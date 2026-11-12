package io.emeraldpay.dshackle.upstream.ethereum

import com.fasterxml.jackson.module.kotlin.readValue
import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Defaults
import io.emeraldpay.dshackle.Global
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ethereum.json.BlockJson
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionRefJson
import io.emeraldpay.dshackle.upstream.finalization.FinalizationData
import io.emeraldpay.dshackle.upstream.finalization.FinalizationDetector
import io.emeraldpay.dshackle.upstream.finalization.FinalizationType
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EthereumFinalizationDetector : FinalizationDetector {
    companion object {
        private val log = LoggerFactory.getLogger(EthereumFinalizationDetector::class.java)

        val POLL_INTERVAL: Duration = Duration.ofSeconds(15)

        // a tag which didn't move is polled every 2nd tick, then every 4th (30s, 60s), and on every tick again once it moves
        const val MAX_BACKOFF_TICKS = 4L
    }

    /**
     * Polling state per tag. On many chains the finalized (and safe) block moves once in a few minutes,
     * so most of the polls return the same block. A tag that didn't move is polled less often.
     */
    private class PollState {
        @Volatile
        var backoff = 1L

        @Volatile
        var nextTick = 0L

        // when the value was moved by a response to a client request, in that case there is no need to poll
        @Volatile
        var externalAt = 0L
    }

    private val polls = ConcurrentHashMap<FinalizationType, PollState>()
    private val ticks = AtomicLong()

    /** for tests */
    var clock: () -> Long = System::currentTimeMillis

    /** true if [type] should be polled on this tick */
    fun shouldPoll(type: FinalizationType, tick: Long): Boolean {
        val state = polls.computeIfAbsent(type) { PollState() }
        if (clock() - state.externalAt < POLL_INTERVAL.toMillis()) {
            state.nextTick = tick + 1
            return false
        }
        return tick >= state.nextTick
    }

    /** remember the result of a poll, and back off if the value didn't move */
    fun polled(type: FinalizationType, tick: Long, height: Long?) {
        val state = polls.computeIfAbsent(type) { PollState() }
        val moved = height != null && height > (data[type]?.height ?: 0)
        state.backoff = if (moved) 1 else minOf(state.backoff * 2, MAX_BACKOFF_TICKS)
        state.nextTick = tick + state.backoff
    }

    val data: ConcurrentHashMap<FinalizationType, FinalizationData> = ConcurrentHashMap()
    private val disableDetector: ConcurrentHashMap<FinalizationType, Boolean> = ConcurrentHashMap()
    private val finalizationSink = Sinks.many().multicast().directBestEffort<FinalizationData>()

    private val errorRegex = "(" +
        ".*bad request.*" +
        "|.*block not found.*" +
        "|.*Unknown block.*" +
        "|.*tag not supported on pre-merge network.*" +
        "|.*hex string without 0x prefix.*" +
        "|.*Invalid params.*" +
        "|.*invalid syntax.*" +
        "|.*invalid block number.*" +
        ")"
    override fun detectFinalization(
        upstream: Upstream,
        blockTime: Duration,
        chain: Chain,
    ): Flux<FinalizationData> {
        return Flux.merge(
            finalizationSink.asFlux(),
            Flux.interval(
                Duration.ofSeconds(0),
                POLL_INTERVAL,
            ).flatMap {
                val tick = ticks.getAndIncrement()
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
                    if (!disableDetector.getOrDefault(type, false) && shouldPoll(type, tick)) {
                        upstream
                            .getIngressReader()
                            .read(req)
                            .timeout(Defaults.internalCallsTimeout)
                            .flatMap {
                                it.requireResult().flatMap { result ->
                                    val block = Global.objectMapper.readValue<BlockJson<TransactionRefJson>>(result)
                                    if (block != null) {
                                        polled(type, tick, block.number)
                                        Mono.just(FinalizationData(block.number, type))
                                    } else {
                                        Mono.empty()
                                    }
                                }
                            }
                            .onErrorResume {
                                if (it.message != null && it.message!!.matches(Regex(errorRegex))) {
                                    log.warn("Can't retrieve tagged block, finalization detector of upstream {} tag {} is disabled", upstream.getId(), type)
                                    disableDetector[type] = true
                                } else {
                                    log.error("Error in FinalizationDetector of upstream {}, reason - {}", upstream.getId(), it.message)
                                }
                                Mono.empty()
                            }
                    } else {
                        Flux.empty()
                    }
                }
            }.filter {
                it.height > (data[it.type]?.height ?: 0)
            }.doOnNext {
                data[it.type] = it
            },
        )
    }

    override fun addFinalization(finalization: FinalizationData) {
        if (finalization.height > (data[finalization.type]?.height ?: 0)) {
            polls.computeIfAbsent(finalization.type) { PollState() }.externalAt = clock()
        }
        finalizationSink.emitNext(finalization) { _, res -> res == Sinks.EmitResult.FAIL_NON_SERIALIZED }
    }

    override fun getFinalizations(): Collection<FinalizationData> {
        return data.values
    }

    override fun getFinalizationByType(type: FinalizationType): FinalizationData? {
        return data[type]
    }
}
