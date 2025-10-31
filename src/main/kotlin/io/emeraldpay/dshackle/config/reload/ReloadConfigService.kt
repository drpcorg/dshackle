package io.emeraldpay.dshackle.config.reload

import io.emeraldpay.dshackle.Config
import io.emeraldpay.dshackle.FileResolver
import io.emeraldpay.dshackle.config.HealthConfig
import io.emeraldpay.dshackle.config.HealthConfigReader
import io.emeraldpay.dshackle.config.MainConfig
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.config.UpstreamsConfigReader
import io.emeraldpay.dshackle.foundation.ChainOptionsReader
import org.springframework.stereotype.Component

@Component
class ReloadConfigService(
    private val config: Config,
    fileResolver: FileResolver,
    private val mainConfig: MainConfig,

) {
    private val optionsReader = ChainOptionsReader()
    private val upstreamsConfigReader = UpstreamsConfigReader(fileResolver, optionsReader)
    private val healthConfigReader = HealthConfigReader()

    fun readUpstreamsConfig(): UpstreamsConfig =
        config.getConfigPath().inputStream().use { input ->
            upstreamsConfigReader.read(input)
                ?: throw IllegalStateException("Cluster config is not defined in ${config.getConfigPath()}")
        }

    fun currentUpstreamsConfig() = mainConfig.initialConfig!!

    fun updateUpstreamsConfig(newConfig: UpstreamsConfig) {
        mainConfig.upstreams = newConfig
    }

    fun readHealthConfig(): HealthConfig =
        config.getConfigPath().inputStream().use { input ->
            healthConfigReader.read(input) ?: HealthConfig.default()
        }

    fun currentHealthConfig(): HealthConfig = mainConfig.health

    fun updateHealthConfig(newConfig: HealthConfig) {
        val current = mainConfig.health
        current.host = newConfig.host
        current.port = newConfig.port
        current.path = newConfig.path
        current.chains.clear()
        current.chains.putAll(newConfig.chains)
    }
}
