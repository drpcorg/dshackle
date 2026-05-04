package io.emeraldpay.dshackle.upstream.aztec

import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.foundation.ChainOptions.Options
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.GenericSingleCallValidator
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.UpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

object AztecChainSpecific : AbstractPollChainSpecific() {
    private val log = LoggerFactory.getLogger(AztecChainSpecific::class.java)

    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val root = Global.objectMapper.readTree(data)
        val height = parseLong(findNode(root, "proposed.number")) ?: 0L
        val hashValue = parseText(findNode(root, "proposed.hash"))

        return Mono.just(
            BlockContainer(
                height = height,
                hash = BlockId.from(hashValue ?: "0x0"),
                difficulty = BigInteger.ZERO,
                timestamp = Instant.EPOCH,
                full = false,
                json = data,
                parsed = root,
                transactions = emptyList(),
                upstreamId = upstreamId,
                parentHash = null,
            ),
        )
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        throw NotImplementedError()
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        throw NotImplementedError()
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
        throw NotImplementedError()
    }

    override fun upstreamValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<UpstreamAvailability>> {
        return listOf(
            GenericSingleCallValidator(
                ChainRequest("node_isReady", ListParams()),
                upstream,
            ) { data ->
                val raw = Global.objectMapper.readTree(data)
                val ready = when {
                    raw.isBoolean -> raw.asBoolean()
                    raw.isTextual -> raw.asText().equals("true", ignoreCase = true)
                    else -> raw.asBoolean(false)
                }
                if (ready) {
                    UpstreamAvailability.OK
                } else {
                    log.warn("Aztec node {} reports not ready", upstream.getId())
                    UpstreamAvailability.SYNCING
                }
            },
            GenericSingleCallValidator(
                ChainRequest("node_getL2Tips", ListParams()),
                upstream,
            ) { data ->
                validateTips(data, config.laggingLagSize, upstream.getId())
            },
        )
    }

    override fun upstreamSettingsValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<ValidateUpstreamSettingsResult>> {
        return emptyList()
    }

    override fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService {
        return AztecLowerBoundService(chain, upstream)
    }

    override fun latestBlockRequest(): ChainRequest =
        ChainRequest("node_getL2Tips", ListParams())

    override fun upstreamSettingsDetector(
        chain: Chain,
        upstream: Upstream,
    ): UpstreamSettingsDetector {
        return AztecUpstreamSettingsDetector(upstream)
    }

    fun validateTips(data: ByteArray, lagging: Int, upstreamId: String): UpstreamAvailability {
        val raw = Global.objectMapper.readTree(data)
        if (raw.isNull || raw.isMissingNode) {
            log.warn("Aztec node {} returned empty L2 tips", upstreamId)
            return UpstreamAvailability.SYNCING
        }
        val proposed = parseLong(findNode(raw, "proposed.number")) ?: 0L
        val proven = parseLong(findNode(raw, "proven.number")) ?: 0L
        if (proposed <= 0) {
            log.warn("Aztec node {} has empty proposed tip: {}", upstreamId, raw)
            return UpstreamAvailability.SYNCING
        }
        // proven trails proposed by definition; sanity-check it isn't way ahead (would mean stale upstream returning fixed data)
        if (proven > proposed) {
            log.warn("Aztec node {} returned inconsistent tips: proposed={} proven={}", upstreamId, proposed, proven)
            return UpstreamAvailability.SYNCING
        }
        if (lagging > 0 && proposed - proven > lagging.toLong() * 10) {
            // proposed-proven gap grows during normal operation, but a gap > lagging*10 indicates the prover is
            // significantly stuck on this node; mark as SYNCING so head-skewed traffic prefers a healthier peer.
            log.warn(
                "Aztec node {} prover lag is excessive: proposed={} proven={} (gap={}, threshold={})",
                upstreamId,
                proposed,
                proven,
                proposed - proven,
                lagging * 10,
            )
            return UpstreamAvailability.SYNCING
        }
        return UpstreamAvailability.OK
    }

    private fun findNode(root: JsonNode, vararg paths: String): JsonNode? {
        for (path in paths) {
            var current: JsonNode? = root
            for (part in path.split(".")) {
                current = current?.get(part)
                if (current == null || current.isMissingNode) {
                    break
                }
            }
            if (current != null && !current.isMissingNode && !current.isNull) {
                return current
            }
        }
        return null
    }

    private fun parseText(node: JsonNode?): String? {
        if (node == null || node.isNull || node.isMissingNode) {
            return null
        }
        return node.asText().ifBlank { null }
    }

    private fun parseLong(node: JsonNode?): Long? {
        if (node == null || node.isNull || node.isMissingNode) {
            return null
        }
        return when {
            node.isNumber -> node.asLong()
            node.isTextual -> parseNumericString(node.asText())
            else -> null
        }
    }

    private fun parseNumericString(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val isHex = trimmed.startsWith("0x") || trimmed.startsWith("0X")
        val raw = if (isHex) trimmed.substring(2) else trimmed
        return runCatching { BigInteger(raw, if (isHex) 16 else 10).toLong() }.getOrNull()
    }
}
