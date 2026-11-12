package io.emeraldpay.dshackle.config

open class RequestsConfig(
    var unstreamBounded: Boolean = false,
) {
    /**
     * Config example:
     * ```
     * requests:
     *   unstream-bounded: true
     * ```
     */
    companion object {
        fun default(): RequestsConfig {
            return RequestsConfig()
        }
    }
}
