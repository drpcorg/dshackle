package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

const val BASE_TX_LIMIT = 1000L

interface PendingTransactionValidator {
    fun pendingTxExists(): Flux<Boolean>
}

class NoopPendingTransactionValidator : PendingTransactionValidator {
    override fun pendingTxExists(): Flux<Boolean> {
        return Flux.just(true)
    }
}

class PendingTransactionValidatorImpl(
    private val upstreamId: String,
    private val directReader: ChainReader,
    private val interval: Duration,
    private val txLimit: Long,
) : PendingTransactionValidator {
    private val log = LoggerFactory.getLogger(this::class.java)

    override fun pendingTxExists(): Flux<Boolean> {
        return Flux.interval(
            Duration.ofSeconds(15),
            interval,
        )
            .flatMap {
                directReader.read(ChainRequest("txpool_content", ListParams()))
                    .flatMap(ChainResponse::requireResult)
                    .map {
                        val node = Global.objectMapper.readTree(it)
                        val pendingTxsNode = node.get("pending")
                        val queuedTxsNode = node.get("queued")

                        val pendingTxsCount = if (pendingTxsNode != null) {
                            pendingTxsNode.fieldNames().asSequence().toList().size
                        } else {
                            0
                        }
                        val queuedTxsCount = if (queuedTxsNode != null) {
                            queuedTxsNode.fieldNames().asSequence().toList().size
                        } else {
                            0
                        }

                        ((pendingTxsCount + queuedTxsCount) >= txLimit)
                    }
                    .timeout(Duration.ofSeconds(30))
                    .onErrorResume {
                        log.error("unable to read txs from txpool of upstream {}", upstreamId, it)
                        Mono.just(false)
                    }
            }
    }
}
