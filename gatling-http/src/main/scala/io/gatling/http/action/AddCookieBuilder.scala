/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.action

import org.asynchttpclient.cookie.Cookie

import akka.actor.ActorDSL.actor
import akka.actor.ActorRef
import org.asynchttpclient.uri.Uri
import io.gatling.core.action.SessionHook
import io.gatling.core.config.Protocols
import io.gatling.core.session.{ Expression, Session }
import io.gatling.core.validation.{ FailureWrapper, SuccessWrapper }
import io.gatling.http.config.HttpProtocol
import io.gatling.http.cookie.CookieHandling.storeCookie

case class CookieDSL(name: Expression[String], value: Expression[String],
                     domain: Option[Expression[String]] = None,
                     path: Option[Expression[String]] = None,
                     expires: Option[Long] = None,
                     maxAge: Option[Int] = None) {

  def withDomain(domain: Expression[String]) = copy(domain = Some(domain))
  def withPath(path: Expression[String]) = copy(path = Some(path))
  def withExpires(expires: Long) = copy(expires = Some(expires))
  def withMaxAge(maxAge: Int) = copy(maxAge = Some(maxAge))
}

object AddCookieBuilder {

  val NoBaseUrlFailure = "Neither cookie domain nor baseURL".failure
  val RootSuccess = "/".success
  val DefaultPath: Expression[String] = _ => RootSuccess

  def defaultDomain(httpProtocol: HttpProtocol) = {
    val baseUrlHost = httpProtocol.baseURL.map(url => Uri.create(url).getHost)
    (session: Session) => baseUrlHost match {
      case Some(host) => host.success
      case _          => NoBaseUrlFailure
    }
  }
}

class AddCookieBuilder(name: Expression[String], value: Expression[String], domain: Option[Expression[String]], path: Option[Expression[String]], expires: Long, maxAge: Int) extends HttpActionBuilder {

  import AddCookieBuilder._

  def build(next: ActorRef, protocols: Protocols): ActorRef = {

    val resolvedDomain = domain.getOrElse(defaultDomain(httpProtocol(protocols)))
    val resolvedPath = path.getOrElse(DefaultPath)

    val expression: Expression[Session] = session => for {
      name <- name(session)
      value <- value(session)
      domain <- resolvedDomain(session)
      path <- resolvedPath(session)
      cookie = new Cookie(name, value, value, domain, path, expires, maxAge, false, false)
    } yield storeCookie(session, domain, path, cookie)

    actor(actorName("addCookie"))(new SessionHook(expression, next))
  }
}
