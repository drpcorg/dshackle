/**
 * Copyright (c) 2024 DRPC
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
package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class LogIndexValidatorTest {

    private lateinit var reader: ChainReader
    private lateinit var upstream: Upstream
    private lateinit var validator: LogIndexValidator

    @BeforeEach
    fun setup() {
        reader = mock<ChainReader> {}
        upstream = mock<Upstream> {
            on { getIngressReader() } doReturn reader
            on { getId() } doReturn "test-upstream"
        }
        validator = LogIndexValidator(upstream)
    }

    @Test
    fun `detects local logIndex numbering bug - basic case`() {
        setupMockForBugDetection(
            firstTxLogs = listOf("0x0", "0x1", "0x2"),
            secondTxLogs = listOf("0x0", "0x1"), // BUG: should be 0x3, 0x4
        )

        // First call (callCount=0) triggers validation immediately
        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `validates correct global logIndex numbering`() {
        setupMockForBugDetection(
            firstTxLogs = listOf("0x0", "0x1", "0x2"),
            secondTxLogs = listOf("0x3", "0x4"), // CORRECT: continues globally
        )

        // First call triggers validation immediately
        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test fun `handles single log transactions correctly`() {
        setupMockForBugDetection(
            firstTxLogs = listOf("0x0"),
            secondTxLogs = listOf("0x0"), // BUG: should be 0x1
        )

        // First call triggers validation immediately
        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `handles transactions without logs gracefully`() {
        reader = mock<ChainReader> {
            on { read(ChainRequest("eth_blockNumber", ListParams())) } doReturn
                Mono.just(ChainResponse("\"0x1000\"".toByteArray(), null))

            on { read(ChainRequest("eth_getBlockByNumber", ListParams("0x1000", true))) } doReturn
                Mono.just(ChainResponse(createBlock("0xaaa", "0xbbb").toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams("0xaaa"))) } doReturn
                Mono.just(ChainResponse("""{"logs": []}""".toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams("0xbbb"))) } doReturn
                Mono.just(ChainResponse("""{"logs": []}""".toByteArray(), null))
        }

        upstream = mock<Upstream> {
            on { getIngressReader() } doReturn reader
            on { getId() } doReturn "test-upstream"
        }
        validator = LogIndexValidator(upstream)

        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `handles block with insufficient transactions`() {
        reader = mock<ChainReader> {
            on { read(ChainRequest("eth_blockNumber", ListParams())) } doReturn
                Mono.just(ChainResponse("\"0x1000\"".toByteArray(), null))

            // Block with only 1 transaction
            on { read(ChainRequest("eth_getBlockByNumber", ListParams("0x1000", true))) } doReturn
                Mono.just(ChainResponse("""{"transactions": [{"hash": "0xaaa"}]}""".toByteArray(), null))

            // Should try previous block
            on { read(ChainRequest("eth_getBlockByNumber", ListParams("0xfff", true))) } doReturn
                Mono.just(ChainResponse(createBlock("0xccc", "0xddd").toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams("0xccc"))) } doReturn
                Mono.just(ChainResponse(createReceipt(listOf("0x0")).toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams("0xddd"))) } doReturn
                Mono.just(ChainResponse(createReceipt(listOf("0x1")).toByteArray(), null))
        }

        upstream = mock<Upstream> {
            on { getIngressReader() } doReturn reader
            on { getId() } doReturn "test-upstream"
        }
        validator = LogIndexValidator(upstream)

        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `performs validation only once every 10 calls`() {
        // First call (callCount=0) will perform validation (0 % 10 == 0)
        // So we need to set up mocks for the first call
        setupMockForBugDetection(
            firstTxLogs = listOf("0x0", "0x1"),
            secondTxLogs = listOf("0x2", "0x3"), // Correct numbering
        )

        // First call - performs validation (callCount=0)
        val firstResult = validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR).block()
        assert(firstResult == ValidateUpstreamSettingsResult.UPSTREAM_VALID) { "First call should validate and return VALID" }

        // Calls 2-10 should skip validation and return VALID immediately
        repeat(9) { _ ->
            StepVerifier.create(
                validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
            )
                .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
                .expectComplete()
                .verify(Duration.ofMillis(100)) // Should be fast since no actual validation
        }

        // 11th call (callCount=10) should perform validation again
        val eleventhResult = validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR).block()
        assert(eleventhResult == ValidateUpstreamSettingsResult.UPSTREAM_VALID) { "11th call should validate again" }
    }

    @Test
    fun `handles RPC errors gracefully`() {
        reader = mock<ChainReader> {
            on { read(ChainRequest("eth_blockNumber", ListParams())) } doReturn
                Mono.just(ChainResponse(null, ChainCallError(123, "Node error")))
        }

        upstream = mock<Upstream> {
            on { getIngressReader() } doReturn reader
            on { getId() } doReturn "test-upstream"
        }
        validator = LogIndexValidator(upstream)

        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_VALID) // Should not fail upstream
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `detects non-continuous logIndex as error`() {
        setupMockForBugDetection(
            firstTxLogs = listOf("0x0", "0x1", "0x2"),
            secondTxLogs = listOf("0x5", "0x6"), // Gap - should be 0x3, 0x4
        )

        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR) // Should fail due to gap
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    // Helper methods
    private fun setupMockForBugDetection(firstTxLogs: List<String>, secondTxLogs: List<String>) {
        reader = mock<ChainReader> {
            on { read(ChainRequest("eth_blockNumber", ListParams())) } doReturn
                Mono.just(ChainResponse("\"0x1000\"".toByteArray(), null))

            on { read(ChainRequest("eth_getBlockByNumber", ListParams("0x1000", true))) } doReturn
                Mono.just(ChainResponse(createBlock("0xaaa", "0xbbb").toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams("0xaaa"))) } doReturn
                Mono.just(ChainResponse(createReceipt(firstTxLogs).toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams("0xbbb"))) } doReturn
                Mono.just(ChainResponse(createReceipt(secondTxLogs).toByteArray(), null))
        }

        // Update upstream mock with new reader
        upstream = mock<Upstream> {
            on { getIngressReader() } doReturn reader
            on { getId() } doReturn "test-upstream"
        }
        validator = LogIndexValidator(upstream)
    }

    private fun setupReceiptsForTxs(
        firstHash: String,
        secondHash: String,
        firstLogs: List<String>,
        secondLogs: List<String>,
    ) {
        reader = mock<ChainReader> {
            on { read(ChainRequest("eth_blockNumber", ListParams())) } doReturn
                Mono.just(ChainResponse("\"0x1000\"".toByteArray(), null))

            on { read(ChainRequest("eth_getBlockByNumber", ListParams("0x1000", true))) } doReturn
                Mono.just(ChainResponse(createBlock(firstHash, secondHash).toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams(firstHash))) } doReturn
                Mono.just(ChainResponse(createReceipt(firstLogs).toByteArray(), null))

            on { read(ChainRequest("eth_getTransactionReceipt", ListParams(secondHash))) } doReturn
                Mono.just(ChainResponse(createReceipt(secondLogs).toByteArray(), null))
        }

        upstream = mock<Upstream> {
            on { getIngressReader() } doReturn reader
            on { getId() } doReturn "test-upstream"
        }
        validator = LogIndexValidator(upstream)
    }

    private fun createBlock(tx1Hash: String, tx2Hash: String) = """
        {
            "transactions": [
                {"hash": "$tx1Hash"},
                {"hash": "$tx2Hash"}
            ]
        }
    """.trimIndent()

    private fun createReceipt(logIndexes: List<String>) = """
        {
            "logs": [
                ${logIndexes.joinToString(",") { """{"logIndex": "$it"}""" }}
            ]
        }
    """.trimIndent()

    @Test
    fun `detects incorrect logIndex start in first transaction`() {
        setupMockForBugDetection(
            firstTxLogs = listOf("0x5", "0x6", "0x7"), // BUG: should start from 0x0
            secondTxLogs = listOf("0x8", "0x9"),
        )

        // Should detect that first transaction doesn't start at 0
        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }

    @Test
    fun `remembers error state between validations`() {
        // First call detects bug
        setupMockForBugDetection(
            firstTxLogs = listOf("0x0", "0x1", "0x2"),
            secondTxLogs = listOf("0x0", "0x1"), // BUG: should be 0x3, 0x4
        )

        // First call (callCount=0) triggers validation and detects bug
        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR)
            .expectComplete()
            .verify(Duration.ofSeconds(3))

        // Calls 2-10 should skip validation but return the error state
        repeat(9) { _ ->
            StepVerifier.create(
                validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
            )
                .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_FATAL_SETTINGS_ERROR) // Should remember error
                .expectComplete()
                .verify(Duration.ofMillis(100))
        }
    }

    @Test
    fun `returns last result when cannot find suitable block`() {
        // Setup: only blocks with 1 transaction
        reader = mock<ChainReader> {
            on { read(ChainRequest("eth_blockNumber", ListParams())) } doReturn
                Mono.just(ChainResponse("\"0x1000\"".toByteArray(), null))

            // All blocks return only 1 transaction
            on { read(argThat { method == "eth_getBlockByNumber" }) } doReturn
                Mono.just(ChainResponse("""{"transactions": [{"hash": "0xaaa"}]}""".toByteArray(), null))
        }

        upstream = mock<Upstream> {
            on { getIngressReader() } doReturn reader
            on { getId() } doReturn "test-upstream"
        }
        validator = LogIndexValidator(upstream)

        // First validation should return UPSTREAM_VALID (initial state)
        StepVerifier.create(
            validator.validate(ValidateUpstreamSettingsResult.UPSTREAM_SETTINGS_ERROR),
        )
            .expectNext(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
            .expectComplete()
            .verify(Duration.ofSeconds(3))
    }
}
