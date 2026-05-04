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

    // node_getL2Tips reshaped between Aztec versions:
    //   v3 (and earlier): {proposed: {number, hash}, proven: {number, hash}, checkpointed: {number, hash}}
    //   v4: proven/finalized/checkpointed each became {block: {number, hash}, checkpoint: {number, hash}}
    // proposed stayed flat. We always look at the v4 nested path first and fall back
    // to the flat v3 path so an upstream on either version is parsed correctly.
    private val PROPOSED_NUMBER = arrayOf("proposed.number", "proposed.block.number")
    private val PROPOSED_HASH = arrayOf("proposed.hash", "proposed.block.hash")

    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val root = Global.objectMapper.readTree(data)
        val height = parseLong(findNode(root, *PROPOSED_NUMBER)) ?: 0L
        val hashValue = parseText(findNode(root, *PROPOSED_HASH))

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

    // Aztec is HTTP-poll only; getFromHeader / listenNewHeadsRequest /
    // unsubscribeNewHeadsRequest are reachable only from GenericWsHead, which is
    // never wired for a polling chain. Fail fast so a misconfigured WS connector
    // surfaces immediately instead of silently producing height=0 blocks from a
    // header-shaped event being parsed as the L2Tips response.
    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        throw UnsupportedOperationException("Aztec does not support websocket subscriptions")
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        throw UnsupportedOperationException("Aztec does not support websocket subscriptions")
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
        throw UnsupportedOperationException("Aztec does not support websocket subscriptions")
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
        )
    }

    override fun upstreamSettingsValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<ValidateUpstreamSettingsResult>> {
        if (chain.chainId.isBlank()) {
            return emptyList()
        }
        return listOf(
            GenericSingleCallValidator(
                ChainRequest("node_getChainId", ListParams()),
                upstream,
            ) { data ->
                validateChainId(data, chain, upstream.getId())
            },
        )
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

    fun validateChainId(data: ByteArray, chain: Chain, upstreamId: String): ValidateUpstreamSettingsResult {
        if (data.isEmpty() || String(data).isBlank()) {
            log.warn("Aztec node {} returned empty chain id response", upstreamId)
            return ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR
        }
        val raw = try {
            Global.objectMapper.readTree(data)
        } catch (e: Exception) {
            log.warn("Aztec node {} returned unparseable chain id payload: {}", upstreamId, e.message)
            return ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR
        }
        if (raw == null || raw.isNull) {
            log.warn("Aztec node {} returned null chain id", upstreamId)
            return ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR
        }
        val reported = parseChainId(raw)
        if (reported.isNullOrBlank()) {
            log.warn("Aztec node {} returned no chain id ({})", upstreamId, raw)
            return ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR
        }
        val expected = chain.chainId
        return if (chainIdMatches(reported, expected)) {
            ValidateUpstreamSettingsResult.UPSTREAM_VALID
        } else {
            log.warn(
                "Aztec node {} chain id mismatch: reported={} expected={}",
                upstreamId,
                reported,
                expected,
            )
            ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
        }
    }

    private fun parseChainId(node: JsonNode): String? {
        return when {
            node.isNumber -> node.asLong().toString()
            node.isTextual -> node.asText().trim().ifBlank { null }
            else -> null
        }
    }

    fun chainIdMatches(reported: String, expected: String): Boolean {
        val normalize: (String) -> String = { value ->
            val trimmed = value.trim().lowercase()
            val withoutPrefix = if (trimmed.startsWith("0x")) trimmed.substring(2) else trimmed
            // Aztec returns chain id as a decimal number; configured chainId may be hex.
            // Compare numerically when both sides parse, fall back to literal match.
            withoutPrefix.trimStart('0').ifEmpty { "0" }
        }
        val a = normalize(reported)
        val b = normalize(expected)
        if (a == b) return true
        val aNum = runCatching { BigInteger(a, if (reported.lowercase().startsWith("0x")) 16 else 10) }.getOrNull()
        val bNum = runCatching { BigInteger(b, if (expected.lowercase().startsWith("0x")) 16 else 10) }.getOrNull()
        return aNum != null && bNum != null && aNum == bNum
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
