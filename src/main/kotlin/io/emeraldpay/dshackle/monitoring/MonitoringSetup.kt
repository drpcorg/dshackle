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

import com.sun.net.httpserver.HttpServer
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
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import javax.annotation.PostConstruct

@Service
class MonitoringSetup(
    @Autowired private val monitoringConfig: MonitoringConfig,
) {

    companion object {
        private val log = LoggerFactory.getLogger(MonitoringSetup::class.java)
    }

    private fun isTcpPortAvailable(host: String, port: Int): Boolean {
        try {
            ServerSocket().use { serverSocket ->
                // setReuseAddress(false) is required only on macOS,
                // otherwise the code will not work correctly on that platform
                serverSocket.reuseAddress = false
                serverSocket.bind(InetSocketAddress(InetAddress.getByName(host), port), 1)
                return true
            }
        } catch (ex: java.lang.Exception) {
            return false
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
            // use standard JVM server with a single thread blocking processing
            // prometheus is a single thread periodic call, no reason to setup anything complex
            Thread {
                var started = false
                val scrapeThreadCounter = AtomicInteger(0)
                val scrapeExecutor = Executors.newFixedThreadPool(
                    4,
                    ThreadFactory { runnable ->
                        Thread(runnable, "prometheus-scrape-${scrapeThreadCounter.incrementAndGet()}").apply {
                            isDaemon = true
                        }
                    },
                )
                while (true) {
                    if (isTcpPortAvailable(monitoringConfig.prometheus.host, monitoringConfig.prometheus.port)) {
                        started = true
                        try {
                            log.info("Run Prometheus metrics on ${monitoringConfig.prometheus.host}:${monitoringConfig.prometheus.port}${monitoringConfig.prometheus.path}")
                            val server = HttpServer.create(
                                InetSocketAddress(
                                    monitoringConfig.prometheus.host,
                                    monitoringConfig.prometheus.port,
                                ),
                                0,
                            )
                            server.executor = scrapeExecutor
                            server.createContext(monitoringConfig.prometheus.path) { httpExchange ->
                                val startTime = System.nanoTime()
                                try {
                                    val response = prometheusRegistry.scrape()
                                    val responseBytes = response.toByteArray(StandardCharsets.UTF_8)
                                    httpExchange.responseHeaders.add("Content-Encoding", "gzip")
                                    httpExchange.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                                    httpExchange.sendResponseHeaders(200, 0)
                                    httpExchange.responseBody.use { os ->
                                        GZIPOutputStream(os).use { gzos ->
                                            gzos.write(responseBytes)
                                        }
                                    }
                                    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)
                                    if (durationMs > 5000) {
                                        log.warn("Prometheus scrape served in {} ms", durationMs)
                                    } else {
                                        log.debug("Prometheus scrape served in {} ms", durationMs)
                                    }
                                } catch (e: Exception) {
                                    log.error("Failed to serve Prometheus metrics", e)
                                    val messageBytes = "internal error".toByteArray(StandardCharsets.UTF_8)
                                    runCatching {
                                        httpExchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                                        httpExchange.sendResponseHeaders(500, messageBytes.size.toLong())
                                        httpExchange.responseBody.use { os ->
                                            os.write(messageBytes)
                                        }
                                    }
                                } finally {
                                    httpExchange.close()
                                }
                            }
                            Thread(server::start).start()
                        } catch (e: IOException) {
                            log.error("Failed to start Prometheus Server", e)
                        }
                    } else {
                        if (!started) {
                            log.error("Can't start prometheus metrics on ${monitoringConfig.prometheus.host}:${monitoringConfig.prometheus.port}${monitoringConfig.prometheus.path}")
                        }
                        Thread.sleep(1000)
                    }
                }
            }.start()
        }
    }
}
