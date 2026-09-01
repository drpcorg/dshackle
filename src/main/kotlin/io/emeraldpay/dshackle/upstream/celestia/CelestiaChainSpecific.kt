package io.emeraldpay.dshackle.upstream.celestia

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
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

/**
 * Celestia DA node (celestia-node) JSON-RPC API. CometBFT encoding: the header
 * height is a decimal string, hashes are bare hex strings. Commits are final,
 * so the head is the finalized pointer and there is no reorg handling.
 */
object CelestiaChainSpecific : AbstractPollChainSpecific() {
    private val log = LoggerFactory.getLogger(CelestiaChainSpecific::class.java)

    override fun latestBlockRequest(): ChainRequest = ChainRequest("header.LocalHead", ListParams())

    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val root = Global.objectMapper.readTree(data)
        val height = parseHeight(root)
        val hash = root.path("commit").path("block_id").path("hash").asText("")
        val parentHash = root.path("header").path("last_block_id").path("hash").asText("")
        val timestamp = runCatching { Instant.parse(root.path("header").path("time").asText()) }
            .getOrDefault(Instant.EPOCH)

        return Mono.just(
            BlockContainer(
                height = height ?: 0L,
                hash = BlockId.from(hash.ifBlank { "0x0" }),
                difficulty = BigInteger.ZERO,
                timestamp = timestamp,
                full = false,
                json = data,
                parsed = root,
                transactions = emptyList(),
                upstreamId = upstreamId,
                parentHash = parentHash.ifBlank { null }?.let { BlockId.from(it) },
            ),
        )
    }

    // celestia-node subscriptions (header.Subscribe) use the go-jsonrpc channel
    // protocol (xrpc.ch.val notifications), which is not a JSON-RPC subscription:
    // the chain is HTTP-poll only.
    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        throw UnsupportedOperationException("Celestia does not support websocket subscriptions")
    }

    override fun listenNewHeadsRequest(): ChainRequest {
        throw UnsupportedOperationException("Celestia does not support websocket subscriptions")
    }

    override fun unsubscribeNewHeadsRequest(subId: Any): ChainRequest {
        throw UnsupportedOperationException("Celestia does not support websocket subscriptions")
    }

    override fun upstreamValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<UpstreamAvailability>> {
        return listOf(
            GenericSingleCallValidator(
                ChainRequest("node.Ready", ListParams()),
                upstream,
            ) { data ->
                val ready = runCatching { Global.objectMapper.readTree(data).asBoolean(false) }.getOrDefault(false)
                if (ready) {
                    UpstreamAvailability.OK
                } else {
                    log.warn("Celestia node {} reports not ready", upstream.getId())
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
                ChainRequest("header.LocalHead", ListParams()),
                upstream,
            ) { data ->
                validateChainId(data, chain, upstream.getId())
            },
        )
    }

    override fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService {
        return CelestiaLowerBoundService(chain, upstream)
    }

    fun validateChainId(data: ByteArray, chain: Chain, upstreamId: String): ValidateUpstreamSettingsResult {
        val root = try {
            Global.objectMapper.readTree(data)
        } catch (e: Exception) {
            log.warn("Celestia node {} returned unparseable header payload: {}", upstreamId, e.message)
            return ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR
        }
        val reported = root?.path("header")?.path("chain_id")?.asText("").orEmpty()
        // an empty chain id is a mismatch, not a transient error
        return if (reported.equals(chain.chainId, ignoreCase = true)) {
            ValidateUpstreamSettingsResult.UPSTREAM_VALID
        } else {
            log.warn(
                "Celestia node {} chain id mismatch: reported={} expected={}",
                upstreamId,
                reported,
                chain.chainId,
            )
            ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
        }
    }

    fun parseHeight(root: JsonNode): Long? {
        val text = root.path("header").path("height").asText("")
        return text.toLongOrNull()
    }
}
