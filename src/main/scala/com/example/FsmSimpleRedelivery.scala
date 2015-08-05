/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package com.example.redelivery

import akka.actor.FSM.Event
import akka.actor._
import com.example.redelivery.SimpleOrderedRedeliverer.{Deliver, CreateNetwork}
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import java.util.UUID

object SimpleOrderedRedeliverer {
  /**
   * Props for creating a [[SimpleOrderedRedeliverer]].
   */
  def props(retryTimeout: FiniteDuration) = Props(classOf[SimpleOrderedRedeliverer], retryTimeout)
  /*
   * Messages exchanged with the requester of the delivery.
   */

  case object CreateNetwork
  case class Deliver(to: ActorRef, msg: Any, uuid: UUID)
  case class Delivered(uuid: UUID)
  case class AcceptedForDelivery(uuid: UUID)
  case class Busy(refused: UUID, currentlyProcessing: UUID)

  /*
   * Messages exchanged with the “deliveree”.
   */
  case class Ackable(from: ActorRef, msg: Any, uuid: UUID, seq: Long)
  case class Ack( seq: Long)

  /*
   * Various states the [[SimpleOrderedRedeliverer]] can be in.
   */
  sealed trait State
  case object Idle extends State
  case object AwaitingAck extends State

  sealed trait Data
  case object NoData extends Data

  /**
   * Keeps track of our last delivery request.
   */
  case class LastRequest(last: Deliver, requester: ActorRef, seq: Long) extends Data

  /**
   * Private message used only inside of the [[SimpleOrderedRedeliverer]] to signalize a tick of its retry timer.
   */
  private case object Retry
}

/**
 * An actor-in-the-middle kind. Takes care of message redelivery between two or more sides.
 *
 * Works “sequentially”, thus is able to process only one message at a time:
 *
 * <pre>
 *   Delivery-request#1 -> ACK#1 -> Delivery-request#2 -> ACK#2 -> ...
 * </pre>
 *
 * A situation like this:
 *
 * <pre>
 *   Delivery-request#1 -> Delivery-request#2 -> ...
 * </pre>
 *
 * ... will result in the second requester getting a [[SimpleOrderedRedeliverer.Busy]] message with [[UUID]]s
 * of both his request and currently-processed one.
 *
 * @param retryTimeout how long to wait for the [[SimpleOrderedRedeliverer.Ack]] message
 */
class SimpleOrderedRedeliverer(retryTimeout: FiniteDuration) extends Actor with FSM[SimpleOrderedRedeliverer.State, SimpleOrderedRedeliverer.Data] {
  import SimpleOrderedRedeliverer._


  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  // So that we don't make a typo when referencing this timer.
  val RetryTimer = "retry"

  // Start idle with neither last request, nor most recent requester.
  startWith(Idle, NoData)

  /**
   * Will process the provided request, sending an [[Ackable]] to its recipient and resetting the inner timer.
   * @return a new post-processing state.
   */
  def process(request: Deliver, requester: ActorRef, seq: Long): State = {
    request.to ! Ackable(requester, request.msg, request.uuid, seq)
    println( s"""  [Messenger] will send "${request.msg}"; with seq = ${seq} """)
    setTimer(RetryTimer, Retry, retryTimeout, repeat = false)
    goto(AwaitingAck) using LastRequest(request, requester, seq)
  }

  /*
   * When [[Idle]], accept new requests and process them, replying with [[WillTry]].
   */
  when(Idle) {
    case Event(request: Deliver, _) =>
      process(request, sender(), nextSeq) replying AcceptedForDelivery(request.uuid)
  }

  when(AwaitingAck) {

    /*
     * When awaiting the [[Ack]] and receiver seems not to have made it,
     * resend the message wrapped in [[Ackable]]. This time, however, without
     * sending [[WillTry]] to our requester!
     */
    case Event(Retry, LastRequest(request, requester, seq)) =>
      process(request, requester, seq)

    /*
     * Fortunately, the receiver made it! It his is an [[Ack]] of correct [[UUID]],
     * cancel the retry timer, notify original requester with [[Delivered]] message,
     * and turn [[Idle]] again.
     */
    case Event(Ack(seq1), LastRequest(request, requester, seq2)) if seq1 == seq2 =>
      cancelTimer(RetryTimer)
      requester ! Delivered(request.uuid)
      goto(Idle) using NoData

    /*
     * Someone (possibly else!) is trying to make the [[SimpleOrderedRedeliverer]] deliver a new message,
     * while an [[Ack]] for the last one has not yet been delivered. Reject.
     */
    case Event(request: Deliver, LastRequest(current, _, _)) =>
      stay() replying Busy(request.uuid, current.uuid)
  }

}

object Receiver {
  /**
   * Props for creating a [[Receiver]].
   */
  def props = Props(classOf[Receiver])
}

class Receiver extends Actor {
    
    import context.dispatcher
  /**
   * Simulate loosing 75% of all messages on the receiving end. We want to see the redelivery in action!
   */


  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  def shouldSendAck = true //ThreadLocalRandom.current.nextDouble() < 0.25

  def receive = {
   case SimpleOrderedRedeliverer.Ackable(from, msg, uuid, seq) =>
      if(_seqCounter == seq) {
        // to avoid processing the old messages
        val goingToSendAck = shouldSendAck
        println( s"""  [NetworkDriver] got "$msg"; with seq = ${seq} ${if (goingToSendAck) "" else " ***NOT***"} going to send Ack this time""")
        // Send a [[SimpleOrderedRedeliverer.Ack]] -- if they're lucky!
        if (goingToSendAck) {
          nextSeq
          sender() ! SimpleOrderedRedeliverer.Ack(seq)
        }
      }
      else if(_seqCounter > seq){
        println(s"""  [NetworkDriver] ignore old messages with seq ${seq}""")
        sender() !  SimpleOrderedRedeliverer.Ack(seq)
      }
  }
}



object FsmSimpleRedelivery extends App {

  val system = ActorSystem()
  /*
   * Start a new [[Requester]] actor.
   */
  val redeliverer = system.actorOf(SimpleOrderedRedeliverer.props(retryTimeout = 3.seconds))
  val receiver = system.actorOf(Receiver.props)
  val uuid = UUID.randomUUID()

  redeliverer ! Deliver(receiver, CreateNetwork, uuid)

  //system.actorOf(Requester.props(redeliverer, receiver))

}
