package io.emeraldpay.dshackle.config.hot

import io.emeraldpay.dshackle.Global
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompatibleVersionsRulesTest {
    @Test
    fun `test parsing`() {
        val raw = """
            rules:
              - client: "client1"
                blacklist:
                    - 1.0.0
                    - 1.0.1
                whitelist:
                    - 1.0.2
                    - 1.0.3
              - client: "client2"
                blacklist:
                    - 1.0.0
                    - 1.0.1
                whitelist:
                    - 1.0.2
                    - 1.0.3
        """.trimIndent()

        val rules = Global.yamlMapper.readValue(raw, CompatibleVersionsRules::class.java)!!.rules
        assertEquals(2, rules.size)
        assertEquals("client1", rules[0].client)
        assertEquals(2, rules[0].blacklist!!.size)
        assertEquals("1.0.0", rules[0].blacklist!![0])
        assertEquals("1.0.1", rules[0].blacklist!![1])
        assertEquals(2, rules[0].whitelist!!.size)
        assertEquals("1.0.2", rules[0].whitelist!![0])
        assertEquals("1.0.3", rules[0].whitelist!![1])
        assertEquals("client2", rules[1].client)
        assertEquals(2, rules[1].blacklist!!.size)
        assertEquals("1.0.0", rules[1].blacklist!![0])
        assertEquals("1.0.1", rules[1].blacklist!![1])
        assertEquals(2, rules[1].whitelist!!.size)
        assertEquals("1.0.2", rules[1].whitelist!![0])
        assertEquals("1.0.3", rules[1].whitelist!![1])
    }

    @Test
    fun `test parsing with networks`() {
        val raw = """
            rules:
              - client: "erigon"
                networks:
                    - ethereum
                blacklist:
                    - v2.40.0
                    - 3.1.0
              - client: "reth"
                blacklist:
                    - v1.4.0
                    - v1.4.1
              - client: "reth"
                networks:
                    - bsc
                blacklist:
                    - "reth/v1.6.0-2a4968e/x86_64-unknown-linux-gnu"
        """.trimIndent()

        val rules = Global.yamlMapper.readValue(raw, CompatibleVersionsRules::class.java)!!.rules
        assertEquals(3, rules.size)

        // First rule: erigon with networks
        assertEquals("erigon", rules[0].client)
        assertEquals(listOf("ethereum"), rules[0].networks)
        assertEquals(listOf("v2.40.0", "3.1.0"), rules[0].blacklist)

        // Second rule: reth without networks (applies to all)
        assertEquals("reth", rules[1].client)
        assertEquals(null, rules[1].networks)
        assertEquals(listOf("v1.4.0", "v1.4.1"), rules[1].blacklist)

        // Third rule: reth with networks (bsc only)
        assertEquals("reth", rules[2].client)
        assertEquals(listOf("bsc"), rules[2].networks)
        assertEquals(listOf("reth/v1.6.0-2a4968e/x86_64-unknown-linux-gnu"), rules[2].blacklist)
    }
}
