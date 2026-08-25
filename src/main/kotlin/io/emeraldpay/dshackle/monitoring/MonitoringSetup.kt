/**
 * Copyright (c) 2021 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.monitoring

import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.MonitoringConfig
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class MonitoringSetup(
    private val monitoringConfig: MonitoringConfig,
) {

    companion object {
        private val log = LoggerFactory.getLogger(MonitoringSetup::class.java)

        /**
         * Explicit latency buckets for every timer that asks for a histogram.
         *
         * `Timer.publishPercentileHistogram()` makes Micrometer emit its own exponential ladder, which
         * expands to 69 `le` buckets per label set (0.001s..30s). On a busy upstream that is 10 KB of
         * `/metrics` body per (chain, method, upstream) combination, and the two biggest timer families
         * alone accounted for 91% of a 74 MB response - past the 64 MB scrape limit of our Prometheus
         * agent, which drops the whole response and loses every dshackle metric with it.
         *
         * These 12 boundaries keep p50/p90/p95/p99 usable across the range we actually serve (sub-ms
         * cache hits up to the 30s timeout) at 13 buckets including `+Inf` - a 5.3x cut.
         */
        private val LATENCY_BUCKETS = listOf(
            Duration.ofMillis(1),
            Duration.ofMicros(2500),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            Duration.ofMillis(25),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
        )

        /**
         * Micrometer stores timer values in nanoseconds, so service level objectives set through
         * [DistributionStatisticConfig] must be nanoseconds too. This is the same conversion
         * `Timer.Builder.serviceLevelObjectives(Duration...)` does internally.
         */
        private val LATENCY_BUCKETS_NANOS =
            LATENCY_BUCKETS.map { it.toNanos().toDouble() }.toDoubleArray()

        /**
         * Replaces Micrometer's 69-bucket percentile histogram with [LATENCY_BUCKETS].
         *
         * Only touches timers that already asked for a percentile histogram, so a timer without one
         * never gains buckets, and non-timer distributions (whose recorded values are not nanoseconds)
         * are left alone. Values set on the builder win over the incoming config; the rest is inherited.
         */
        @JvmStatic
        fun latencyHistogramFilter(): MeterFilter = object : MeterFilter {
            override fun configure(
                id: Meter.Id,
                config: DistributionStatisticConfig,
            ): DistributionStatisticConfig {
                if (id.type != Meter.Type.TIMER || config.isPercentileHistogram != true) {
                    return config
                }
                return DistributionStatisticConfig.builder()
                    .percentilesHistogram(false)
                    .serviceLevelObjectives(*LATENCY_BUCKETS_NANOS)
                    .build()
                    .merge(config)
            }
        }
    }

    @PostConstruct
    fun setup() {
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        Metrics.globalRegistry.add(prometheusRegistry)
        Metrics.globalRegistry.config().meterFilter(
            object : MeterFilter {
                override fun map(id: Meter.Id): Meter.Id {
                    if (id.name.startsWith("jvm") || id.name.startsWith("process") || id.name.startsWith("system")) {
                        return id
                    } else {
                        return id.withName("dshackle." + id.name)
                    }
                }
            },
        )
        Metrics.globalRegistry.config().meterFilter(latencyHistogramFilter())

        if (monitoringConfig.enableJvm) {
            ClassLoaderMetrics().bindTo(Metrics.globalRegistry)
            JvmMemoryMetrics().bindTo(Metrics.globalRegistry)
            JvmGcMetrics().bindTo(Metrics.globalRegistry)
            ProcessorMetrics().bindTo(Metrics.globalRegistry)
            JvmThreadMetrics().bindTo(Metrics.globalRegistry)
        }
        if (monitoringConfig.enableExtended) {
            Global.metricsExtended = true
        }

        if (monitoringConfig.prometheus.enabled) {
            HTTPServer
                .builder()
                .hostname(monitoringConfig.prometheus.host)
                .port(monitoringConfig.prometheus.port)
                .registry(prometheusRegistry.prometheusRegistry)
                .metricsHandlerPath(monitoringConfig.prometheus.path)
                .buildAndStart()
        }
    }
}
