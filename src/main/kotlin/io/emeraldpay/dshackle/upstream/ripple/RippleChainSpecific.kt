package io.emeraldpay.dshackle.upstream.ripple

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.foundation.ChainOptions.Options
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.BasicUpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.GenericSingleCallValidator
import io.emeraldpay.dshackle.upstream.NodeTypeRequest
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.UpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.math.BigInteger
import java.time.Instant

object RippleChainSpecific : AbstractPollChainSpecific() {

    private val log = LoggerFactory.getLogger(RippleChainSpecific::class.java)

    override fun parseBlock(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        val jsonNode = Global.objectMapper.readTree(data)

        val block: RippleClosedLedger = if (jsonNode.has("ledger")) {
            Global.objectMapper.treeToValue(jsonNode.get("ledger"), RippleClosedLedger::class.java)
        } else {
            val result = Global.objectMapper.readValue(data, RippleBlock::class.java)
            result.closed.ledger
        }

        var height: Long = 0
        try {
            height = block.ledgerIndex.toLong()
        } catch (e: NumberFormatException) {
            log.error("Invalid ledgerIndex ${block.ledgerIndex}, upstreamId:$upstreamId")
        }

        return Mono.just(
            BlockContainer(
                height = height,
                hash = BlockId.from(block.ledgerHash),
                difficulty = BigInteger.ZERO,
                timestamp = Instant.EPOCH,
                full = false,
                json = data,
                parsed = block,
                transactions = emptyList(),
                upstreamId = upstreamId,
                parentHash = BlockId.from(block.parentHash),
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
                ChainRequest("server_state", ListParams()),
                upstream,
            ) { data -> validate(data) },
        )
    }

    override fun upstreamSettingsValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<ValidateUpstreamSettingsResult>> {
        return listOf(
            GenericSingleCallValidator(
                ChainRequest("server_state", ListParams()),
                upstream,
            ) { data ->
                validateSettings(data, chain)
            },
        )
    }

    override fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService {
        return RippleLowerBoundService(chain, upstream)
    }

    fun validate(data: ByteArray): UpstreamAvailability {
        // Check if this is a Clio response (has top-level "ledger" field)
        val resp = Global.objectMapper.readValue(data, RippleState::class.java)
        return when (resp.state.serverState) {
            "full", "proposing" -> UpstreamAvailability.OK
            "connected" -> UpstreamAvailability.SYNCING
            else -> UpstreamAvailability.UNAVAILABLE
        }
    }

    fun validateSettings(data: ByteArray, chain: Chain): ValidateUpstreamSettingsResult {
        val resp = Global.objectMapper.readValue(data, RippleState::class.java)
        return if (chain.chainId.isNotEmpty() && resp.state.networkId.toString() != chain.chainId.lowercase()) {
            ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR
        } else {
            ValidateUpstreamSettingsResult.UPSTREAM_VALID
        }
    }

    override fun latestBlockRequest(): ChainRequest =
        ChainRequest("ledger", ListParams())

    override fun upstreamSettingsDetector(chain: Chain, upstream: Upstream): UpstreamSettingsDetector? {
        return RippleUpstreamSettingsDetector(upstream)
    }
}

class RippleUpstreamSettingsDetector(val upstream: Upstream) : BasicUpstreamSettingsDetector(upstream) {
    override fun nodeTypeRequest(): NodeTypeRequest = NodeTypeRequest(clientVersionRequest())

    override fun clientVersion(node: JsonNode): String? {
        return parse(node).second
    }

    override fun clientType(node: JsonNode): String? {
        return parse(node).first
    }

    override fun internalDetectLabels(): Flux<Pair<String, String>> {
        return Flux.merge(
            detectNodeType(),
        )
    }

    override fun clientVersionRequest(): ChainRequest = ChainRequest("server_info", ListParams())

    override fun parseClientVersion(data: ByteArray): String {
        val res = parse(Global.objectMapper.readTree(data))
        if (res.first == null || res.second == null) {
            return "unknown"
        }

        return "${res.first}/${res.second}"
    }

    private fun parse(node: JsonNode): Pair<String?, String?> {
        val resp = Global.objectMapper.treeToValue(node, RippleInfoWrapper::class.java)
        if (resp.info.clio?.isNotEmpty() == true) {
            return Pair("clio", resp.info.clio)
        } else if (resp.info.buildVersion?.isNotEmpty() == true) {
            return Pair("rippled", resp.info.buildVersion)
        } else {
            return Pair(null, null)
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleInfoWrapper(
    @param:JsonProperty("info") var info: RippleInfo,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleInfo(
    @param:JsonProperty("clio_version") var clio: String?,
    @param:JsonProperty("build_version") var buildVersion: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleBlock(
    @param:JsonProperty("closed") var closed: RippleClosed,
    @param:JsonProperty("open") var open: RippleOpen?,
    @param:JsonProperty("status") var status: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleClosed(
    @param:JsonProperty("ledger") var ledger: RippleClosedLedger,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleOpen(
    @param:JsonProperty("ledger") var ledger: RippleOpenLedger,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleClosedLedger(
    @param:JsonProperty("account_hash") var accountHash: String,
    @param:JsonProperty("close_flags") var closeFlags: Short,
    @param:JsonProperty("close_time") var closeTime: Long,
    @param:JsonProperty("close_time_human") var closeTimeHuman: String,
    @param:JsonProperty("close_time_iso") var closeTimeIso: String,
    @param:JsonProperty("close_time_resolution") var closeTimeResolution: Short,
    @param:JsonProperty("closed") var closed: Boolean,
    @param:JsonProperty("ledger_hash") var ledgerHash: String,
    @param:JsonProperty("ledger_index") var ledgerIndex: String,
    @param:JsonProperty("parent_close_time") var parentCloseTime: Long,
    @param:JsonProperty("parent_hash") var parentHash: String,
    @param:JsonProperty("total_coins") var totalCoins: String,
    @param:JsonProperty("transaction_hash") var transactionHash: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleOpenLedger(
    @param:JsonProperty("closed") var closed: Boolean,
    @param:JsonProperty("ledger_index") var ledgerIndex: String,
    @param:JsonProperty("parent_hash") var parentHash: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleState(
    @param:JsonProperty("state") var state: RippleServerState,
    @param:JsonProperty("status") var status: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RippleServerState(
    @param:JsonProperty("build_version") val buildVersion: String,
    @param:JsonProperty("complete_ledgers") val completeLedgers: String,
    @param:JsonProperty("initial_sync_duration_us") val initialSyncDurationUs: String?,
    @param:JsonProperty("io_latency_ms") val ioLatencyMs: Int,
    @param:JsonProperty("jq_trans_overflow") val jqTransOverflow: String,
    @param:JsonProperty("last_close") val lastClose: LastClose,
    @param:JsonProperty("load_base") val loadBase: Int,
    @param:JsonProperty("load_factor") val loadFactor: Int,
    @param:JsonProperty("load_factor_fee_escalation") val loadFactorFeeEscalation: Int,
    @param:JsonProperty("load_factor_fee_queue") val loadFactorFeeQueue: Int,
    @param:JsonProperty("load_factor_fee_reference") val loadFactorFeeReference: Int,
    @param:JsonProperty("load_factor_server") val loadFactorServer: Int,
    @param:JsonProperty("network_id") val networkId: Int,
    @param:JsonProperty("peer_disconnects") val peerDisconnects: String?,
    @param:JsonProperty("peer_disconnects_resources") val peerDisconnectsResources: String?,
    @param:JsonProperty("peers") val peers: Int,
    @param:JsonProperty("ports") val ports: List<Port>,
    @param:JsonProperty("pubkey_node") val pubkeyNode: String?,
    @param:JsonProperty("server_state") val serverState: String?,
    @param:JsonProperty("server_state_duration_us") val serverStateDurationUs: String?,
    @param:JsonProperty("state_accounting") val stateAccounting: StateAccounting?,
    @param:JsonProperty("time") val time: String,
    @param:JsonProperty("uptime") val uptime: Long,
    @param:JsonProperty("validated_ledger") val validatedLedger: ValidatedLedger,
    @param:JsonProperty("validation_quorum") val validationQuorum: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LastClose(
    @param:JsonProperty("converge_time") val convergeTime: Int,
    @param:JsonProperty("proposers") val proposers: Int,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Port(
    @param:JsonProperty("port") val port: String,
    @param:JsonProperty("protocol") val protocol: List<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StateAccounting(
    @param:JsonProperty("connected") val connected: StateDuration,
    @param:JsonProperty("disconnected") val disconnected: StateDuration,
    @param:JsonProperty("full") val full: StateDuration,
    @param:JsonProperty("syncing") val syncing: StateDuration,
    @param:JsonProperty("tracking") val tracking: StateDuration,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StateDuration(
    @param:JsonProperty("duration_us") val durationUs: String,
    @param:JsonProperty("transitions") val transitions: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ValidatedLedger(
    @param:JsonProperty("base_fee") val baseFee: Int,
    @param:JsonProperty("close_time") val closeTime: Long,
    @param:JsonProperty("hash") val hash: String,
    @param:JsonProperty("reserve_base") val reserveBase: Long,
    @param:JsonProperty("reserve_inc") val reserveInc: Long,
    @param:JsonProperty("seq") val seq: Long,
)
