package io.emeraldpay.dshackle.upstream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpstreamSettingsDetectorTest {

    @Test
    fun `parseLenientJson accepts plain JSON string`() {
        val data = "\"Geth/v1.12.0/linux-amd64/go1.20.3\"".toByteArray()
        val node = parseLenientJson(data)
        assertTrue(node.isTextual)
        assertEquals("Geth/v1.12.0/linux-amd64/go1.20.3", node.asText())
    }

    @Test
    fun `parseLenientJson accepts JSON string with unescaped LF (Moca Tendermint)`() {
        // Real-world Moca Tendermint web3_clientVersion result:
        //   "Version dev ()\nCompiled at  using Go go1.23.11 (amd64)"
        // where \n is a real line feed (CTRL-CHAR, code 10), not an escape sequence.
        // Default Jackson rejects this with "Illegal unquoted character"; the lenient
        // parser must accept it.
        val raw = "Version dev ()\nCompiled at  using Go go1.23.11 (amd64)"
        val data = ("\"" + raw + "\"").toByteArray()

        val node = parseLenientJson(data)

        assertTrue(node.isTextual)
        assertEquals(raw, node.asText())
    }

    @Test
    fun `parseLenientJson accepts JSON string with unescaped CR and tab`() {
        val raw = "Version dev ()\r\n\tCompiled with Go go1.23.11"
        val data = ("\"" + raw + "\"").toByteArray()

        val node = parseLenientJson(data)

        assertTrue(node.isTextual)
        assertEquals(raw, node.asText())
    }

    @Test
    fun `parseLenientJson still parses normal JSON objects`() {
        val data = """{"foo":"bar","n":42}""".toByteArray()
        val node = parseLenientJson(data)
        assertEquals("bar", node.get("foo").asText())
        assertEquals(42, node.get("n").asInt())
    }
}
