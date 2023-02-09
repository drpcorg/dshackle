package io.emeraldpay.dshackle.config

class CompressionConfig(
    var grpc: GRPC = GRPC()
) {
    /**
     * Config example:
     * ```
     * compression:
     *   grpc:
     *     server:
     *       enabled: true
     *     client:
     *       enabled: false
     * ```
     */
    class GRPC(
        var serverEnabled: Boolean = false,
        var clientEnabled: Boolean = false
    )

    companion object {
        fun default(): CompressionConfig {
            return CompressionConfig()
        }
    }
}
