package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.Chain

class IndexConfig {
    var items: HashMap<Chain, Index> = HashMap<Chain, Index>()

    class Index(
        var rpc: String,
        var store: String,
        var ram_limit: Long?,
    )

    fun getByChain(chain: Chain): Index? {
        return items.get(chain)
    }
}
