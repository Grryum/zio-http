/*
 * Copyright 2021 - 2023 Sporta Technologies PVT LTD & the ZIO HTTP contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.http

import zio._
import zio.http.html.Html
import zio.http.internal.HeaderOps
import zio.stream.ZStream

import java.nio.file.{AccessDeniedException, NotDirectoryException}
import scala.annotation.tailrec

final case class Response(
  status: Status,
  headers: Headers,
  body: Body,
  socketApp: Option[SocketApp[Any]],
  frozen: Boolean,
) extends HeaderOps[Response] {
  def addCookie(cookie: Cookie.Response): Response =
    copy(headers = headers ++ Headers(Header.SetCookie(cookie)))

  /**
   * Updates the current Headers with new one, using the provided update
   * function passed.
   */
  override def updateHeaders(update: Headers => Headers): Response =
    copy(headers = update(headers))

  final def status(status: Status): Response =
    copy(status = status)

  //  /**
  //   * Collects the potentially streaming body of the response into a single
  //   * chunk.
  //   */
  //  def collect: ZIO[Any, Throwable, Response] =
  //    if (body.isComplete) ZIO.succeed(self)
  //    else
  //      body.asChunk.map { bytes =>
  //        copy(body = Body.fromChunk(bytes))
  //      }
  //
}


object Response {

  //  private trait InternalState extends Response {
  //    private[Response] def parent: Response
  //
  //    override def frozen: Boolean = parent.frozen
  //
  //    override def addServerTime: Boolean = parent.addServerTime
  //  }

  object GetApp {
    def unapply(response: Response): Option[SocketApp[Any]] =
      response.socketApp
  }

  //  object GetError {
  //    def unapply(response: Response): Option[HttpError] = response match {
  //      case resp: ErrorResponse => Some(resp.httpError0)
  //      case _                   => None
  //    }
  //  }

  private[zio] trait CloseableResponse {
    def close(implicit trace: Trace): Task[Unit]
  }

  private[zio] class NativeResponse(
    response: Response,
    onClose: () => Task[Unit],
  ) extends CloseableResponse { self =>

    override final def close(implicit trace: Trace): Task[Unit] = onClose()

    override final def toString(): String =
      s"NativeResponse(status = ${response.status}, headers = ${response.headers}, body = ${response.body})"

  }

  /**
   * Models the set of operations that one would want to apply on a Response.
   */
  sealed trait Patch { self =>
    def ++(that: Patch): Patch         = Patch.Combine(self, that)
    def apply(res: Response): Response = {

      @tailrec
      def loop(res: Response, patch: Patch): Response =
        patch match {
          case Patch.Empty                  => res
          case Patch.AddHeaders(headers)    => res.addHeaders(headers)
          case Patch.RemoveHeaders(headers) => res.removeHeaders(headers)
          case Patch.SetStatus(status)      => res.status(status)
          case Patch.Combine(self, other)   => loop(self(res), other)
          case Patch.UpdateHeaders(f)       => res.updateHeaders(f)
        }

      loop(res, self)
    }
  }

  object Patch {
    import Header.HeaderType

    case object Empty                                     extends Patch
    final case class AddHeaders(headers: Headers)         extends Patch
    final case class RemoveHeaders(headers: Set[String])  extends Patch
    final case class SetStatus(status: Status)            extends Patch
    final case class Combine(left: Patch, right: Patch)   extends Patch
    final case class UpdateHeaders(f: Headers => Headers) extends Patch

    def empty: Patch = Empty

    def addHeader(headerType: HeaderType)(value: headerType.HeaderValue): Patch =
      addHeader(headerType.name, headerType.render(value))

    def addHeader(header: Header): Patch                          = addHeaders(Headers(header))
    def addHeaders(headers: Headers): Patch                       = AddHeaders(headers)
    def addHeader(name: CharSequence, value: CharSequence): Patch = addHeaders(Headers(name, value))

    def removeHeaders(headerTypes: Set[HeaderType]): Patch = RemoveHeaders(headerTypes.map(_.name))
    def status(status: Status): Patch                      = SetStatus(status)
    def updateHeaders(f: Headers => Headers): Patch        = UpdateHeaders(f)
  }

  def apply(
    status: Status = Status.Ok,
    headers: Headers = Headers.empty,
    body: Body = Body.empty,
  ): Response = new Response(status, headers, body, None, false)

  def badRequest: Response = error(Status.BadRequest)

  def badRequest(message: String): Response = error(Status.BadRequest, message)

  def error(status: Status, message: String): Response = {
    import zio.http.internal.OutputEncoder

    val message2 = OutputEncoder.encodeHtml(if (message == null) status.text else message)

    Response(status = status, headers = Headers(Header.Warning(status.code, "ZIO HTTP", message2)))
  }

  def error(status: Status): Response =
    error(status, status.text)

  def forbidden: Response = error(Status.Forbidden)

  def forbidden(message: String): Response = error(Status.Forbidden, message)

  /**
   * Creates a new response from the specified cause. Note that this method is
   * not polymorphic, but will attempt to inspect the runtime class of the
   * failure inside the cause, if any.
   */
  def fromCause(cause: Cause[Any]): Response = {
    cause.failureOrCause match {
      case Left(failure: Response)  => failure
      case Left(failure: Throwable) => fromThrowable(failure)
      case Left(failure: Cause[_])  => fromCause(failure)
      case _                        =>
        if (cause.isInterruptedOnly) error(Status.RequestTimeout, cause.prettyPrint.take(100))
        else error(Status.InternalServerError, cause.prettyPrint.take(100))
    }
  }

  /**
   * Creates a new response from the specified cause, translating any typed
   * error to a response using the provided function.
   */
  def fromCauseWith[E](cause: Cause[E])(f: E => Response): Response = {
    cause.failureOrCause match {
      case Left(failure) => f(failure)
      case Right(cause)  => fromCause(cause)
    }
  }

  def fromHttpError(error: HttpError): Response =
    Response(error.status, Headers.empty, Body.fromString(error.message))

  /**
   * Creates a response with content-type set to text/event-stream
   * @param data
   *   \- stream of data to be sent as Server Sent Events
   */
  def fromServerSentEvents(data: ZStream[Any, Nothing, ServerSentEvent]): Response =
    Response(Status.Ok, Headers.empty, Body.fromStream(data.map(_.encode)))

  /**
   * Creates a new response for the provided socket
   */
  def fromSocket[R](
    http: Handler[R, Throwable, WebSocketChannel, Any],
  )(implicit trace: Trace): ZIO[R, Nothing, Response] =
    fromSocketApp(http)

  /**
   * Creates a new response for the provided socket app
   */
  def fromSocketApp[R](app: SocketApp[R])(implicit trace: Trace): ZIO[R, Nothing, Response] = {
    ZIO.environment[R].map { env =>
      Response(
        Status.SwitchingProtocols,
        Headers.empty,
        Body.empty,
        Some(app.provideEnvironment(env)),
        false,
      )
    }
  }

  /**
   * Creates a new response for the specified throwable. Note that this method
   * relies on the runtime class of the throwable.
   */
  def fromThrowable(throwable: Throwable): Response = {
    throwable match { // TODO: Enhance
      case _: AccessDeniedException           => error(Status.Forbidden, throwable.getMessage)
      case _: IllegalAccessException          => error(Status.Forbidden, throwable.getMessage)
      case _: IllegalAccessError              => error(Status.Forbidden, throwable.getMessage)
      case _: NotDirectoryException           => error(Status.BadRequest, throwable.getMessage)
      case _: IllegalArgumentException        => error(Status.BadRequest, throwable.getMessage)
      case _: java.io.FileNotFoundException   => error(Status.NotFound, throwable.getMessage)
      case _: java.net.ConnectException       => error(Status.ServiceUnavailable, throwable.getMessage)
      case _: java.net.SocketTimeoutException => error(Status.GatewayTimeout, throwable.getMessage)
      case _                                  => error(Status.InternalServerError, throwable.getMessage)
    }
  }

  def gatewayTimeout: Response = error(Status.GatewayTimeout)

  def gatewayTimeout(message: String): Response = error(Status.GatewayTimeout, message)

  /**
   * Creates a response with content-type set to text/html
   */
  def html(data: Html, status: Status = Status.Ok): Response =
    Response(status, contentTypeHtml, Body.fromString("<!DOCTYPE html>" + data.encode))

  def httpVersionNotSupported: Response = error(Status.HttpVersionNotSupported)

  def httpVersionNotSupported(message: String): Response = error(Status.HttpVersionNotSupported, message)

  def internalServerError: Response = error(Status.InternalServerError)

  def internalServerError(message: String): Response = error(Status.InternalServerError, message)

  /**
   * Creates a response with content-type set to application/json
   */
  def json(data: CharSequence): Response =
    Response(Status.Ok, contentTypeJson, Body.fromCharSequence(data))

  def networkAuthenticationRequired: Response = error(Status.NetworkAuthenticationRequired)

  def networkAuthenticationRequired(message: String): Response = error(Status.NetworkAuthenticationRequired, message)

  def notExtended: Response = error(Status.NotExtended)

  def notExtended(message: String): Response = error(Status.NotExtended, message)

  def notFound: Response = error(Status.NotFound)

  def notFound(message: String): Response = error(Status.NotFound, message)

  def notImplemented: Response = error(Status.NotImplemented)

  def notImplemented(message: String): Response = error(Status.NotImplemented, message)

  /**
   * Creates an empty response with status 200
   */
  def ok: Response = Response(Status.Ok)

  /**
   * Creates an empty response with status 301 or 302 depending on if it's
   * permanent or not.
   */
  def redirect(location: URL, isPermanent: Boolean = false): Response = {
    val status = if (isPermanent) Status.PermanentRedirect else Status.TemporaryRedirect
    Response(status, Headers(Header.Location(location)), Body.empty)
  }

  /**
   * Creates an empty response with status 303
   */
  def seeOther(location: URL): Response =
    Response(Status.SeeOther, Headers(Header.Location(location)), Body.empty)

  def serviceUnavailable: Response = error(Status.ServiceUnavailable)

  def serviceUnavailable(message: String): Response = error(Status.ServiceUnavailable, message)

  /**
   * Creates an empty response with the provided Status
   */
  def status(status: Status): Response = Response(status, Headers.empty, Body.empty)

  /**
   * Creates a response with content-type set to text/plain
   */
  def text(text: CharSequence): Response =
    Response(
      Status.Ok,
      contentTypeText,
      Body.fromCharSequence(text),
    )

  def unauthorized: Response = error(Status.Unauthorized)

  def unauthorized(message: String): Response = error(Status.Unauthorized, message)

  private lazy val contentTypeJson: Headers        = Headers(Header.ContentType(MediaType.application.json).untyped)
  private lazy val contentTypeHtml: Headers        = Headers(Header.ContentType(MediaType.text.html).untyped)
  private lazy val contentTypeText: Headers        = Headers(Header.ContentType(MediaType.text.plain).untyped)
  private lazy val contentTypeEventStream: Headers =
    Headers(Header.ContentType(MediaType.text.`event-stream`).untyped)
}
