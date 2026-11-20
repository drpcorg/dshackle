package io.emeraldpay.dshackle.upstream

import io.emeraldpay.dshackle.upstream.rpcclient.CallParams

interface CachingReader : Lifecycle {

    /**
     * An upstream answered a client request: a reader may keep the result for later requests
     */
    fun onUpstreamResponse(method: String, params: CallParams, result: ByteArray) {
    }
}

object NoopCachingReader : CachingReader {
    override fun start() {
    }

    override fun stop() {
    }

    override fun isRunning(): Boolean {
        return true
    }
}
