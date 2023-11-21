package io.emeraldpay.dshackle.upstream

import io.emeraldpay.dshackle.config.IndexConfig
import reactor.core.publisher.Mono

class LogsOracle(
    var config: IndexConfig.Index,
) {
    val db = org.drpc.logsoracle.LogsOracle("", config.store, config.ram_limit ?: 0L)

    fun estimate(
        fromBlock: Long?,
        toBlock: Long?,
        address: List<String>,
        topics: List<List<String>>,
    ): Mono<Long> {
        return Mono.just(db.query(fromBlock, toBlock, address, topics))
    }
}
