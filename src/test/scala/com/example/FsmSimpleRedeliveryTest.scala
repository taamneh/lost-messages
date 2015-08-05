package com.example

import java.util.UUID

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.duration._
import akka.testkit.{ TestProbe,TestActors, TestKit, ImplicitSender }
import com.example.redelivery.{SimpleOrderedRedeliverer, Receiver}
import com.example.redelivery.SimpleOrderedRedeliverer._
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll

class FsmSimpleRedeliveryTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("MySpec"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A Messenger actor" must {
    "keep resending the same message until it receives Ack message" in {
      val redeliverer = system.actorOf(SimpleOrderedRedeliverer.props(retryTimeout = 3.seconds))
      val receiver = TestProbe()
      val uuid = UUID.randomUUID()
      redeliverer ! Deliver(receiver.ref, CreateNetwork, uuid)
      within( 15.seconds) {
        receiveWhile( 10 seconds)
        {
          case _ =>
        }
        receiver.send(redeliverer, Ack(0))
        expectMsg(Delivered( uuid))
      }
    }
  }

  "The Receiver actor" must {
    "Send Ack immediately for old seq" in {
      val redeliverer = TestProbe()
      val receiver = system.actorOf(Receiver.props)
      val uuid = UUID.randomUUID()

      redeliverer.send(receiver, Ackable(self,CreateNetwork , uuid, 0))
      redeliverer.expectMsg(15.seconds, Ack(0))
      redeliverer.send(receiver, Ackable(self,CreateNetwork , uuid, 0))
      redeliverer.expectMsg(15.seconds, Ack(0))
    }
  }

  "A Messenger actor" must {
    "ignore the ack that belong to already received ack" in {
      val redeliverer = system.actorOf(SimpleOrderedRedeliverer.props(retryTimeout = 3.seconds))
      val receiver = TestProbe()
      val uuid = UUID.randomUUID()
      redeliverer ! Deliver(receiver.ref, CreateNetwork, uuid)
      receiver.send(redeliverer, Ack(0))
      // the second message will have seq 1
      redeliverer ! Deliver(receiver.ref, CreateNetwork, uuid)
      // this is not the ack we are looking for, and messenger will keep sending message
      receiver.send(redeliverer, Ack(0))
      within( 15.seconds) {
        receiveWhile( 10 seconds)
        {
          case _ =>
        }
        receiver.send(redeliverer, Ack(1))
        expectMsg(Delivered( uuid))
      }

    }
  }

}
