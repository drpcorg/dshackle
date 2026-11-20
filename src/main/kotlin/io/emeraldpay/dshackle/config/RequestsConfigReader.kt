package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.foundation.YamlConfigReader
import org.yaml.snakeyaml.nodes.MappingNode

class RequestsConfigReader : YamlConfigReader<RequestsConfig>() {

    override fun read(input: MappingNode?): RequestsConfig {
        val config = RequestsConfig.default()
        getMapping(input, "requests")?.let { node ->
            getValueAsBool(node, "unstream-bounded")?.let {
                config.unstreamBounded = it
            }
        }
        return config
    }
}
