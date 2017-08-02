package ru.smslv.akka.dns.raw


import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.{Paths, Files}
import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import akka.io.{IO, Udp}

class DnsClient(ns: InetSocketAddress, upstream: ActorRef) extends Actor with ActorLogging with Stash {

  import context.system

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(InetAddress.getByAddress(Array.ofDim(4)), 0))

  def receive = {
    case Udp.Bound(local) =>
      log.debug(s"Bound to UDP address $local")
      context.become(ready(sender()))
      unstashAll()
    case r: Question4 =>
      stash()
    case r: Question6 =>
      stash()
    case r: SrvQuestion =>
      stash()
  }

  def ready(socket: ActorRef): Receive = {
    {
      case Question4(id, name) =>
        log.warning(s"Q4 AKKA-DNS: Resolving $name (A)")
        val msg4 = Message(id,
          MessageFlags(recursionDesired = true),
          Seq(
            Question(name, RecordType.A, RecordClass.IN)
          ))
        log.warning(s"Q4 Message to NS $ns: $msg4")
        socket ! Udp.Send(msg4.write(), ns)

      case Question6(id, name) =>
        log.warning(s"Q6 AKKA-DNS: Resolving $name (AAAA)")
        val msg6 = Message(id,
          MessageFlags(recursionDesired = true),
          Seq(
            Question(name, RecordType.AAAA, RecordClass.IN)
          ))
        log.debug(s"Q6 Message to NS $ns: $msg6")
        socket ! Udp.Send(msg6.write(), ns)

      case SrvQuestion(id, name) =>
        log.warning(s"SRV AKKA-DNS: Resolving $name (SRV)")
        val msg = Message(id,
          MessageFlags(recursionDesired = true),
          Seq(
            Question(name, RecordType.SRV, RecordClass.IN)
          ))
        log.warning(s"SRV Message to $ns: $msg")
        socket ! Udp.Send(msg.write(), ns)

      case Udp.Received(data, remote) =>
        log.warning(s"AKKA-DNS: Received message from $remote: ${data}")
        val msg = Message.parse(data)
        log.warning(s"AKKA-DNS: Decoded: $msg")
        log.warning(s"AKKA-DNS: Response code : ${msg.flags.responseCode}")
        if (msg.flags.responseCode == ResponseCode.SUCCESS) {
          log.warning(s"AKKA-DNS: respond with success: $msg")
          upstream ! Answer(msg.id, msg.answerRecs)
        } else {
          log.warning(s"AKKA-DNS: respond NOT success: $msg")
          upstream ! Answer(msg.id, Seq())
        }

        val filePath = s"/tmp/result-${UUID.randomUUID()}"
        log.warning(s"AKKA-DNS: saving resolved data to ${filePath}")
        Files.write(Paths.get(filePath), data.toArray[Byte])
        log.warning(s"AKKA-DNS: saving resolved data to ${filePath} - saved")


      case Udp.Unbind => socket ! Udp.Unbind
      case Udp.Unbound => context.stop(self)
    }
  }
}

case class SrvQuestion(id: Short, name: String)

case class Question4(id: Short, name: String)

case class Question6(id: Short, name: String)

case class Answer(id: Short, rrs: Iterable[ResourceRecord])
