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

import cats.effect.IO
import cats.effect.std.Queue
import org.typelevel.log4cats.Logger
import uk.gov.nationalarchives.omega.jms.JmsRRClient.ReplyMessageHandler

case class RequestReplyHandler(jmsRrClient: JmsRRClient[IO]) {

  /**
   * Convenience method for binding a request and its reply
   * @param requestQueue the JMS queue to send the message to
   * @param requestMessage the JMS message
   * @return
   */
  def handle(requestQueue: String, requestMessage: RequestMessage)(implicit L: Logger[IO]): IO[String] =
    Queue.bounded[IO, String](1).flatMap { editSetQueue =>
      val replyHandler: ReplyMessageHandler[IO] = replyMessage => editSetQueue.offer(replyMessage.body)
      jmsRrClient.request(requestQueue, requestMessage, replyHandler) flatMap { _ =>
        editSetQueue.take
      }
    }

}
