package io.emeraldpay.dshackle.config.reload

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Config
import io.emeraldpay.dshackle.FileResolver
import io.emeraldpay.dshackle.config.HealthConfig
import io.emeraldpay.dshackle.config.HealthConfigReader
import io.emeraldpay.dshackle.config.MainConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.util.ResourceUtils
import java.io.File

class HealthReloadConfigProcessorTest {
    private val fileResolver = FileResolver(File(""))
    private val mainConfig = MainConfig()

    private val config = mock<Config>()
    private val reloadConfigService = ReloadConfigService(config, fileResolver, mainConfig)
    private val processor = HealthReloadConfigProcessor(reloadConfigService)
    private val healthConfigReader = HealthConfigReader()

    @BeforeEach
    fun setupTests() {
        mainConfig.health = HealthConfig.default()
    }

    @Test
    fun `get health processor config type`() {
        assertThat(processor.configType()).isEqualTo("health config")
    }

    @Test
    fun `cant reload config if they are equal`() {
        val newConfigFile = ResourceUtils.getFile("classpath:configs/health-initial.yaml")
        whenever(config.getConfigPath()).thenReturn(newConfigFile)

        val initialConfigIs = ResourceUtils.getFile("classpath:configs/health-initial.yaml").inputStream()
        val initialConfig = healthConfigReader.read(initialConfigIs)!!

        mainConfig.health = initialConfig

        val result = processor.reload()

        assertThat(result).isFalse
    }

    @Test
    fun `cant reload config if they everything is different but not blockchain list`() {
        val newConfigFile = ResourceUtils.getFile("classpath:configs/health-changed-params.yaml")
        whenever(config.getConfigPath()).thenReturn(newConfigFile)

        val initialConfigIs = ResourceUtils.getFile("classpath:configs/health-initial.yaml").inputStream()
        val initialConfig = healthConfigReader.read(initialConfigIs)!!

        mainConfig.health = initialConfig

        val result = processor.reload()

        assertThat(result).isFalse
    }

    @Test
    fun `reload health config`() {
        val newConfigFile = ResourceUtils.getFile("classpath:configs/health-changed.yaml")
        whenever(config.getConfigPath()).thenReturn(newConfigFile)

        val initialConfigIs = ResourceUtils.getFile("classpath:configs/health-initial.yaml").inputStream()
        val initialConfig = healthConfigReader.read(initialConfigIs)!!

        mainConfig.health = initialConfig
        assertThat(mainConfig.health.configs().toSet()).isEqualTo(
            setOf(
                HealthConfig.ChainConfig(Chain.BSC__MAINNET, 0),
            ),
        )

        val reloaded = processor.reload()
        val newChains = mainConfig.health.configs()

        assertThat(reloaded).isTrue
        assertThat(newChains.toSet()).isEqualTo(
            setOf(
                HealthConfig.ChainConfig(Chain.ARBITRUM__MAINNET, 5),
                HealthConfig.ChainConfig(Chain.OPTIMISM__MAINNET, 1),
                HealthConfig.ChainConfig(Chain.BSC__MAINNET, 1),
            ),
        )
    }
}
