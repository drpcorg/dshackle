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
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class MonitoringSetup(
    private val monitoringConfig: MonitoringConfig,
) {

    companion object {
        private val log = LoggerFactory.getLogger(MonitoringSetup::class.java)
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
