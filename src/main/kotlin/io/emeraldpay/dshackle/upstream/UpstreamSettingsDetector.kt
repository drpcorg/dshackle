package io.emeraldpay.dshackle.upstream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Defaults.Companion.internalCallsTimeout
import io.emeraldpay.dshackle.Global
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

const val UNKNOWN_CLIENT_VERSION = "unknown"
const val DEFAULT_CLIENT_TYPE = "default client"

typealias UpstreamSettingsDetectorBuilder = (Chain, Upstream) -> UpstreamSettingsDetector?

abstract class UpstreamSettingsDetector(
    private val upstream: Upstream,
) {
    protected val log = LoggerFactory.getLogger(this::class.java)

    fun detectLabels(): Flux<Pair<String, String>> {
        return internalDetectLabels()
            .timeout(internalCallsTimeout)
            .onErrorResume {
                log.warn("Couldn't detect lables of upstream {}, message - {}", upstream.getId(), it.message)
                Flux.empty()
            }
    }

    protected abstract fun internalDetectLabels(): Flux<Pair<String, String>>

    fun detectClientVersion(): Mono<String> {
        return upstream.getIngressReader()
            .read(clientVersionRequest())
            .flatMap(ChainResponse::requireResult)
            .map(::parseClientVersion)
            .timeout(internalCallsTimeout)
            .onErrorResume {
                log.warn("Can't detect the client version of upstream {}, reason - {}", upstream.getId(), it.message)
                Mono.just(UNKNOWN_CLIENT_VERSION)
            }
    }

    protected abstract fun clientVersionRequest(): ChainRequest

    protected abstract fun parseClientVersion(data: ByteArray): String
}

abstract class BasicUpstreamSettingsDetector(
    private val upstream: Upstream,
) : UpstreamSettingsDetector(upstream) {
    protected abstract fun nodeTypeRequest(): NodeTypeRequest
    protected abstract fun clientVersion(node: JsonNode): String?
    protected abstract fun clientType(node: JsonNode): String?

    protected fun detectNodeType(): Flux<Pair<String, String>?> {
        val nodeTypeRequest = nodeTypeRequest()
        return upstream
            .getIngressReader()
            .read(nodeTypeRequest.request)
            .flatMap(ChainResponse::requireResult)
            .map { Global.objectMapper.readValue<JsonNode>(it) }
            .flatMapMany { node ->
                val labels = mutableListOf<Pair<String, String>>()
                clientType(node)?.let {
                    labels.add("client_type" to it)
                }
                clientVersion(node)?.let {
                    labels.add("client_version" to it)
                }

                Flux.fromIterable(labels)
            }
            .onErrorResume { error ->
                log.warn("Can't detect the node type of upstream ${upstream.getId()}, reason - {}", error.message)
                Flux.empty()
            }
    }
}

abstract class BasicEthUpstreamSettingsDetector(
    val upstream: Upstream,
) : BasicUpstreamSettingsDetector(upstream) {
    abstract fun mapping(node: JsonNode): String

    override fun clientVersion(node: JsonNode): String? {
        val client = mapping(node)

        val firstSlash = client.indexOf("/")
        if (firstSlash != -1) {
            // Standard format with slashes: "geth/1.9.0/linux/go1.15" or "Type/Version"
            val secondSlash = client.indexOf("/", firstSlash + 1)
            if (secondSlash != -1) {
                // Full standard format: return version part between first and second slash
                return client.substring(firstSlash + 1, secondSlash)
            } else {
                // "Type/Version" format: return version part after slash
                val version = client.substring(firstSlash + 1)
                return if (version.isEmpty()) {
                    log.warn("Could not determine client version for upstream ${upstream.getId()}, empty version after slash in: '{}'", client)
                    UNKNOWN_CLIENT_VERSION
                } else {
                    version
                }
            }
        }

        // Check if it's semver format without client type
        if (isSemverLike(client)) {
            return client
        }

        // String without slashes and dots - return unknown version
        if (!client.contains(".")) {
            log.warn("Could not determine client version for upstream ${upstream.getId()}, single word without version info: '{}'", client)
            return UNKNOWN_CLIENT_VERSION
        }

        // Fallback: return original as version
        log.warn("Could not determine client version for upstream ${upstream.getId()}, raw version: '{}'", client)
        return client
    }

    override fun clientType(node: JsonNode): String? {
        val client = mapping(node)

        if (client.isEmpty()) {
            return DEFAULT_CLIENT_TYPE
        }

        val firstSlash = client.indexOf("/")
        if (firstSlash != -1) {
            // Has slash - return part before first slash as client type
            val clientPart = client.substring(0, firstSlash)
            return if (clientPart.isEmpty()) DEFAULT_CLIENT_TYPE else clientPart.lowercase()
        }

        // Check if it's semver format without client type
        if (isSemverLike(client)) {
            return DEFAULT_CLIENT_TYPE
        }

        // String without slashes and dots - use whole string as client type
        if (!client.contains(".")) {
            return client.lowercase()
        }

        // Fallback for unrecognized formats
        return DEFAULT_CLIENT_TYPE
    }

    private fun isSemverLike(version: String): Boolean {
        return version.matches(Regex("^v?\\d+\\.\\d+\\.\\d+.*"))
    }
}

data class NodeTypeRequest(
    val request: ChainRequest,
)
