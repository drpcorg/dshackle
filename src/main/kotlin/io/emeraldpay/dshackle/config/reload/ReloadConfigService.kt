package io.emeraldpay.dshackle.config.reload

import io.emeraldpay.dshackle.Chain
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

    fun readUpstreamsConfig() = upstreamsConfigReader.read(config.getConfigPath().inputStream())!!

    fun currentUpstreamsConfig() = mainConfig.initialConfig!!

    fun currentHealthConfig() = mainConfig.health

    fun readHealthConfig() = healthConfigReader.read(config.getConfigPath().inputStream())!!

    fun updateUpstreamsConfig(newConfig: UpstreamsConfig) {
        mainConfig.upstreams = newConfig
    }

    fun updateHealthChains(newChains: Map<Chain, HealthConfig.ChainConfig>) {
        mainConfig.health.updateChains(newChains)
    }
}
