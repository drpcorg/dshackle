package io.emeraldpay.dshackle.config

import org.yaml.snakeyaml.nodes.MappingNode

class StreamingConfigReader : YamlConfigReader<StreamingConfig>() {
    override fun read(input: MappingNode?): StreamingConfig {
        val streaming = getMapping(input, "streaming") ?: return StreamingConfig.default()

        val chunkSize = getValueAsInt(streaming, "chunk-size") ?: DEFAULT_CHUNK_SIZE

        return StreamingConfig(chunkSize)
    }
}
