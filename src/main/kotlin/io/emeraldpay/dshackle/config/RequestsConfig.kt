package io.emeraldpay.dshackle.config

open class RequestsConfig(
    var coalescing: Boolean = false,
    var unstreamBounded: Boolean = false,
) {
    /**
     * Config example:
     * ```
     * requests:
     *   coalescing:
     *     enabled: true
     *   unstream-bounded: true
     * ```
     */
    companion object {
        fun default(): RequestsConfig {
            return RequestsConfig()
        }
    }
}
