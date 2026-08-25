/**
 * Copyright (c) 2026 EmeraldPay, Inc
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

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import spock.lang.Specification

import java.time.Duration
import java.util.regex.Pattern

class MonitoringSetupSpec extends Specification {

    static final Pattern LE = Pattern.compile(/\ble="([^"]+)"/)

    private static PrometheusMeterRegistry registry() {
        def registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registry.config().meterFilter(MonitoringSetup.latencyHistogramFilter())
        return registry
    }

    /**
     * Bucket upper bounds of a metric, as doubles. Prometheus writes the last bucket as "+Inf",
     * which Double.parseDouble does not accept, so it is mapped explicitly.
     */
    private static List<Double> bounds(PrometheusMeterRegistry registry, String metric) {
        def out = []
        registry.scrape().eachLine { line ->
            if (line.startsWith(metric + "_bucket")) {
                def m = LE.matcher(line)
                if (m.find()) {
                    def raw = m.group(1)
                    out.add(raw.endsWith("Inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(raw))
                }
            }
        }
        return out
    }

    def "caps a percentile histogram at 13 buckets instead of Micrometer's 69"() {
        setup:
        def registry = registry()

        when:
        Timer.builder("upstream.rpc.conn")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(3))

        then:
        // 12 explicit boundaries plus +Inf. The default percentile ladder would be 69.
        bounds(registry, "upstream_rpc_conn_seconds").size() == 13
    }

    def "keeps the boundaries in seconds, not nanoseconds"() {
        setup:
        def registry = registry()

        when:
        Timer.builder("upstream.rpc.conn")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(3))
        def all = bounds(registry, "upstream_rpc_conn_seconds")
        def finite = all.findAll { !it.isInfinite() }.sort()

        then:
        // Micrometer wants nanoseconds in DistributionStatisticConfig but exposes seconds. Getting
        // that conversion wrong gives sub-nanosecond boundaries, which this pins down.
        all.any { it.isInfinite() }
        Math.abs(finite.first() - 0.001d) < 1e-9d
        Math.abs(finite.last() - 30.0d) < 1e-6d
    }

    def "leaves a timer without a percentile histogram alone"() {
        setup:
        def registry = registry()

        when:
        Timer.builder("request.jsonrpc.call")
                .register(registry)
                .record(Duration.ofMillis(3))

        then:
        bounds(registry, "request_jsonrpc_call_seconds").isEmpty()
    }

    def "leaves a non-timer distribution alone"() {
        setup:
        def registry = registry()

        when:
        DistributionSummary.builder("upstreams.tried")
                .publishPercentileHistogram()
                .register(registry)
                .record(3.0d)
        def finite = bounds(registry, "upstreams_tried").findAll { !it.isInfinite() }

        then:
        // A summary counts upstreams, not nanoseconds, so the latency ladder must not be applied.
        // 2.5ms is the boundary unique to that ladder.
        !finite.any { Math.abs(it - 0.0025d) < 1e-9d }
    }
}
