package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.foundation.YamlConfigReader
import org.yaml.snakeyaml.nodes.MappingNode

class RequestsConfigReader : YamlConfigReader<RequestsConfig>() {

    override fun read(input: MappingNode?): RequestsConfig {
        val config = RequestsConfig.default()
        getMapping(input, "requests")?.let { node ->
            getMapping(node, "coalescing")?.let { coalescing ->
                getValueAsBool(coalescing, "enabled")?.let {
                    config.coalescing = it
                }
            }
            getValueAsBool(node, "unstream-bounded")?.let {
                config.unstreamBounded = it
            }
        }
        return config
    }
}
