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
package io.gatling.core.body

import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.config.Resource
import io.gatling.core.session._
import io.gatling.core.session.el.{ El, ElCompiler }
import io.gatling.core.util.Io._
import io.gatling.core.util.cache._
import io.gatling.core.validation._

object ElFileBodies {

  val ElFileBodyStringCache = ThreadSafeCache[String, Validation[Expression[String]]](configuration.core.elFileBodiesCacheMaxCapacity)

  def asString(filePath: Expression[String]): Expression[String] = {

      def compileFile(path: String): Validation[Expression[String]] =
        Resource.body(path)
          .map(resource => withCloseable(resource.inputStream) {
            _.toString(configuration.core.charset)
          }).map(_.el[String])

      def pathToExpression(path: String) =
        if (ElFileBodyStringCache.enabled)
          ElFileBodyStringCache.getOrElsePutIfAbsent(path, compileFile(path))
        else
          compileFile(path)

    session =>
      for {
        path <- filePath(session)
        expression <- pathToExpression(path)
        body <- expression(session)
      } yield body
  }

  val ElFileBodyBytesCache = ThreadSafeCache[String, Validation[Expression[Seq[Array[Byte]]]]](configuration.core.elFileBodiesCacheMaxCapacity)

  def asBytesSeq(filePath: Expression[String]): Expression[Seq[Array[Byte]]] = {

      def resource2BytesSeq(path: String): Validation[Expression[Seq[Array[Byte]]]] = Resource.body(path).map { resource =>
        ElCompiler.compile2BytesSeq(resource.string(configuration.core.charset))
      }

      def pathToExpression(path: String) =
        if (ElFileBodyBytesCache.enabled) ElFileBodyBytesCache.getOrElsePutIfAbsent(path, resource2BytesSeq(path))
        else resource2BytesSeq(path)

    session =>
      for {
        path <- filePath(session)
        expression <- pathToExpression(path)
        body <- expression(session)
      } yield body
  }
}