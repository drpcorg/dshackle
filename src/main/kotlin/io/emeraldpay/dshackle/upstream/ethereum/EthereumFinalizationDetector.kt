package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ethereum.json.BlockJson
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionRefJson
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcException
import io.emeraldpay.dshackle.upstream.ethereum.rpc.RpcResponseError
import io.emeraldpay.dshackle.upstream.finalization.FinalizationData
import io.emeraldpay.dshackle.upstream.finalization.FinalizationDetector
import io.emeraldpay.dshackle.upstream.finalization.FinalizationType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class EthereumFinalizationDetector : FinalizationDetector {
    companion object {
        private val log = LoggerFactory.getLogger(EthereumFinalizationDetector::class.java)
    }

    val data: ConcurrentHashMap<FinalizationType, FinalizationData> = ConcurrentHashMap()

    private val finalizationSink = Sinks.many().multicast().directBestEffort<FinalizationData>()

    override fun detectFinalization(
        upstream: Upstream,
        blockTime: Duration,
    ): Flux<FinalizationData> {
        return Flux.merge(
            finalizationSink.asFlux(),
            Flux.interval(
                Duration.ofSeconds(0),
                Duration.ofSeconds(15),
            ).flatMap {
                Flux.fromIterable(
                    listOf(
                        Pair(
                            FinalizationType.SAFE_BLOCK,
                            ChainRequest(
                                "eth_getBlockByNumber",
                                ListParams("safe", false),
                                1,
                            ),
                        ),
                        Pair(
                            FinalizationType.FINALIZED_BLOCK,
                            ChainRequest(
                                "eth_getBlockByNumber",
                                ListParams("finalized", false),
                                2,
                            ),
                        ),
                    ),
                ).flatMap { (type, req) ->
                    upstream
                        .getIngressReader()
                        .read(req)
                        .flatMap {
                            it.requireResult().map { result ->
                                val block =
                                    Global.objectMapper
                                        .readValue(result, BlockJson::class.java) as BlockJson<TransactionRefJson>?
                                if (block != null) {
                                    FinalizationData(block.number, type)
                                } else {
                                    throw RpcException(RpcResponseError.CODE_INVALID_JSON, "can't parse block data")
                                }
                            }
                        }
                }.onErrorResume {
                    log.error("Error during retrieving — $it")
                    Flux.empty()
                }
            }.filter {
                it.height > (data[it.type]?.height ?: 0)
            }.doOnNext {
                data[it.type] = it
            },
        )
    }

    override fun addFinalization(finalization: FinalizationData) {
        finalizationSink.emitNext(finalization) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
    }

    override fun getFinalizations(): Collection<FinalizationData> {
        return data.values
    }
}
