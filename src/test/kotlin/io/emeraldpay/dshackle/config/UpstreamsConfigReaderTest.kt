package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.FileResolver
import io.emeraldpay.dshackle.foundation.ChainOptionsReader
import io.emeraldpay.dshackle.upstream.lowerbound.LowerBoundType
import io.emeraldpay.dshackle.upstream.lowerbound.ManualLowerBoundType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class UpstreamsConfigReaderTest {

    @Test
    fun `should parse additional settings with lower bounds`() {
        val yaml = """
            version: v1
            upstreams:
              - id: test-upstream
                chain: ethereum
                additional-settings:
                  manual-lower-bounds:
                    state:
                      type: head
                      value: -5000000
                    block:
                      type: fixed
                      value: 1   
                    tx:
                      type: fixed
                      value: 123  
                    receipts:
                      type: fixed
                      value: 15661
                    slot:
                      type: head
                      value: -34566  
                connection:
                  ethereum:
                    rpc:
                      url: "http://localhost:8545"
        """.trimIndent()

        val reader = UpstreamsConfigReader(
            FileResolver(File(".")),
            ChainOptionsReader(),
        )

        val config = reader.readInternal(yaml.byteInputStream())

        assertNotNull(config)
        assertEquals(1, config.upstreams.size)

        val upstream = config.upstreams[0]
        assertEquals("test-upstream", upstream.id)
        assertEquals(
            UpstreamsConfig.AdditionalSettings(
                mapOf(
                    LowerBoundType.SLOT to UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, -34566),
                    LowerBoundType.RECEIPTS to UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, 15661),
                    LowerBoundType.TX to UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, 123),
                    LowerBoundType.BLOCK to UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.FIXED, 1),
                    LowerBoundType.STATE to UpstreamsConfig.ManualBoundSetting(ManualLowerBoundType.HEAD, -5000000),
                ),
            ),
            upstream.additionalSettings,
        )
    }

    @Test
    fun `should parse customHeaders from YAML`() {
        val yaml = """
            version: v1
            upstreams:
              - id: test-upstream
                chain: ethereum
                custom-headers:
                  X-Custom-Header: "custom-value"
                  Authorization: "Bearer token"
                  X-Another-Header: "another-value"
                connection:
                  ethereum:
                    rpc:
                      url: "http://localhost:8545"
        """.trimIndent()

        val reader = UpstreamsConfigReader(
            FileResolver(File(".")),
            ChainOptionsReader(),
        )

        val config = reader.readInternal(yaml.byteInputStream())

        assertNotNull(config)
        assertEquals(1, config.upstreams.size)

        val upstream = config.upstreams[0]
        assertEquals("test-upstream", upstream.id)
        assertEquals(3, upstream.customHeaders.size)
        assertEquals("custom-value", upstream.customHeaders["X-Custom-Header"])
        assertEquals("Bearer token", upstream.customHeaders["Authorization"])
        assertEquals("another-value", upstream.customHeaders["X-Another-Header"])
    }

    @Test
    fun `should work without customHeaders`() {
        val yaml = """
            version: v1
            upstreams:
              - id: test-upstream
                chain: ethereum
                connection:
                  ethereum:
                    rpc:
                      url: "http://localhost:8545"
        """.trimIndent()

        val reader = UpstreamsConfigReader(
            FileResolver(File(".")),
            ChainOptionsReader(),
        )

        val config = reader.readInternal(yaml.byteInputStream())

        assertNotNull(config)
        assertEquals(1, config.upstreams.size)

        val upstream = config.upstreams[0]
        assertEquals("test-upstream", upstream.id)
        assertTrue(upstream.customHeaders.isEmpty())
    }

    @Test
    fun `should trim header names and values`() {
        val yaml = """
            version: v1
            upstreams:
              - id: test-upstream
                chain: ethereum
                custom-headers:
                  "  X-Header  ": "  value  "
                connection:
                  ethereum:
                    rpc:
                      url: "http://localhost:8545"
        """.trimIndent()

        val reader = UpstreamsConfigReader(
            FileResolver(File(".")),
            ChainOptionsReader(),
        )

        val config = reader.readInternal(yaml.byteInputStream())

        assertNotNull(config)
        val upstream = config.upstreams[0]
        assertEquals(1, upstream.customHeaders.size)
        assertEquals("value", upstream.customHeaders["X-Header"])
    }
}
