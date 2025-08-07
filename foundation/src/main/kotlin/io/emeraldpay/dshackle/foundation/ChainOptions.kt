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
        val validateGasPrice: Boolean,
        val validateChain: Boolean,
        val callLimitSize: Int,
        val disableLivenessSubscriptionValidation: Boolean,
        val disableBoundValidation: Boolean = false,
        val valdateErigonBug: Boolean,
        val disableLogIndexValidation: Boolean = false,
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
        var disableLivenessSubscriptionValidation: Boolean? = null,
        var disableBoundValidation: Boolean? = null,
        var validateErigonBug: Boolean? = null,
        var disableLogIndexValidation: Boolean? = null
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
            copy.disableLivenessSubscriptionValidation = overwrites.disableLivenessSubscriptionValidation ?: this.disableLivenessSubscriptionValidation
            copy.disableBoundValidation = overwrites.disableBoundValidation ?: this.disableBoundValidation
            copy.validateErigonBug = overwrites.validateErigonBug ?: this.validateErigonBug
            copy.disableLogIndexValidation = overwrites.disableLogIndexValidation ?: this.disableLogIndexValidation
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
                this.disableLivenessSubscriptionValidation ?: false,
                this.disableBoundValidation ?: false,
                this.validateErigonBug ?: true,
                this.disableLogIndexValidation ?: false,
            )
    }
}
