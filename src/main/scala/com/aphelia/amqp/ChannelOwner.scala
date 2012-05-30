package com.aphelia.amqp

import collection.JavaConversions._
import akka.util.duration._
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client._
import com.aphelia.amqp.ChannelOwner.{Data, State}
import akka.actor.{ActorRef, Actor, FSM}
import com.aphelia.amqp.ConnectionOwner.{CreateChannel, Shutdown}
import collection.mutable

object ChannelOwner {

  sealed trait State

  case object Disconnected extends State

  case object Connected extends State

  private[amqp] sealed trait Data

  private[amqp] case object Uninitialized extends Data

  private[amqp] case class Connected(channel: com.rabbitmq.client.Channel) extends Data

}

class ChannelOwner(channelParams: Option[ChannelParameters] = None) extends Actor with FSM[State, Data] {

  import ChannelOwner._

  startWith(Disconnected, Uninitialized)
  setTimer("getChannel", 'getChannel, 1000 millis, true)

  def isConnected = stateName == Connected

  def onChannel(channel: Channel) {}

  when(Disconnected) {
    case Event('getChannel, _) => {
      context.parent ! CreateChannel
      stay
    }
    case Event(channel: Channel, _) => {
      channelParams.foreach(p => channel.basicQos(p.qos))
      channel.addReturnListener(new ReturnListener() {
        def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) {
          self !('returned, replyCode, replyText, exchange, routingKey, properties, body)
        }
      })
      onChannel(channel)
      goto(Connected) using Connected(channel)
    }
  }
  
  when(Connected) {
    case Event(Shutdown(cause), _) => goto(Disconnected)
    case Event(Publish(exchange, routingKey, body, mandatory, immediate), Connected(channel)) => {
      channel.basicPublish(exchange, routingKey, mandatory, immediate, new AMQP.BasicProperties.Builder().build(), body)
      stay
    }
    case Event(Transaction(publish), Connected(channel)) => {
      channel.txSelect()
      publish.foreach(p => channel.basicPublish(p.exchange, p.key, p.mandatory, p.immediate, new AMQP.BasicProperties.Builder().build(), p.buffer))
      channel.txCommit()
      stay
    }
    case Event(Ack(deliveryTag), Connected(channel)) => {
      channel.basicAck(deliveryTag, false)
      stay
    }
    case Event(Reject(deliveryTag, requeue), Connected(channel)) => {
      channel.basicReject(deliveryTag, requeue)
      stay
    }
    case Event(DeclareExchange(exchange), Connected(channel)) => {
      declareExchange(channel, exchange)
      stay
    }
    case Event(DeclareQueue(queue), Connected(channel)) => {
      declareQueue(channel, queue)
      stay
    }
    case Event(QueueBind(queue, exchange, routing_key, args), Connected(channel)) => {
      channel.queueBind(queue, exchange, routing_key, args)
      stay
    }
  }

  onTransition {
    case Disconnected -> Connected => {
      log.info("connected")
      cancelTimer("getChannel")
    }
    case Connected -> Disconnected => {
      log.warning("disconnect")
      setTimer("getChannel", 'getChannel, 1000 millis, true)
    }
  }

  onTermination {
    case StopEvent(_, Connected, Connected(channel)) => {
      cancelTimer("getChannel")
      try {
        log.info("closing channel")
        channel.close()
      }
      catch {
        case e: Exception => log.warning(e.toString)
      }
    }
  }

  override def preStart() {
    self ! 'getChannel
  }
}

class Consumer(bindings : List[Binding], listener: ActorRef, channelParams : Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {
  var consumer : Option[DefaultConsumer] = None
  
  private def setupBinding(consumer : DefaultConsumer, binding : Binding) = {
    val channel = consumer.getChannel
    val queueName = declareQueue(channel, binding.queue).getQueue
    declareExchange(channel, binding.exchange)
    channel.queueBind(queueName, binding.exchange.name, binding.routingKey)
    channel.basicConsume(queueName, binding.autoack, consumer)
  }

  override def onChannel(channel: Channel) = {
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        listener ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    bindings.foreach(b => setupBinding(consumer.get, b))
  }
}

object RpcServer {

  trait IProcessor {
    def process(data: Array[Byte]): Array[Byte]

    def onFailure(e: Exception): Array[Byte]
  }
}

class RpcServer(queue: QueueParameters, exchange: ExchangeParameters, routingKey: String, processor: RpcServer.IProcessor, channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {
  var consumer : Option[DefaultConsumer] = None
  override def onChannel(channel: Channel) = {   
    val queueName = declareQueue(channel, queue).getQueue
    declareExchange(channel, exchange)
    channel.queueBind(queueName, exchange.name, routingKey)
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        self ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    channel.basicConsume(queueName, false, consumer.get)
  }

  when(ChannelOwner.Connected) {
    case Event(Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
      log.debug("processing delivery")
      try {
        val result = processor.process(body)
        if (properties.getReplyTo != null) {
          val props = new BasicProperties.Builder().correlationId(properties.getCorrelationId).build()
          channel.basicPublish("", properties.getReplyTo, true, false, props, result)
        }
        channel.basicAck(envelope.getDeliveryTag, false)
      }
      catch {
        case e: Exception => {
          // if a request could not be processed twice, send back an error
          if (envelope.isRedeliver) {
            if (properties.getReplyTo != null) {
              val props = new BasicProperties.Builder().correlationId(properties.getCorrelationId).build()
              channel.basicPublish("", properties.getReplyTo, true, false, props, processor.onFailure(e))
            }
            channel.basicAck(envelope.getDeliveryTag, false)
          }
          // else requeue the message, it will be picked up by another consumer
          else {
            log.error("processing failed twice, returning error buffer")
            channel.basicReject(envelope.getDeliveryTag, true)
          }
        }
      }
      stay
    }
  }
}

object RpcClient {

  private[amqp] case class RpcResult(destination: ActorRef, expected: Int, buffers: scala.collection.mutable.ListBuffer[Array[Byte]])

  private[amqp] case class Request(publish: List[Publish], numberOfResponses: Int)

  case class Response(buffers: List[Array[Byte]])

}

class RpcClient(channelParams: Option[ChannelParameters] = None) extends ChannelOwner(channelParams) {
  import RpcClient._
  var queue: String = ""
  var consumer: Option[DefaultConsumer] = None
  var counter: Int = 0
  var correlationMap = scala.collection.mutable.Map.empty[String, RpcResult]

  override def onChannel(channel: Channel) = {
    queue = declareQueue(channel, QueueParameters("", passive = false, exclusive = true)).getQueue
    consumer = Some(new DefaultConsumer(channel) {
      override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
        self ! Delivery(consumerTag, envelope, properties, body)
      }
    })
    channel.basicConsume(queue, false, consumer.get)
    correlationMap.clear()
  }

  when(ChannelOwner.Connected) {
    case Event(Request(publish, numberOfResponses), ChannelOwner.Connected(channel)) => {
      counter = counter + 1
      val props = new BasicProperties.Builder().correlationId(counter.toString).replyTo(queue).build()
      publish.foreach(r => channel.basicPublish(r.exchange, r.key, r.mandatory, r.immediate, props, r.buffer))
      correlationMap += (counter.toString -> RpcResult(sender, numberOfResponses, collection.mutable.ListBuffer.empty[Array[Byte]]))
      stay
    }
    case Event(Delivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]), ChannelOwner.Connected(channel)) => {
      channel.basicAck(envelope.getDeliveryTag, false)
      if (correlationMap.contains(properties.getCorrelationId)) {
        val results = correlationMap.get(properties.getCorrelationId).get
        results.buffers += body
        if (results.buffers.length == results.expected) {
          results.destination ! Response(results.buffers.toList)
          correlationMap -= properties.getCorrelationId
        }
      }
      else {
        log.warning("unexpected message with correlation id " + properties.getCorrelationId)
      }
      stay
    }
  }
}