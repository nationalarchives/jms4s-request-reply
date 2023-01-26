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

import cats.data.NonEmptyList
import cats.effect.implicits.genSpawnOps
import cats.effect.kernel.Sync
import cats.effect.{Async, Resource}
import jms4s.JmsAcknowledgerConsumer.AckAction
import jms4s.activemq.activeMQ
import jms4s.activemq.activeMQ.{Password, Username}
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
 * @author <a href="mailto:adam@evolvedbinary.com">Adam Retter</a>
 */
class JmsRRClient[F[_]: Async: Logger](requestMap: ConcurrentHashMap[String, ReplyMessageHandler[F]])(consumer: Unit, producer: JmsProducer[F]) {

  def request(requestQueue: String, jmsRequest: RequestMessage, replyMessageHandler: ReplyMessageHandler[F])(implicit F: Async[F]) : F[Unit] = {

    val sender: F[Option[String]] = producer.send { mf =>
      val jmsMessage: F[JmsMessage.JmsTextMessage] = mf.makeTextMessage(jmsRequest.body)
      F.map(jmsMessage)(jmsMessage =>
        jmsMessage.setStringProperty("sid", jmsRequest.sid) match {
          case Success(_) => (jmsMessage, QueueName(requestQueue))
          case Failure(e) =>
            // TODO log the error and find a way out of sending the message
            (jmsMessage, QueueName(requestQueue))
        })
    }

    F.flatMap(sender) {
      case Some(messageId) =>
        F.delay(requestMap.put(messageId, replyMessageHandler))
      case None =>
        F.raiseError(new IllegalStateException("No messageId obtainable from JMS but application requires messageId support"))
    }
  }
}

object JmsRRClient {

  type ReplyMessageHandler[F[_]] = ReplyMessage => F[Unit]

  private val defaultConsumerConcurrencyLevel = 1 //0
  private val defaultConsumerPollingInterval = 50.millis
  private val defaultProducerConcurrencyLevel = 1 //0

  /**
   * Create a JMS Request-Reply Client for use with Apache Active MQ.
   *
   * @param endpoint the ActiveMQ broker endpoint to connect to.
   * @param credentials the credentials for connecting to the ActiveMQ broker.
   * @param customClientId an optional Custom client ID to identify this client.
   *
   * @param replyQueue the queue that replies should be consumed from.
   *
   * @return The resource for the JMS Request-Reply Client.
   */
  def createForActiveMq[F[_]: Async: Logger](endpoint: HostBrokerEndpoint, credentials: UsernamePasswordCredentials, customClientId: Option[F[String]] = None)(replyQueue: String): Resource[F, JmsRRClient[F]] = {
    val clientIdRes: Resource[F, String] = Resource.liftK[F](customClientId.getOrElse(RandomClientIdGen.randomClientId[F]))

    val jmsClientRes: Resource[F, JmsClient[F]] = clientIdRes.flatMap { clientId =>
      activeMQ.makeJmsClient[F](activeMQ.Config(
        endpoints = NonEmptyList.one(activeMQ.Endpoint(endpoint.host, endpoint.port)),
        username = Some(Username(credentials.username)),
        password = Some(Password(credentials.password)),
        clientId = activeMQ.ClientId(clientId)
      ))
    }

    create[F](jmsClientRes)(replyQueue)
  }

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
  private def jmsConsumerHandler[F[_]: Async: Logger](requestMap: ConcurrentHashMap[String, ReplyMessageHandler[F]])(jmsMessage: JmsMessage, mf: MessageFactory[F])(implicit F: Async[F], L: Logger[F]): F[AckAction[F]] = {
    val maybeCorrelatedRequestHandler: F[Option[ReplyMessageHandler[F]]] = F.delay(jmsMessage.getJMSCorrelationId.flatMap(correlationId => Option(requestMap.remove(correlationId))))

    val maybeHandled: F[Unit] = F.flatMap(maybeCorrelatedRequestHandler) {
      case Some(correlatedRequestHandler) =>
        correlatedRequestHandler(ReplyMessage(jmsMessage.attemptAsText.get))
      case None =>
        L.error("No request found for response '${jmsMessage.attemptAsText.get}'")
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
