package io.emeraldpay.dshackle.upstream.aztec

import com.fasterxml.jackson.databind.JsonNode
import com.github.benmanes.caffeine.cache.Caffeine
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.foundation.ChainOptions.Options
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainException
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
import java.time.Duration
import java.time.Instant

object AztecChainSpecific : AbstractPollChainSpecific() {
    private val log = LoggerFactory.getLogger(AztecChainSpecific::class.java)

    private const val METHOD_NOT_FOUND = -32601

    // Aztec v5 (v5.0.0-rc.1) renamed the tips RPC: node_getL2Tips -> node_getChainTips.
    // Older nodes (incl. current mainnet) expose only the legacy name; v5+ nodes expose
    // only the new one. We probe the legacy method first (most upstreams are still on it)
    // and fall back to the new one on "method not found", remembering the working method
    // per upstream so we stop probing the dead one on every poll.
    private val LEGACY_TIPS_REQUEST = ChainRequest("node_getL2Tips", ListParams())
    private val CHAIN_TIPS_REQUEST = ChainRequest("node_getChainTips", ListParams())

    // Bounded so per-upstream entries can't accumulate without limit (e.g. across config
    // reloads): a hard size cap plus idle expiry evict stale ids, and an evicted entry just
    // costs one re-probe. Only upstreams that actually fall back take a slot — legacy-only
    // ones keep using the default and never populate it.
    private val workingTipsRequest = Caffeine.newBuilder()
        .maximumSize(1024)
        .expireAfterAccess(Duration.ofHours(1))
        .build<String, ChainRequest>()

    // The tips response reshaped between Aztec versions:
    //   v3 (and earlier): {proposed: {number, hash}, proven: {number, hash}, checkpointed: {number, hash}}
    //   v4/v5: proven/finalized/checkpointed each became {block: {number, hash}, checkpoint: {number, hash}}
    // proposed stayed flat across all versions. We look at the flat path first and fall back
    // to the nested path so an upstream on any version is parsed correctly.
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

    override fun latestBlockRequest(): ChainRequest = LEGACY_TIPS_REQUEST

    // Try the per-upstream remembered method (legacy by default); on "method not found"
    // fall back to the other one and remember whichever succeeds, so subsequent polls go
    // straight to the working method. Any other error propagates as before.
    override fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer> {
        val preferred = workingTipsRequest.getIfPresent(upstreamId) ?: LEGACY_TIPS_REQUEST
        val fallback = if (preferred === LEGACY_TIPS_REQUEST) CHAIN_TIPS_REQUEST else LEGACY_TIPS_REQUEST
        return fetchTips(api, upstreamId, preferred)
            .onErrorResume { err ->
                if (isMethodNotFound(err)) {
                    log.info(
                        "Aztec upstream {} does not support {}, falling back to {}",
                        upstreamId,
                        preferred.method,
                        fallback.method,
                    )
                    fetchTips(api, upstreamId, fallback)
                        .doOnNext { workingTipsRequest.put(upstreamId, fallback) }
                } else {
                    Mono.error(err)
                }
            }
    }

    private fun fetchTips(api: ChainReader, upstreamId: String, request: ChainRequest): Mono<BlockContainer> {
        return api.read(request).flatMap {
            parseBlock(it.getResult(), upstreamId, api)
        }
    }

    private fun isMethodNotFound(err: Throwable): Boolean {
        if (err is ChainException && err.error.code == METHOD_NOT_FOUND) {
            return true
        }
        return err.message?.contains("method not found", ignoreCase = true) ?: false
    }

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
