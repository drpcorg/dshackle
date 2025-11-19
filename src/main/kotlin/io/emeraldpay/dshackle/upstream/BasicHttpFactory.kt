package io.emeraldpay.dshackle.upstream

import io.emeraldpay.dshackle.ApiType
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.config.AuthConfig
import io.emeraldpay.dshackle.upstream.restclient.RestHttpReader
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcHttpReader
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory

class BasicHttpFactory(
    private val url: String,
    private val maxConnections: Int,
    private val queueSize: Int,
    private val basicAuth: AuthConfig.ClientBasicAuth?,
    private val tls: ByteArray?,
    private val nettyMetricsEnabled: Boolean,
) : HttpFactory {
    private val log = LoggerFactory.getLogger(this::class.java)

    override fun create(id: String?, chain: Chain): HttpReader {
        log.info("Creating http pool for {} with maxConnections {} and queueSize {}", url, maxConnections, queueSize)

        val metricsTags = listOf(
            // "unknown" is not supposed to happen
            Tag.of("upstream", id ?: "unknown"),
            // UNSPECIFIED shouldn't happen too
            Tag.of("chain", chain.chainCode),
        )
        val metrics = RequestMetrics(
            Timer.builder("upstream.rpc.conn")
                .description("Request time through a HTTP JSON RPC connection")
                .tags(metricsTags)
                .publishPercentileHistogram()
                .register(Metrics.globalRegistry),
            Counter.builder("upstream.rpc.fail")
                .description("Number of failures of HTTP JSON RPC requests")
                .tags(metricsTags)
                .register(Metrics.globalRegistry),
            nettyMetricsEnabled,
        )

        if (chain.type.apiType == ApiType.REST) {
            return RestHttpReader(url, maxConnections, queueSize, metrics, basicAuth, tls)
        }
        return JsonRpcHttpReader(url, maxConnections, queueSize, metrics, basicAuth, tls)
    }
}
