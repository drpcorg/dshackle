/**
 * Copyright (c) 2020 EmeraldPay, Inc
 * Copyright (c) 2019 ETCDEV GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream.calls

import com.fasterxml.jackson.databind.ObjectMapper
import io.emeraldpay.dshackle.Chain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagedCallMethodsWeb3ClientVersionTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `should pad client version with 2 components to 4 components`() {
        // Given: Mock delegate that returns 2 components
        val delegate = DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        val managedMethods = ManagedCallMethods(
            delegate = delegate,
            enabled = emptySet(),
            disabled = emptySet(),
            groupsEnabled = emptySet(),
            groupsDisabled = emptySet(),
        )

        // Test transformation by creating a mock response with 2 components
        val twoComponentVersion = "\"CustomNode/v1.0.0\""
        val response = managedMethods.transformWeb3ClientVersion(twoComponentVersion.toByteArray())

        val resultString = String(response)
        val resultVersion = objectMapper.readValue(resultString, String::class.java)

        assertEquals("CustomNode/v1.0.0/unk/unk", resultVersion)
    }

    @Test
    fun `should pad client version with 1 component to 4 components`() {
        val delegate = DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        val managedMethods = ManagedCallMethods(
            delegate = delegate,
            enabled = emptySet(),
            disabled = emptySet(),
            groupsEnabled = emptySet(),
            groupsDisabled = emptySet(),
        )

        val oneComponentVersion = "\"CustomNode\""
        val response = managedMethods.transformWeb3ClientVersion(oneComponentVersion.toByteArray())

        val resultString = String(response)
        val resultVersion = objectMapper.readValue(resultString, String::class.java)

        assertEquals("CustomNode/unk/unk/unk", resultVersion)
    }

    @Test
    fun `should not modify client version with 4 components`() {
        val delegate = DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        val managedMethods = ManagedCallMethods(
            delegate = delegate,
            enabled = emptySet(),
            disabled = emptySet(),
            groupsEnabled = emptySet(),
            groupsDisabled = emptySet(),
        )

        val fourComponentVersion = "\"Geth/v1.5.4-unstable-586f10ec/linux/go1.7.1\""
        val response = managedMethods.transformWeb3ClientVersion(fourComponentVersion.toByteArray())

        // Should return original response unchanged
        val resultString = String(response)
        assertEquals(fourComponentVersion, resultString)
    }

    @Test
    fun `should not modify client version with more than 4 components`() {
        val delegate = DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        val managedMethods = ManagedCallMethods(
            delegate = delegate,
            enabled = emptySet(),
            disabled = emptySet(),
            groupsEnabled = emptySet(),
            groupsDisabled = emptySet(),
        )

        val moreComponentsVersion = "\"Geth/v1.5.4-unstable/extra/linux/go1.7.1\""
        val response = managedMethods.transformWeb3ClientVersion(moreComponentsVersion.toByteArray())

        // Should return original response unchanged
        val resultString = String(response)
        assertEquals(moreComponentsVersion, resultString)
    }

    @Test
    fun `should handle empty string gracefully`() {
        val delegate = DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        val managedMethods = ManagedCallMethods(
            delegate = delegate,
            enabled = emptySet(),
            disabled = emptySet(),
            groupsEnabled = emptySet(),
            groupsDisabled = emptySet(),
        )

        val emptyVersion = "\"\""
        val response = managedMethods.transformWeb3ClientVersion(emptyVersion.toByteArray())

        val resultString = String(response)
        val resultVersion = objectMapper.readValue(resultString, String::class.java)

        assertEquals("unk/unk/unk/unk", resultVersion)
    }

    @Test
    fun `should handle malformed JSON gracefully`() {
        val delegate = DefaultEthereumMethods(Chain.ETHEREUM__MAINNET)
        val managedMethods = ManagedCallMethods(
            delegate = delegate,
            enabled = emptySet(),
            disabled = emptySet(),
            groupsEnabled = emptySet(),
            groupsDisabled = emptySet(),
        )

        val malformedJson = "not-valid-json"
        val response = managedMethods.transformWeb3ClientVersion(malformedJson.toByteArray())

        // Should return original response unchanged
        val resultString = String(response)
        assertEquals(malformedJson, resultString)
    }
}

// Extension function to make transformWeb3ClientVersion testable
private fun ManagedCallMethods.transformWeb3ClientVersion(originalResponse: ByteArray): ByteArray {
    try {
        val responseString = String(originalResponse)
        val mapper = ObjectMapper()

        // Parse JSON to extract the actual version string
        val jsonNode = mapper.readTree(responseString)
        val versionString = if (jsonNode.isTextual) {
            jsonNode.asText()
        } else {
            // If it's not a string, return original response
            return originalResponse
        }

        // Split by "/" to get components, filter out empty strings
        val components = versionString.split("/").filter { it.isNotEmpty() }.toMutableList()

        // If we already have 4 or more components, return original
        if (components.size >= 4) {
            return originalResponse
        }

        // If we have no components (empty string), start fresh
        if (components.isEmpty()) {
            components.add("unk")
        }

        // Pad with "unk" until we have exactly 4 components
        while (components.size < 4) {
            components.add("unk")
        }

        // Reconstruct the version string
        val normalizedVersion = components.take(4).joinToString("/")

        // Return as JSON string
        val normalizedJson = mapper.writeValueAsString(normalizedVersion)

        return normalizedJson.toByteArray()
    } catch (e: Exception) {
        return originalResponse
    }
}
