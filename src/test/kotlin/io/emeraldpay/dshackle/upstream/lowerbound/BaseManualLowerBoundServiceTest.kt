package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Upstream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class BaseManualLowerBoundServiceTest {

    @Test
    fun `no manual settings then return nothing`() {
        val upstream = mock<Upstream>()
        val service = BaseManualLowerBoundService(upstream, emptyMap())

        assertThat(service.manualBoundTypes()).isEmpty()
        LowerBoundType.entries
            .forEach {
                assertThat(service.manualLowerBound(it)).isNull()
                assertThat(service.hasManualBound(it)).isFalse
            }
    }

    @ParameterizedTest
    @MethodSource("headProviderData")
    fun `test head provider - canHandle`(
        upstream: Upstream,
        setting: UpstreamsConfig.ManualBoundSetting,
        expected: Boolean,
    ) {
        val provider = HeadManualLowerBoundProvider(upstream, setting)

        assertThat(provider.canHandle()).isEqualTo(expected)
    }

    @ParameterizedTest
    @MethodSource("fixedProviderData")
    fun `test fixed provider - canHandle`(
        setting: UpstreamsConfig.ManualBoundSetting,
        expected: Boolean,
    ) {
        val provider = FixedManualLowerBoundProvider(setting)

        assertThat(provider.canHandle()).isEqualTo(expected)
    }

    @Test
    fun `get value from head provider`() {
        val upstream = mock<Upstream> {
            val head = mock<Head> {
                on { getCurrentHeight() } doReturn 100
            }
            on { getHead() } doReturn head
        }
        val provider = HeadManualLowerBoundProvider(upstream, UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, -45))

        assertThat(provider.getManualLowerBound()).isEqualTo(55)
    }

    @Test
    fun `get value from fixed provider`() {
        val provider = FixedManualLowerBoundProvider(UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, 200))

        assertThat(provider.getManualLowerBound()).isEqualTo(200)
    }

    @Test
    fun `no supported manual type then null otherwise get a value`() {
        val upstream = mock<Upstream>()
        val settings = mapOf(
            LowerBoundType.STATE to UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, 100),
        )
        val service = BaseManualLowerBoundService(upstream, settings)

        assertThat(service.manualLowerBound(LowerBoundType.STATE))
            .usingRecursiveComparison()
            .ignoringFields("timestamp")
            .isEqualTo(LowerBoundData(100, LowerBoundType.STATE))
        assertThat(service.manualBoundTypes()).containsExactly(LowerBoundType.STATE)
        assertThat(service.hasManualBound(LowerBoundType.STATE)).isTrue()
        LowerBoundType.entries
            .filter { it != LowerBoundType.STATE }
            .forEach {
                assertThat(service.manualLowerBound(it)).isNull()
                assertThat(service.hasManualBound(it)).isFalse
            }
    }

    @Test
    fun `can not be handled then null`() {
        val upstream = mock<Upstream>()
        val settings = mapOf(
            LowerBoundType.STATE to UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, -100),
        )
        val service = BaseManualLowerBoundService(upstream, settings)

        assertThat(service.manualLowerBound(LowerBoundType.STATE)).isNull()
        assertThat(service.manualBoundTypes()).containsExactly(LowerBoundType.STATE)
        assertThat(service.hasManualBound(LowerBoundType.STATE)).isTrue()
        LowerBoundType.entries
            .filter { it != LowerBoundType.STATE }
            .forEach {
                assertThat(service.manualLowerBound(it)).isNull()
                assertThat(service.hasManualBound(it)).isFalse
            }
    }

    companion object {
        @JvmStatic
        fun fixedProviderData(): List<Arguments> =
            listOf(
                Arguments.of(
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, 1),
                    false,
                ),
                Arguments.of(
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, -121),
                    false,
                ),
                Arguments.of(
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, 55),
                    true,
                ),
            )

        @JvmStatic
        fun headProviderData(): List<Arguments> =
            listOf(
                Arguments.of(
                    mock<Upstream>(),
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, 1),
                    false,
                ),
                Arguments.of(
                    mock<Upstream>(),
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, 1241),
                    false,
                ),
                Arguments.of(
                    mock<Upstream> {
                        val head = mock<Head> {
                            on { getCurrentHeight() } doReturn null
                        }
                        on { getHead() } doReturn head
                    },
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, -100),
                    false,
                ),
                Arguments.of(
                    mock<Upstream> {
                        val head = mock<Head> {
                            on { getCurrentHeight() } doReturn 20
                        }
                        on { getHead() } doReturn head
                    },
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, -100),
                    false,
                ),
                Arguments.of(
                    mock<Upstream> {
                        val head = mock<Head> {
                            on { getCurrentHeight() } doReturn 150
                        }
                        on { getHead() } doReturn head
                    },
                    UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, -100),
                    true,
                ),
            )
    }
}
