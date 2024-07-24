package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.foundation.ChainOptions.Options
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.BasicEthUpstreamRpcModulesDetector
import io.emeraldpay.dshackle.upstream.CachingReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.EgressSubscription
import io.emeraldpay.dshackle.upstream.GenericSingleCallValidator
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.IngressSubscription
import io.emeraldpay.dshackle.upstream.LogsOracle
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.SingleValidator
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.UpstreamRpcModulesDetector
import io.emeraldpay.dshackle.upstream.UpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.calls.CallMethods
import io.emeraldpay.dshackle.upstream.calls.CallSelector
import io.emeraldpay.dshackle.upstream.calls.EthereumCallSelector
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.AggregatedPendingTxes
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.EthereumWsIngressSubscription
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.NoPendingTxes
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.PendingTxesSource
import io.emeraldpay.dshackle.upstream.finalization.FinalizationDetector
import io.emeraldpay.dshackle.upstream.generic.AbstractPollChainSpecific
import io.emeraldpay.dshackle.upstream.generic.CachingReaderBuilder
import io.emeraldpay.dshackle.upstream.generic.GenericUpstream
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cloud.sleuth.Tracer
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import java.math.BigInteger

object EthereumChainSpecific : AbstractPollChainSpecific() {

    private val log: Logger = LoggerFactory.getLogger(EthereumChainSpecific::class.java)

    override fun parseBlock(data: ByteArray, upstreamId: String): BlockContainer {
        return BlockContainer.fromEthereumJson(data, upstreamId)
    }

    override fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer> {
        return Mono.just(parseBlock(data, upstreamId))
    }

    override fun latestBlockRequest() =
        ChainRequest("eth_getBlockByNumber", ListParams("latest", false))
    override fun listenNewHeadsRequest(): ChainRequest =
        ChainRequest("eth_subscribe", ListParams("newHeads"))
    override fun unsubscribeNewHeadsRequest(subId: String): ChainRequest =
        ChainRequest("eth_unsubscribe", ListParams(subId))

    override fun localReaderBuilder(
        cachingReader: CachingReader,
        methods: CallMethods,
        head: Head,
        logsOracle: LogsOracle?,
    ): Mono<ChainReader> {
        return Mono.just(EthereumLocalReader(cachingReader as EthereumCachingReader, methods, head, logsOracle))
    }

    override fun subscriptionBuilder(headScheduler: Scheduler): (Multistream) -> EgressSubscription {
        return { ms ->
            val pendingTxes: PendingTxesSource = (ms.getAll())
                .filter { it is GenericUpstream }
                .map { it as GenericUpstream }
                .filter { it.getIngressSubscription() is EthereumIngressSubscription }
                .mapNotNull {
                    (it.getIngressSubscription() as EthereumIngressSubscription).getPendingTxes()
                }.let {
                    if (it.isEmpty()) {
                        NoPendingTxes()
                    } else if (it.size == 1) {
                        it.first()
                    } else {
                        AggregatedPendingTxes(it)
                    }
                }
            EthereumEgressSubscription(ms, headScheduler, pendingTxes)
        }
    }

    override fun makeCachingReaderBuilder(tracer: Tracer): CachingReaderBuilder {
        return { ms, caches, methodsFactory -> EthereumCachingReader(ms, caches, methodsFactory, tracer) }
    }

    override fun upstreamValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<UpstreamAvailability>> {
        var validators = emptyList<SingleValidator<UpstreamAvailability>>()
        if (options.validateSyncing) {
            validators += GenericSingleCallValidator(
                ChainRequest("eth_syncing", ListParams()),
                upstream,
            ) { data ->
                val raw = Global.objectMapper.readTree(data)
                if (raw.isBoolean) {
                    upstream.getHead().onSyncingNode(raw.asBoolean())
                    if (raw.asBoolean()) {
                        UpstreamAvailability.SYNCING
                    } else {
                        UpstreamAvailability.OK
                    }
                } else {
                    when (chain) {
                        Chain.TRON_EVM__MAINNET, Chain.TRON_EVM__SHASTA -> {
                            var current =
                                BigInteger(raw.get("currentBlock")?.asText()?.lowercase()?.substringAfter("x"), 16)
                            var highest =
                                BigInteger(raw.get("highestBlock")?.asText()?.lowercase()?.substringAfter("x"), 16)

                            if (highest - current > config.syncingLagSize.toBigInteger()) {
                                UpstreamAvailability.SYNCING
                            } else {
                                UpstreamAvailability.OK
                            }
                        }
                        else -> {
                            log.warn("Received syncing object ${raw.toPrettyString()} for upstream ${upstream.getId()}")
                            UpstreamAvailability.SYNCING
                        }
                    }
                }
            }
        }
        if (options.validatePeers) {
            validators += GenericSingleCallValidator(
                ChainRequest("net_peerCount", ListParams()),
                upstream,
            ) { data ->
                val peers = Integer.decode(String(data).trim('"'))
                if (peers < options.minPeers) UpstreamAvailability.IMMATURE else UpstreamAvailability.OK
            }
        }
        return validators
    }

    override fun upstreamSettingsValidators(
        chain: Chain,
        upstream: Upstream,
        options: Options,
        config: ChainConfig,
    ): List<SingleValidator<ValidateUpstreamSettingsResult>> {
        val limitValidator = callLimitValidatorFactory(upstream, options, config, chain)

        return listOf(
            ChainIdValidator(upstream, chain),
            OldBlockValidator(upstream),
            GasPriceValidator(upstream, config),
        ) + if (limitValidator.isEnabled()) {
            listOf(limitValidator)
        } else {
            emptyList()
        }
    }

    override fun upstreamRpcModulesDetector(upstream: Upstream): UpstreamRpcModulesDetector {
        return BasicEthUpstreamRpcModulesDetector(upstream)
    }

    override fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService {
        return EthereumLowerBoundService(chain, upstream)
    }

    override fun finalizationDetectorBuilder(): FinalizationDetector {
        return EthereumFinalizationDetector()
    }

    override fun upstreamSettingsDetector(
        chain: Chain,
        upstream: Upstream,
    ): UpstreamSettingsDetector {
        return EthereumUpstreamSettingsDetector(upstream, chain)
    }

    override fun makeIngressSubscription(ws: WsSubscriptions): IngressSubscription {
        return EthereumWsIngressSubscription(ws)
    }

    override fun callSelector(caches: Caches): CallSelector {
        return EthereumCallSelector(caches)
    }
}
