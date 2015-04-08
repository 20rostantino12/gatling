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
package io.gatling.metrics.types

import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.result.message.{ KO, OK, Status }
import com.tdunning.math.stats.{ AVLTreeDigest, TDigest }

private[metrics] trait RequestMetricsBuffer {
  def add(status: Status, time: Long, statusCode: Option[String]): Unit
  def clear(): Unit
  def metricsByStatus: MetricByStatus
}

private[metrics] class TDigestRequestMetricsBuffer(configuration: GatlingConfiguration) extends RequestMetricsBuffer {

  private val percentile1 = configuration.charting.indicators.percentile1 / 100.0
  private val percentile2 = configuration.charting.indicators.percentile2 / 100.0
  private val percentile3 = configuration.charting.indicators.percentile3 / 100.0
  private val percentile4 = configuration.charting.indicators.percentile4 / 100.0

  private var okDigest: TDigest = _
  private var koDigest: TDigest = _
  private var allDigest: TDigest = _

  private var statusCodes: Map[Option[String], Int] = _

  clear()

  override def add(status: Status, time: Long, statusCode: Option[String]): Unit = {
    val responseTime = time.max(0L)

    allDigest.add(responseTime)
    status match {
      case OK => okDigest.add(responseTime)
      case KO => koDigest.add(responseTime)
    }

    statusCodes = statusCodes + (statusCode -> (statusCodes(statusCode) + 1))
  }

  override def clear(): Unit = {
    okDigest = new AVLTreeDigest(100.0)
    koDigest = new AVLTreeDigest(100.0)
    allDigest = new AVLTreeDigest(100.0)
    statusCodes = Map.empty[Option[String], Int].withDefault(_ => 0)
  }

  override def metricsByStatus: MetricByStatus =
    MetricByStatus(metricsOfDigest(okDigest), metricsOfDigest(koDigest), metricsOfDigest(allDigest), statusCodes)

  private def metricsOfDigest(digest: TDigest): Option[Metrics] = {
    val count = digest.size
    if (count > 0) {
      val percentile1Value = digest.quantile(percentile1).toInt
      val percentile2Value = digest.quantile(percentile2).toInt
      val percentile3Value = digest.quantile(percentile3).toInt
      val percentile4Value = digest.quantile(percentile4).toInt
      Some(Metrics(count, digest.quantile(0).toInt, digest.quantile(1).toInt, percentile1Value, percentile2Value, percentile3Value, percentile4Value))
    } else
      None
  }
}

private[metrics] case class MetricByStatus(ok: Option[Metrics], ko: Option[Metrics], all: Option[Metrics], statusCodes: Map[Option[String], Int])
private[metrics] case class Metrics(count: Long, min: Int, max: Int, percentile1: Int, percentile2: Int, percentile3: Int, percentile4: Int)
