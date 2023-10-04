package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.foundation.YamlConfigReader
import org.yaml.snakeyaml.nodes.MappingNode

data class ReloadConfiguration(
    val port: Int,
) {
    companion object {
        fun default() = ReloadConfiguration(8093)
    }
}

class ReloadConfigurationReader : YamlConfigReader<ReloadConfiguration>() {

    override fun read(input: MappingNode?): ReloadConfiguration {
        val reload = getMapping(input, "reload") ?: return ReloadConfiguration.default()

        val port = getValueAsInt(reload, "port") ?: return ReloadConfiguration.default()

        return ReloadConfiguration(port)
    }
}
