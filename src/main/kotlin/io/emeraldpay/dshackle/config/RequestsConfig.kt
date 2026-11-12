package io.emeraldpay.dshackle.config

open class RequestsConfig(
    var unstreamBounded: Boolean = false,
    // 0 - ask all upstreams
    var notNullMaxUpstreams: Int = 0,
) {
    /**
     * Config example:
     * ```
     * requests:
     *   unstream-bounded: true
     *   not-null-max-upstreams: 2
     * ```
     */
    companion object {
        fun default(): RequestsConfig {
            return RequestsConfig()
        }
    }
}
