package io.emeraldpay.dshackle.foundation

import java.time.Duration

class ChainOptions {
    data class Options(
        val disableUpstreamValidation: Boolean,
        val disableValidation: Boolean,
        val validationInterval: Int,
        val timeout: Duration,
        val providesBalance: Boolean?,
        val validatePeers: Boolean,
        val minPeers: Int,
        val validateSyncing: Boolean,
        val validateCallLimit: Boolean,
        val validateChain: Boolean,
        val validateGasPrice: Boolean,
        val callLimitSize: Int,
    )

    data class DefaultOptions(
        var chains: List<String>? = null,
        var options: PartialOptions? = null,
    )

    data class PartialOptions(
        var disableValidation: Boolean? = null,
        var disableUpstreamValidation: Boolean? = null,
        var validationInterval: Int? = null,
        var timeout: Duration? = null,
        var providesBalance: Boolean? = null,
        var validatePeers: Boolean? = null,
        var validateCallLimit: Boolean? = null,
        var validateGasPrice: Boolean? = null,
        var minPeers: Int? = null,
        var validateSyncing: Boolean? = null,
        var validateChain: Boolean? = null,
        var callLimitSize: Int? = null,
    ) {
        companion object {
            @JvmStatic
            fun getDefaults(): PartialOptions {
                val options = PartialOptions()
                options.minPeers = 1
                return options
            }
        }

        fun merge(overwrites: PartialOptions?): PartialOptions {
            if (overwrites == null) {
                return this
            }
            val copy = PartialOptions()
            copy.validatePeers = overwrites.validatePeers ?: this.validatePeers
            copy.minPeers = overwrites.minPeers ?: this.minPeers
            copy.disableValidation = overwrites.disableValidation ?: this.disableValidation
            copy.validationInterval = overwrites.validationInterval ?: this.validationInterval
            copy.providesBalance = overwrites.providesBalance ?: this.providesBalance
            copy.validateSyncing = overwrites.validateSyncing ?: this.validateSyncing
            copy.validateCallLimit = overwrites.validateCallLimit ?: this.validateCallLimit
            copy.validateGasPrice = overwrites.validateGasPrice ?: this.validateGasPrice
            copy.timeout = overwrites.timeout ?: this.timeout
            copy.validateChain = overwrites.validateChain ?: this.validateChain
            copy.disableUpstreamValidation =
                overwrites.disableUpstreamValidation ?: this.disableUpstreamValidation
            copy.callLimitSize = overwrites.callLimitSize ?: this.callLimitSize
            return copy
        }

        fun buildOptions(): Options =
            Options(
                this.disableUpstreamValidation ?: false,
                this.disableValidation ?: false,
                this.validationInterval ?: 30,
                this.timeout ?: Duration.ofSeconds(60),
                this.providesBalance,
                this.validatePeers ?: true,
                this.minPeers ?: 1,
                this.validateSyncing ?: true,
                this.validateCallLimit ?: true,
                this.validateGasPrice ?: true,
                this.validateChain ?: true,
                this.callLimitSize ?: 1_000_000,
            )
    }
}
