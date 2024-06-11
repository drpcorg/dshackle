package io.emeraldpay.dshackle.upstream.generic

import io.emeraldpay.dshackle.BlockchainType.BITCOIN
import io.emeraldpay.dshackle.BlockchainType.COSMOS
import io.emeraldpay.dshackle.BlockchainType.ETHEREUM
import io.emeraldpay.dshackle.BlockchainType.ETHEREUM_BEACON_CHAIN
import io.emeraldpay.dshackle.BlockchainType.NEAR
import io.emeraldpay.dshackle.BlockchainType.POLKADOT
import io.emeraldpay.dshackle.BlockchainType.SOLANA
import io.emeraldpay.dshackle.BlockchainType.STARKNET
import io.emeraldpay.dshackle.BlockchainType.UNKNOWN
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.cache.Caches
import io.emeraldpay.dshackle.config.ChainsConfig.ChainConfig
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.foundation.ChainOptions
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.CachingReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.EgressSubscription
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.IngressSubscription
import io.emeraldpay.dshackle.upstream.LogsOracle
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.UpstreamRpcModulesDetector
import io.emeraldpay.dshackle.upstream.UpstreamSettingsDetector
import io.emeraldpay.dshackle.upstream.UpstreamValidator
import io.emeraldpay.dshackle.upstream.beaconchain.BeaconChainSpecific
import io.emeraldpay.dshackle.upstream.calls.CallMethods
import io.emeraldpay.dshackle.upstream.calls.CallSelector
import io.emeraldpay.dshackle.upstream.cosmos.CosmosChainSpecific
import io.emeraldpay.dshackle.upstream.ethereum.EthereumChainSpecific
import io.emeraldpay.dshackle.upstream.ethereum.WsSubscriptions
import io.emeraldpay.dshackle.upstream.finalization.FinalizationDetector
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundService
import io.emeraldpay.dshackle.upstream.near.NearChainSpecific
import io.emeraldpay.dshackle.upstream.polkadot.PolkadotChainSpecific
import io.emeraldpay.dshackle.upstream.solana.SolanaChainSpecific
import io.emeraldpay.dshackle.upstream.starknet.StarknetChainSpecific
import org.apache.commons.collections4.Factory
import org.springframework.cloud.sleuth.Tracer
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler

typealias SubscriptionBuilder = (Multistream) -> EgressSubscription
typealias LocalReaderBuilder = (CachingReader, CallMethods, Head, LogsOracle?) -> Mono<ChainReader>
typealias CachingReaderBuilder = (Multistream, Caches, Factory<CallMethods>) -> CachingReader
typealias FinalizationDetectorBuilder = () -> FinalizationDetector

interface ChainSpecific {
    fun getFromHeader(data: ByteArray, upstreamId: String, api: ChainReader): Mono<BlockContainer>

    fun getLatestBlock(api: ChainReader, upstreamId: String): Mono<BlockContainer>

    fun listenNewHeadsRequest(): ChainRequest

    fun unsubscribeNewHeadsRequest(subId: String): ChainRequest

    fun finalizationDetectorBuilder(): FinalizationDetector

    fun localReaderBuilder(
        cachingReader: CachingReader,
        methods: CallMethods,
        head: Head,
        logsOracle: LogsOracle?,
    ): Mono<ChainReader>

    fun subscriptionBuilder(headScheduler: Scheduler): (Multistream) -> EgressSubscription

    fun makeCachingReaderBuilder(tracer: Tracer): CachingReaderBuilder

    fun validator(chain: Chain, upstream: Upstream, options: ChainOptions.Options, config: ChainConfig): UpstreamValidator

    fun upstreamSettingsDetector(chain: Chain, upstream: Upstream): UpstreamSettingsDetector?

    fun upstreamRpcModulesDetector(upstream: Upstream): UpstreamRpcModulesDetector?

    fun makeIngressSubscription(ws: WsSubscriptions): IngressSubscription

    fun callSelector(caches: Caches): CallSelector?

    fun lowerBoundService(chain: Chain, upstream: Upstream): LowerBoundService
}

object ChainSpecificRegistry {

    @JvmStatic
    fun resolve(chain: Chain): ChainSpecific {
        return when (chain.type) {
            ETHEREUM -> EthereumChainSpecific
            STARKNET -> StarknetChainSpecific
            POLKADOT -> PolkadotChainSpecific
            SOLANA -> SolanaChainSpecific
            NEAR -> NearChainSpecific
            ETHEREUM_BEACON_CHAIN -> BeaconChainSpecific
            COSMOS -> CosmosChainSpecific
            BITCOIN -> throw IllegalArgumentException("bitcoin should use custom streams implementation")
            UNKNOWN -> throw IllegalArgumentException("unknown chain")
        }
    }
}
