/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
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
package com.excilys.ebi.gatling.http.ahc

import scala.annotation.tailrec
import scala.collection.JavaConversions.asScalaBuffer

import com.excilys.ebi.gatling.core.action.BaseActor
import com.excilys.ebi.gatling.core.check.Check.applyChecks
import com.excilys.ebi.gatling.core.check.Failure
import com.excilys.ebi.gatling.core.config.GatlingConfiguration.configuration
import com.excilys.ebi.gatling.core.result.message.RequestStatus.{ KO, OK, RequestStatus }
import com.excilys.ebi.gatling.core.result.writer.DataWriter
import com.excilys.ebi.gatling.core.session.Session
import com.excilys.ebi.gatling.core.util.StringHelper.END_OF_LINE
import com.excilys.ebi.gatling.core.util.TimeHelper.nowMillis
import com.excilys.ebi.gatling.http.Headers.{ Names => HeaderNames }
import com.excilys.ebi.gatling.http.cache.CacheHandling
import com.excilys.ebi.gatling.http.check.HttpCheck
import com.excilys.ebi.gatling.http.config.HttpProtocolConfiguration
import com.excilys.ebi.gatling.http.cookie.CookieHandling
import com.excilys.ebi.gatling.http.request.ExtendedRequest.extendRequest
import com.excilys.ebi.gatling.http.request.HttpPhase
import com.excilys.ebi.gatling.http.request.HttpPhase.HttpPhase
import com.excilys.ebi.gatling.http.response.{ ExtendedResponse, ExtendedResponseBuilder, ExtendedResponseBuilderFactory }
import com.ning.http.client.{ AsyncHttpClient, FluentStringsMap, Request, RequestBuilder }
import com.ning.http.util.AsyncHttpProviderUtils

import akka.actor.{ ActorRef, ReceiveTimeout }
import akka.util.duration.intToDurationInt

object GatlingAsyncHandlerActor {
	val REDIRECTED_REQUEST_NAME_PATTERN = """(.+?) Redirect (\d+)""".r
	val REDIRECT_STATUS_CODES = 301 to 303
	val NOT_MODIFIED_PHASES = HttpPhase.phases.filter(p => p != HttpPhase.CompletePageReceived && p != HttpPhase.BodyPartReceived)

	val propagatedOnRedirectHeaders = Vector(
		HeaderNames.ACCEPT,
		HeaderNames.ACCEPT_ENCODING,
		HeaderNames.ACCEPT_LANGUAGE,
		HeaderNames.REFERER,
		HeaderNames.USER_AGENT)

	def newAsyncHandlerActorFactory(
		checks: List[HttpCheck[_]],
		next: ActorRef,
		protocolConfiguration: HttpProtocolConfiguration)(requestName: String) = {

		val handlerFactory = GatlingAsyncHandler.newHandlerFactory(checks, protocolConfiguration)
		val responseBuilderFactory = ExtendedResponseBuilder.newExtendedResponseBuilder(checks, protocolConfiguration)

		(request: Request, session: Session) =>
			new GatlingAsyncHandlerActor(
				session,
				checks,
				next,
				requestName,
				request,
				protocolConfiguration,
				handlerFactory,
				responseBuilderFactory)
	}
}

class GatlingAsyncHandlerActor(
	var session: Session,
	checks: List[HttpCheck[_]],
	next: ActorRef,
	var requestName: String,
	var request: Request,
	protocolConfiguration: HttpProtocolConfiguration,
	handlerFactory: HandlerFactory,
	responseBuilderFactory: ExtendedResponseBuilderFactory) extends BaseActor {

	var responseBuilder = responseBuilderFactory(request, session)

	resetTimeout

	def receive = {
		case OnHeaderWriteCompleted(nanos) =>
			resetTimeout
			responseBuilder.updateRequestSendingEndDate(nanos)

		case OnContentWriteCompleted(nanos) =>
			resetTimeout
			responseBuilder.updateRequestSendingEndDate(nanos)

		case OnStatusReceived(status, nanos) =>
			resetTimeout
			responseBuilder.updateResponseReceivingStartDate(nanos)
			responseBuilder.accumulate(status)

		case OnHeadersReceived(headers) =>
			resetTimeout
			responseBuilder.accumulate(headers)

		case OnBodyPartReceived(bodyPart) =>
			resetTimeout
			responseBuilder.accumulate(bodyPart)

		case OnCompleted(nanos) =>
			responseBuilder.computeExecutionEndDateFromNanos(nanos)
			processResponse(responseBuilder.build)

		case OnThrowable(errorMessage, nanos) =>
			responseBuilder.computeExecutionEndDateFromNanos(nanos)
			val response = responseBuilder.build
			logRequest(KO, response, Some(errorMessage))
			executeNext(session.setFailed, response)

		case ReceiveTimeout =>
			val response = responseBuilder.build
			logRequest(KO, response, Some("GatlingAsyncHandlerActor timed out"))
			executeNext(session.setFailed, response)
	}

	def resetTimeout = context.setReceiveTimeout(configuration.http.requestTimeOutInMs milliseconds)

	private def logRequest(
		requestStatus: RequestStatus,
		response: ExtendedResponse,
		errorMessage: Option[String] = None) {

		def dump = {
			val buff = new StringBuilder
			buff.append(END_OF_LINE).append(">>>>>>>>>>>>>>>>>>>>>>>>>>").append(END_OF_LINE)
			buff.append("Request:").append(END_OF_LINE).append(requestName).append(": ").append(requestStatus).append(" ").append(errorMessage.getOrElse("")).append(END_OF_LINE)
			buff.append("=========================").append(END_OF_LINE)
			buff.append("Session:").append(END_OF_LINE).append(session).append(END_OF_LINE)
			buff.append("=========================").append(END_OF_LINE)
			buff.append("HTTP request:").append(END_OF_LINE)
			request.dumpTo(buff)
			buff.append("=========================").append(END_OF_LINE)
			buff.append("HTTP response:").append(END_OF_LINE)
			response.dumpTo(buff)
			buff.append(END_OF_LINE).append("<<<<<<<<<<<<<<<<<<<<<<<<<")
			buff
		}

		if (requestStatus == KO) {
			warn("Request '" + requestName + "' failed : " + errorMessage.getOrElse(""))
			if (!isTraceEnabled) debug(dump)
		}
		trace(dump)

		DataWriter.logRequest(session.scenarioName, session.userId, requestName,
			response.executionStartDate, response.requestSendingEndDate, response.responseReceivingStartDate, response.executionEndDate,
			requestStatus, errorMessage, extractExtraInfo(response))
	}

	/**
	 * This method is used to send a message to the data writer actor and then execute the next action
	 *
	 * @param newSession the new Session
	 */
	private def executeNext(newSession: Session, response: ExtendedResponse) {
		next ! newSession.increaseTimeShift(nowMillis - response.executionEndDate)
		context.stop(self)
	}

	/**
	 * This method processes the response if needed for each checks given by the user
	 */
	private def processResponse(response: ExtendedResponse) {

		def handleFollowRedirect(sessionWithUpdatedCookies: Session) {

			logRequest(OK, response)

			val redirectURI = AsyncHttpProviderUtils.getRedirectUri(request.getURI, response.getHeader(HeaderNames.LOCATION))

			val originalRequest = request

			val requestBuilder = new RequestBuilder("GET", originalRequest.isUseRawUrl)
				.setURI(redirectURI)
				.setBodyEncoding(configuration.core.encoding)
				.setConnectionPoolKeyStrategy(originalRequest.getConnectionPoolKeyStrategy)
				.setInetAddress(originalRequest.getInetAddress)
				.setLocalInetAddress(originalRequest.getLocalAddress)
				.setVirtualHost(originalRequest.getVirtualHost)
				.setProxyServer(originalRequest.getProxyServer)

			for (headerName <- GatlingAsyncHandlerActor.propagatedOnRedirectHeaders) {
				Option(originalRequest.getHeaders.getFirstValue(headerName)).foreach(requestBuilder.addHeader(headerName, _))
			}

			for (cookie <- CookieHandling.getStoredCookies(sessionWithUpdatedCookies, redirectURI))
				requestBuilder.addCookie(cookie)

			val newRequest = requestBuilder.build

			val newRequestName = requestName match {
				case GatlingAsyncHandlerActor.REDIRECTED_REQUEST_NAME_PATTERN(requestBaseName, redirectCount) => requestBaseName + " Redirect " + (redirectCount.toInt + 1)
				case _ => requestName + " Redirect 1"
			}

			this.session = sessionWithUpdatedCookies
			this.requestName = newRequestName
			this.request = newRequest
			this.responseBuilder = responseBuilderFactory(newRequest, session)

			val client =
				if (protocolConfiguration.shareClient)
					GatlingHttpClient.defaultClient
				else
					sessionWithUpdatedCookies.getAttribute(GatlingHttpClient.httpClientAttributeName).asInstanceOf[AsyncHttpClient]

			client.executeRequest(newRequest, handlerFactory(newRequestName, self))
		}

		val sessionWithUpdatedCookies = CookieHandling.storeCookies(session, response.getUri, response.getCookies.toList)

		def checkAndProceed(session: Session, phases: List[HttpPhase]) {

			@tailrec
			def checkPhasesRec(session: Session, phases: List[HttpPhase]) {

				phases match {
					case Nil =>
						logRequest(OK, response)
						executeNext(session, response)

					case phase :: otherPhases =>
						val phaseChecks = checks.filter(_.phase == phase)
						var (newSession, checkResult) = applyChecks(session, response, phaseChecks)

						checkResult match {
							case Failure(errorMessage) =>
								logRequest(KO, response, Some(errorMessage))
								executeNext(newSession, response)

							case _ => checkPhasesRec(newSession, otherPhases)
						}
				}
			}

			val sessionWithUpdatedCache = CacheHandling.cache(protocolConfiguration, session, request, response)
			checkPhasesRec(sessionWithUpdatedCache, phases)
		}

		if (response.getStatusCode == 304)
			checkAndProceed(sessionWithUpdatedCookies, GatlingAsyncHandlerActor.NOT_MODIFIED_PHASES)
		else if (GatlingAsyncHandlerActor.REDIRECT_STATUS_CODES.contains(response.getStatusCode) && protocolConfiguration.followRedirectEnabled)
			handleFollowRedirect(sessionWithUpdatedCookies)
		else
			checkAndProceed(sessionWithUpdatedCookies, HttpPhase.phases)
	}

	/**
	 * Extract extra info from both request and response.
	 *
	 * @param response is the response to extract data from; request is retrieved from the property
	 * @return the extracted Strings
	 */
	private def extractExtraInfo(response: ExtendedResponse): List[String] = {

		def extractExtraSourceInfo[T](extractor: Option[T => List[String]], source: T): List[String] = {

			val extracted = try {
				extractor.map(_(source))

			} catch {
				case e: Exception =>
					warn("Encountered error while extracting extra request info", e)
					None
			}

			extracted.getOrElse(Nil)
		}

		val extraRequestInfo = extractExtraSourceInfo(protocolConfiguration.extraRequestInfoExtractor, request)
		val extraResponseInfo = extractExtraSourceInfo(protocolConfiguration.extraResponseInfoExtractor, response)
		extraRequestInfo ::: extraResponseInfo
	}
}