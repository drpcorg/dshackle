package io.emeraldpay.dshackle.config.reload

import com.sun.net.httpserver.HttpServer
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.Global.Companion.chainById
import io.emeraldpay.dshackle.config.ReloadConfiguration
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.foundation.ChainOptions
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.stream.Collectors
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Component
class ReloadConfigSetup(
    private val reloadConfigService: ReloadConfigService,
    private val reloadConfigUpstreamService: ReloadConfigUpstreamService,
    private val reloadConfiguration: ReloadConfiguration,
) {
    companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
    }

    private lateinit var server: HttpServer

    @PostConstruct
    fun start() {
        server = HttpServer.create(
            InetSocketAddress("0.0.0.0", reloadConfiguration.port),
            0,
        )

        server.createContext("/reload") { httpExchange ->
            if (httpExchange.requestMethod == "POST") {
                try {
                    log.info("Reloading config...")
                    reloadConfig()
                } catch (e: Exception) {
                    val response = Global.objectMapper.writeValueAsBytes(
                        ErrorResponse(e.message),
                    )
                    httpExchange.responseHeaders.add("Content-Type", "application/json")
                    httpExchange.sendResponseHeaders(500, response.size.toLong())
                    httpExchange.responseBody.use { it.write(response) }
                    return@createContext
                }

                httpExchange.sendResponseHeaders(202, 0)
                httpExchange.responseBody.close()
                log.info("Config reload is done")
            } else {
                val response = Global.objectMapper.writeValueAsBytes(
                    ErrorResponse("${httpExchange.requestMethod} is not supported"),
                )
                httpExchange.responseHeaders.add("Content-Type", "application/json")
                httpExchange.sendResponseHeaders(404, response.size.toLong())
                httpExchange.responseBody.use { it.write(response) }
            }
        }

        server.start()
    }

    @PreDestroy
    fun stop() {
        if (::server.isInitialized) {
            server.stop(0)
        }
    }

    private fun reloadConfig() {
        val newUpstreamsConfig = reloadConfigService.readUpstreamsConfig()
        val currentUpstreamsConfig = reloadConfigService.currentUpstreamsConfig()

        val chainsToReload = analyzeDefaultOptions(
            currentUpstreamsConfig.defaultOptions,
            newUpstreamsConfig.defaultOptions,
        )
        val upstreamsAnalyzeData = analyzeUpstreams(
            currentUpstreamsConfig.upstreams,
            newUpstreamsConfig.upstreams,
        )

        val upstreamsToRemove = upstreamsAnalyzeData.removed
            .plus(upstreamsAnalyzeData.reloaded)
            .filterNot { chainsToReload.contains(it.second) }
        val upstreamsToAdd = upstreamsAnalyzeData.added
            .plus(upstreamsAnalyzeData.reloaded.map { it.first })

        reloadConfigUpstreamService.reloadUpstreams(chainsToReload, upstreamsToRemove, upstreamsToAdd, newUpstreamsConfig)

        reloadConfigService.updateUpstreamsConfig(newUpstreamsConfig)
    }

    private fun analyzeUpstreams(
        currentUpstreams: List<UpstreamsConfig.Upstream<*>>,
        newUpstreams: List<UpstreamsConfig.Upstream<*>>,
    ): UpstreamAnalyzeData {
        if (currentUpstreams == newUpstreams) {
            return UpstreamAnalyzeData()
        }
        val reloaded = mutableSetOf<Pair<String, Chain>>()
        val removed = mutableSetOf<Pair<String, Chain>>()
        val currentUpstreamsMap = currentUpstreams.associateBy { it.id!! to chainById(it.chain) }
        val newUpstreamsMap = newUpstreams.associateBy { it.id!! to chainById(it.chain) }

        currentUpstreamsMap.forEach {
            val newUpstream = newUpstreamsMap[it.key]
            if (newUpstream == null) {
                removed.add(it.key)
            } else if (newUpstream != it.value) {
                reloaded.add(it.key)
            }
        }

        val added = newUpstreamsMap.minus(currentUpstreamsMap.keys).mapTo(mutableSetOf()) { it.key.first }

        return UpstreamAnalyzeData(added, removed, reloaded)
    }

    private fun analyzeDefaultOptions(
        currentDefaultOptions: List<ChainOptions.DefaultOptions>,
        newDefaultOptions: List<ChainOptions.DefaultOptions>,
    ): Set<Chain> {
        val chainsToReload = mutableSetOf<Chain>()

        val currentOptions = getChainOptions(currentDefaultOptions)
        val newOptions = getChainOptions(newDefaultOptions)

        if (currentOptions == newOptions) {
            return emptySet()
        }

        val removed = mutableSetOf<Chain>()

        currentOptions.forEach {
            val newChainOption = newOptions[it.key]
            if (newChainOption == null) {
                removed.add(chainById(it.key))
            } else if (newChainOption != it.value) {
                chainsToReload.add(chainById(it.key))
            }
        }

        val added = newOptions.minus(currentOptions.keys).map { chainById(it.key) }

        return chainsToReload.plus(added).plus(removed)
    }

    private fun getChainOptions(
        defaultOptions: List<ChainOptions.DefaultOptions>,
    ): Map<String, List<ChainOptions.PartialOptions>> {
        return defaultOptions.stream()
            .flatMap { options -> options.chains?.stream()?.map { it to options.options } }
            .collect(
                Collectors.groupingBy(
                    { it.first },
                    Collectors.mapping(
                        { it.second },
                        Collectors.toUnmodifiableList(),
                    ),
                ),
            )
    }

    private data class ErrorResponse(
        val error: String?,
    )

    private data class UpstreamAnalyzeData(
        val added: Set<String> = emptySet(),
        val removed: Set<Pair<String, Chain>> = emptySet(),
        val reloaded: Set<Pair<String, Chain>> = emptySet(),
    )
}
