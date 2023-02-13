/*
 * Copyright (c) 2023 The National Archives
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package uk.gov.nationalarchives.omega.jms

import cats.effect.implicits.genSpawnOps
import cats.effect.kernel.Sync
import cats.effect.{Async, Resource}
import jms4s.JmsAcknowledgerConsumer.AckAction
import jms4s.config.QueueName
import jms4s.jms.{JmsMessage, MessageFactory}
import jms4s.sqs.simpleQueueService
import jms4s.sqs.simpleQueueService.{Credentials, DirectAddress, HTTP}
import jms4s.{JmsClient, JmsProducer}
import org.typelevel.log4cats.Logger
import uk.gov.nationalarchives.omega.jms.JmsRRClient.ReplyMessageHandler

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

case class RequestMessage(body: String, sid: String)
case class ReplyMessage(body: String)

/**
 * A JMS Request-Reply client.
 * Suitable for being packaged as a library for abstracting
 * away the complexities of JMS and jms4s.
 *
 * @param requestMap - a map of the requests sent and their reply handlers
 * @param consumer - unused
 * @param producer - the JmsProducer used to send the message
 * @tparam F
 */
class JmsRRClient[F[_]: Async: Logger](requestMap: ConcurrentHashMap[String, ReplyMessageHandler[F]])(consumer: Unit, producer: JmsProducer[F]) {

  /**
   *
   * @param requestQueue - the queue to send the message to
   * @param jmsRequest  - the request message
   * @param replyMessageHandler - the reply handler
   * @return
   */
  def request(requestQueue: String, jmsRequest: RequestMessage, replyMessageHandler: ReplyMessageHandler[F])(implicit L: Logger[F]): F[Unit] = {

    /* calls the JmsProducer send method and returns an optional message ID */
    val sender: F[Option[String]] = producer.send { mf =>
      val jmsMessage: F[JmsMessage.JmsTextMessage] = mf.makeTextMessage(jmsRequest.body)
      Async[F].map(jmsMessage)(jmsMessage =>
        jmsMessage.setStringProperty("sid", jmsRequest.sid) match {
          case Success(_) => (jmsMessage, QueueName(requestQueue))
          case Failure(e) =>
            // TODO (RW) there must be a better way to handle this
            L.error(s"Failed to set SID due to ${e.getMessage}")
            (jmsMessage, QueueName(requestQueue))
        })
    }

    Async[F].flatMap(sender) {
      case Some(messageId) =>
        Async[F].delay {
          val _ = requestMap.put(messageId, replyMessageHandler)
          ()
        }
      case None =>
        Async[F].raiseError(new IllegalStateException("No messageId obtainable from JMS but application requires messageId support"))
    }
  }
}

object JmsRRClient {

  type ReplyMessageHandler[F[_]] = ReplyMessage => F[Unit]

  private val defaultConsumerConcurrencyLevel = 1
  private val defaultConsumerPollingInterval = 50.millis
  private val defaultProducerConcurrencyLevel = 1

 /**
   * Create a JMS Request-Reply Client for use with Amazon Simple Queue Service.
   *
   * @param credentials the credentials for connecting to the ActiveMQ broker.
   * @param customClientId an optional Custom client ID to identify this client.
   *
   * @param replyQueue the queue that replies should be consumed from.
   *
   * @return The resource for the JMS Request-Reply Client.
   */
  def createForSqs[F[_]: Async: Logger](endpoint: HostBrokerEndpoint, credentials: UsernamePasswordCredentials, customClientId: Option[F[String]] = None)(replyQueue: String): Resource[F, JmsRRClient[F]] = {
    val clientIdRes: Resource[F, String] = Resource.liftK[F](customClientId.getOrElse(RandomClientIdGen.randomClientId[F]))

    val jmsClientRes: Resource[F, JmsClient[F]] = clientIdRes.flatMap { clientId =>
      simpleQueueService.makeJmsClient[F](simpleQueueService.Config(
        endpoint = simpleQueueService.Endpoint(Some(DirectAddress(HTTP,endpoint.host,Some(endpoint.port))),"elasticmq"),
        credentials = Some(Credentials(credentials.username, credentials.password)),
        clientId = simpleQueueService.ClientId(clientId),
        None
      ))
    }

    create[F](jmsClientRes)(replyQueue)
  }

  /**
   * Create a JMS Request-Reply Client.
   *
   * @param jmsClientRes a jms4s Client resource.
   *
   * @param replyQueue the queue that replies should be consumed from.
   *
   * @return The resource for the JMS Request-Reply Client.
   */
  def create[F[_]: Async: Sync: Logger](jmsClientRes: Resource[F, JmsClient[F]])(replyQueue: String): Resource[F, JmsRRClient[F]] = {
    for {
      requestMap <- Resource.pure(new ConcurrentHashMap[String, ReplyMessage => F[Unit]](16, 0.75f, defaultProducerConcurrencyLevel))
      jmsClient <- jmsClientRes
      consumer <- jmsClient.createAcknowledgerConsumer(QueueName(replyQueue), concurrencyLevel = defaultConsumerConcurrencyLevel, pollingInterval = defaultConsumerPollingInterval)
      consumerHandlerRes = consumer.handle(jmsConsumerHandler[F](requestMap)(_, _)).background
      producerRes = jmsClient.createProducer(concurrencyLevel = defaultProducerConcurrencyLevel)

      // tie the life-times of consumerHandler and producer together
      consumerProducer <- Resource.both(consumerHandlerRes, producerRes)
    } yield new JmsRRClient[F](requestMap)(consumerProducer._1, consumerProducer._2)
  }

  /**
   * A jms4s consumer handler that consumes a reply message,
   * finds the ReplyMessageHandler and dispatches the message
   * to it.
   */
  //private def jmsConsumerHandler[F[_]: Async: Logger](requestMap: ConcurrentHashMap[String, ReplyMessageHandler[F]])(jmsMessage: JmsMessage, mf: MessageFactory[F])(implicit F: Async[F], L: Logger[F]): F[AckAction[F]] = {
  def jmsConsumerHandler[F[_]: Async: Logger](requestMap: ConcurrentHashMap[String, ReplyMessageHandler[F]])(jmsMessage: JmsMessage, mf: MessageFactory[F])(implicit F: Async[F], L: Logger[F]): F[AckAction[F]] = {
    // the correlated request handler gets the correlation id from the incoming message and checks the requestMap for it - if it is found it is removed from the request map and the mapped
    // reply handler is called. The message is also acknowledged.
    val maybeCorrelatedRequestHandler: F[Option[ReplyMessageHandler[F]]] = F.delay(jmsMessage.getJMSCorrelationId.flatMap(correlationId => Option(requestMap.remove(correlationId))))

    val maybeHandled: F[Unit] = F.flatMap(maybeCorrelatedRequestHandler) {
      case Some(correlatedRequestHandler) =>
        correlatedRequestHandler(ReplyMessage(jmsMessage.attemptAsText.get))
      case None =>
        L.error(s"No request found for response '${jmsMessage.attemptAsText.get}'")
      // TODO(AR) maybe record/report these somewhere better...
    }

    F.*>(maybeHandled)(F.pure(AckAction.ack[F]))  // acknowledge receipt of the message
  }

}

private trait RandomClientIdGen[F[_]] {

  /**
   * Generates a ClientId pseudorandom manner.
   * @return randomly generated ClientId
   */
  def randomClientId: F[String]
}

private object RandomClientIdGen {
  def apply[F[_]](implicit ev: RandomClientIdGen[F]): RandomClientIdGen[F] = ev

  def randomClientId[F[_]: RandomClientIdGen]: F[String] = RandomClientIdGen[F].randomClientId

  implicit def fromSync[F[_]](implicit ev: Sync[F]): RandomClientIdGen[F] = new RandomClientIdGen[F] {
    override final val randomClientId: F[String] = {
      ev.map(ev.blocking(UUID.randomUUID()))(uuid => s"jms-rr-client-$uuid")
    }
  }
}

sealed trait BrokerEndpoint
case class HostBrokerEndpoint(host: String, port: Int) extends BrokerEndpoint
sealed trait RRCredentials
case class UsernamePasswordCredentials(username: String, password: String) extends RRCredentials

