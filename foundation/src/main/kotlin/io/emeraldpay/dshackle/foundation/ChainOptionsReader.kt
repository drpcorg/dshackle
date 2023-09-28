package io.emeraldpay.dshackle.foundation

import org.yaml.snakeyaml.nodes.MappingNode
import java.time.Duration

class ChainOptionsReader : YamlConfigReader<ChainOptions.PartialOptions>() {
    override fun read(upNode: MappingNode?): ChainOptions.PartialOptions? {
        return if (hasAny(upNode, "options")) {
            return getMapping(upNode, "options")?.let { values ->
                readOptions(values)
            }
        } else {
            null
        }
    }

    fun readOptions(values: MappingNode): ChainOptions.PartialOptions {
        val options = ChainOptions.PartialOptions()
        getValueAsBool(values, "validate-peers")?.let {
            options.validatePeers = it
        }
        getValueAsBool(values, "validate-syncing")?.let {
            options.validateSyncing = it
        }
        getValueAsBool(values, "validate-call-limit")?.let {
            options.validateCalllimit = it
        }
        getValueAsBool(values, "validate-chain")?.let {
            options.validateChain = it
        }
        getValueAsInt(values, "min-peers")?.let {
            options.minPeers = it
        }
        getValueAsInt(values, "timeout")?.let {
            options.timeout = Duration.ofSeconds(it.toLong())
        }
        getValueAsBool(values, "disable-validation")?.let {
            options.disableValidation = it
        }
        getValueAsInt(values, "validation-interval")?.let {
            options.validationInterval = it
        }
        getValueAsBool(values, "balance")?.let {
            options.providesBalance = it
        }
        return options
    }
}
