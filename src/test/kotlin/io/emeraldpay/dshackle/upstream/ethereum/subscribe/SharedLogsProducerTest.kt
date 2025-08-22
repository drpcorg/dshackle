/**
 * Copyright (c) 2021 EmeraldPay, Inc
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
package io.emeraldpay.dshackle.upstream.ethereum.subscribe

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.reader.Reader
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.ethereum.EthereumCachingReader
import io.emeraldpay.dshackle.upstream.ethereum.EthereumDirectReader
import io.emeraldpay.dshackle.upstream.ethereum.domain.Address
import io.emeraldpay.dshackle.upstream.ethereum.domain.BlockHash
import io.emeraldpay.dshackle.upstream.ethereum.domain.TransactionId
import io.emeraldpay.dshackle.upstream.ethereum.hex.Hex32
import io.emeraldpay.dshackle.upstream.ethereum.hex.HexData
import io.emeraldpay.dshackle.upstream.ethereum.json.TransactionLogJson
import io.emeraldpay.dshackle.upstream.ethereum.subscribe.json.LogMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.anyOrNull
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.time.Duration

/**
 * Полноценные тесты для SharedLogsProducer
 * Comprehensive tests for SharedLogsProducer
 * Includes both integration tests and unit tests for LogsSubscription
 */
class SharedLogsProducerTest {

    companion object {
        private const val TEST_ADDRESS = "0xe0aadb0a012dbcdc529c4c743d3e0385a0b54d3d"
        private const val TEST_BLOCK_HASH = "0x668b92d6b8c7db1350fd527fec4885ce5be2159b2b7daf6b126babdcbaa349da"
        private const val TEST_TX_HASH = "0xb5e554178a94fd993111f2ae64cb708cb0899d7b5182024e70d5c468164a8bec"
        private const val OTHER_ADDRESS = "0x1234567890123456789012345678901234567890"
        private const val OTHER_TX_HASH = "0xc6e554178a94fd993111f2ae64cb708cb0899d7b5182024e70d5c468164a8bec"
    }

    private lateinit var multistream: Multistream
    private lateinit var cachingReader: EthereumCachingReader
    private lateinit var logReader: Reader<BlockId, EthereumDirectReader.Result<List<TransactionLogJson>>>
    private lateinit var head: Head
    private lateinit var producer: SharedLogsProducer
    private lateinit var blockUpdatesFlux: Flux<BlockContainer>

    // Sink for controlling block flow in tests
    private lateinit var blockUpdatesSink: Sinks.Many<BlockContainer>

    @BeforeEach
    fun setup() {
        multistream = mock(Multistream::class.java)
        cachingReader = mock(EthereumCachingReader::class.java)
        logReader = mock(Reader::class.java) as Reader<BlockId, EthereumDirectReader.Result<List<TransactionLogJson>>>
        head = mock(Head::class.java)

        // Create controlled block flow
        blockUpdatesSink = Sinks.many().multicast().onBackpressureBuffer()
        blockUpdatesFlux = blockUpdatesSink.asFlux()

        // Set up mocks - simplified approach
        `when`(multistream.getCachingReader()).thenReturn(cachingReader)
        `when`(multistream.getChain()).thenReturn(Chain.ETHEREUM__MAINNET)
        `when`(multistream.getHead(anyOrNull())).thenReturn(head)

        `when`(cachingReader.logsByHash()).thenReturn(logReader)
        val emptyResult = EthereumDirectReader.Result(emptyList<TransactionLogJson>(), emptyList())
        `when`(logReader.read(BlockId.from("0x0"))).thenReturn(Mono.just(emptyResult))

        `when`(head.getFlux()).thenReturn(blockUpdatesFlux)

        producer = SharedLogsProducer(multistream, Schedulers.immediate())
    }

    @AfterEach
    fun tearDown() {
        blockUpdatesSink.tryEmitComplete()
    }

    private fun createLogMessage(
        address: String = TEST_ADDRESS,
        topics: List<String> = emptyList(),
        removed: Boolean = false,
    ): LogMessage {
        return LogMessage(
            address = Address.from(address),
            blockHash = BlockHash.from(TEST_BLOCK_HASH),
            blockNumber = 1L,
            data = HexData.empty(),
            logIndex = 1L,
            topics = topics.map { Hex32.from(it) },
            transactionHash = TransactionId.from(TEST_TX_HASH),
            transactionIndex = 1L,
            removed = removed,
            upstreamId = "test-upstream",
        )
    }

    private fun createMatcher(): Selector.Matcher {
        val matcher = mock(Selector.Matcher::class.java)
        `when`(matcher.describeInternal()).thenReturn("test-matcher")
        return matcher
    }

    @Test
    fun `should start shared stream on first subscription`() {
        // Create real logs for block
        val testLog = TransactionLogJson().apply {
            address = Address.from(TEST_ADDRESS)
            blockHash = BlockHash.from(TEST_BLOCK_HASH)
            blockNumber = 1L
            data = HexData.empty()
            logIndex = 1L
            topics = emptyList()
            transactionHash = TransactionId.from(TEST_TX_HASH)
            transactionIndex = 1L
        }

        val logsResult = EthereumDirectReader.Result(listOf(testLog), emptyList())
        `when`(logReader.read(BlockId.from(testLog.blockHash))).thenReturn(Mono.just(logsResult))

        val matcher = createMatcher()
        `when`(matcher.matches(anyOrNull())).thenReturn(true)

        val filterAddresses = listOf(Address.from(TEST_ADDRESS))
        val subscription = producer.subscribe(filterAddresses, emptyList(), matcher)

        val block = BlockContainer(
            height = 1L,
            hash = BlockId.from(TEST_BLOCK_HASH),
            difficulty = java.math.BigInteger.ZERO,
            timestamp = java.time.Instant.now(),
            full = false,
            json = byteArrayOf(),
            parsed = null,
            parentHash = null,
            transactions = emptyList(),
            upstreamId = "test-upstream",
        )

        blockUpdatesSink.tryEmitNext(block)

        // Verify that we received logs
        StepVerifier.create(subscription.take(1))
            .expectNextMatches { logMessage ->
                logMessage.address == testLog.address &&
                    logMessage.blockHash == testLog.blockHash &&
                    logMessage.blockNumber == testLog.blockNumber
            }
            .expectComplete()
            .verify(Duration.ofSeconds(5))
    }

    @Test
    fun `should reuse shared stream for multiple subscriptions`() {
        // Given
        val matcher = createMatcher()
        val addresses = emptyList<Address>()
        val topics = emptyList<List<Hex32>?>()

        // When
        producer.subscribe(addresses, topics, matcher)

        val sharedStreamsField = SharedLogsProducer::class.java.getDeclaredField("sharedStreams")
        sharedStreamsField.isAccessible = true
        val sharedStreams = sharedStreamsField.get(producer) as Map<*, *>

        val logsSinksField = SharedLogsProducer::class.java.getDeclaredField("logsSinks")
        logsSinksField.isAccessible = true
        val logsSinks = logsSinksField.get(producer) as Map<*, *>

        val matcherKey = matcher.describeInternal()
        val firstStream = sharedStreams[matcherKey]
        val firstSink = logsSinks[matcherKey]

        producer.subscribe(addresses, topics, matcher)

        // Then - verify that the same stream is used for the same matcher
        assertEquals(firstStream, sharedStreams[matcherKey])
        assertEquals(firstSink, logsSinks[matcherKey])
        assertEquals(1, sharedStreams.size)
        assertEquals(1, logsSinks.size)
    }

    @Test
    fun `should filter logs by address`() {
        val targetAddress = Address.from(TEST_ADDRESS)
        val otherAddress = Address.from(OTHER_ADDRESS)

        // Create log with correct address
        val matchingLog = TransactionLogJson().apply {
            address = targetAddress
            blockHash = BlockHash.from(TEST_BLOCK_HASH)
            blockNumber = 1L
            data = HexData.empty()
            logIndex = 1L
            topics = emptyList()
            transactionHash = TransactionId.from(TEST_TX_HASH)
            transactionIndex = 1L
        }

        // Create log with incorrect address
        val nonMatchingLog = TransactionLogJson().apply {
            address = otherAddress
            blockHash = BlockHash.from(TEST_BLOCK_HASH)
            blockNumber = 1L
            data = HexData.empty()
            logIndex = 2L
            topics = emptyList()
            transactionHash = TransactionId.from(OTHER_TX_HASH)
            transactionIndex = 2L
        }

        val logsResult = EthereumDirectReader.Result(listOf(matchingLog, nonMatchingLog), emptyList())
        `when`(logReader.read(BlockId.from(matchingLog.blockHash))).thenReturn(Mono.just(logsResult))

        val matcher = createMatcher()
        `when`(matcher.matches(anyOrNull())).thenReturn(true)

        val filterAddresses = listOf(targetAddress)
        val subscription = producer.subscribe(filterAddresses, emptyList(), matcher)

        val block = BlockContainer(
            height = 1L,
            hash = BlockId.from(TEST_BLOCK_HASH),
            difficulty = java.math.BigInteger.ZERO,
            timestamp = java.time.Instant.now(),
            full = false,
            json = byteArrayOf(),
            parsed = null,
            parentHash = null,
            transactions = emptyList(),
            upstreamId = "test-upstream",
        )

        blockUpdatesSink.tryEmitNext(block)

        // Verify that we only received log with correct address
        StepVerifier.create(subscription.take(1))
            .expectNextMatches { logMessage ->
                logMessage.address == targetAddress &&
                    logMessage.logIndex == 1L
            }
            .expectComplete()
            .verify(Duration.ofSeconds(5))
    }

    // ========== Unit tests for LogsSubscription ==========

    private fun createLogsSubscription(
        id: String,
        addresses: List<Address> = emptyList(),
        topics: List<List<Hex32>?> = emptyList(),
    ): Any {
        val logsSubscriptionClass = SharedLogsProducer::class.java.declaredClasses
            .first { it.simpleName == "LogsSubscription" }
        val constructor = logsSubscriptionClass.declaredConstructors[0]
        constructor.isAccessible = true

        return constructor.newInstance(
            id,
            addresses,
            topics,
            mock(Selector.Matcher::class.java),
        )
    }

    private fun matches(subscription: Any, logMessage: LogMessage): Boolean {
        val logsSubscriptionClass = SharedLogsProducer::class.java.declaredClasses
            .first { it.simpleName == "LogsSubscription" }
        val matchesMethod = logsSubscriptionClass.getDeclaredMethod("matches", LogMessage::class.java)
        matchesMethod.isAccessible = true
        return matchesMethod.invoke(subscription, logMessage) as Boolean
    }

    @Test
    fun `LogsSubscription matches logs by address`() {
        val subscription = createLogsSubscription(
            "test-id",
            listOf(Address.from(TEST_ADDRESS)),
        )

        val matchingLog = createLogMessage(TEST_ADDRESS)
        val nonMatchingLog = createLogMessage(OTHER_ADDRESS)

        assertTrue(matches(subscription, matchingLog))
        assertFalse(matches(subscription, nonMatchingLog))
    }

    @Test
    fun `LogsSubscription matches all addresses when empty list`() {
        val subscription = createLogsSubscription("test-id")

        val log1 = createLogMessage(TEST_ADDRESS)
        val log2 = createLogMessage(OTHER_ADDRESS)

        assertTrue(matches(subscription, log1))
        assertTrue(matches(subscription, log2))
    }

    @Test
    fun `LogsSubscription matches logs by topics`() {
        val topic1 = "0x1234567890123456789012345678901234567890123456789012345678901234"
        val topic2 = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"

        val subscription = createLogsSubscription(
            "test-id",
            emptyList(),
            listOf(listOf(Hex32.from(topic1)), null, listOf(Hex32.from(topic2))),
        )

        val matchingLog = createLogMessage(TEST_ADDRESS, listOf(topic1, topic1, topic2))
        val nonMatchingLog = createLogMessage(TEST_ADDRESS, listOf(topic2, topic1, topic2))
        val shortTopicsLog = createLogMessage(TEST_ADDRESS, listOf(topic1))

        assertTrue(matches(subscription, matchingLog))
        assertFalse(matches(subscription, nonMatchingLog))
        assertFalse(matches(subscription, shortTopicsLog))
    }

    @Test
    fun `LogsSubscription matches when topic filter is null (wildcard)`() {
        val topic1 = "0x1234567890123456789012345678901234567890123456789012345678901234"
        val topic2 = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"

        val subscription = createLogsSubscription(
            "test-id",
            emptyList(),
            listOf(null, listOf(Hex32.from(topic2))),
        )

        val log1 = createLogMessage(TEST_ADDRESS, listOf(topic1, topic2))
        val log2 = createLogMessage(TEST_ADDRESS, listOf(topic2, topic2))

        assertTrue(matches(subscription, log1))
        assertTrue(matches(subscription, log2))
    }

    @Test
    fun `LogsSubscription matches all topics when empty list`() {
        val subscription = createLogsSubscription("test-id")

        val topic1 = "0x1234567890123456789012345678901234567890123456789012345678901234"
        val logWithTopics = createLogMessage(TEST_ADDRESS, listOf(topic1))
        val logWithoutTopics = createLogMessage(TEST_ADDRESS, emptyList())

        assertTrue(matches(subscription, logWithTopics))
        assertTrue(matches(subscription, logWithoutTopics))
    }

    @Test
    fun `LogsSubscription combines address and topics matching`() {
        val targetAddress = TEST_ADDRESS
        val topic1 = "0x1234567890123456789012345678901234567890123456789012345678901234"

        val subscription = createLogsSubscription(
            "test-id",
            listOf(Address.from(targetAddress)),
            listOf(listOf(Hex32.from(topic1))),
        )

        val matchingLog = createLogMessage(targetAddress, listOf(topic1))
        val wrongAddress = createLogMessage(OTHER_ADDRESS, listOf(topic1))
        val wrongTopic = createLogMessage(targetAddress, listOf("0xabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcdefabcd"))

        assertTrue(matches(subscription, matchingLog))
        assertFalse(matches(subscription, wrongAddress))
        assertFalse(matches(subscription, wrongTopic))
    }

    @Test
    fun `LogsSubscription filters by multiple topic positions`() {
        val topicA = "0x952ba7f163c4a11628f55a4df523b3efddf252ad1be2c89b69c2b068fc378daa"
        val topicB = "0x00000000000000000000000088e6a0c2ddd26feeb64f039a2c41296fcb3f5640"

        val subscription = createLogsSubscription(
            "test-id",
            listOf(Address.from("0x63bc4a36c66c64acb3d695298d492e8c1d909d3f")),
            listOf(
                listOf(Hex32.from(topicA)), // position 0 must be topicA
                listOf(Hex32.from(topicB)), // position 1 must be topicB
            ),
        )

        val matchingLog = createLogMessage(
            "0x63bc4a36c66c64acb3d695298d492e8c1d909d3f",
            listOf(topicA, topicB),
        )

        val matchingLogWithExtra = createLogMessage(
            "0x63bc4a36c66c64acb3d695298d492e8c1d909d3f",
            listOf(topicA, topicB, "0x00000000000000000000000088e6a0c2ddd26feeb64f039a2c41296fcb3f5641"),
        )

        val nonMatchingLog = createLogMessage(
            "0x63bc4a36c66c64acb3d695298d492e8c1d909d3f",
            listOf(topicA),
        )

        assertTrue(matches(subscription, matchingLog))
        assertTrue(matches(subscription, matchingLogWithExtra))
        assertFalse(matches(subscription, nonMatchingLog))
    }

    @Test
    fun `LogsSubscription handles OR logic in topic positions`() {
        val topicA = "0x952ba7f163c4a11628f55a4df523b3efddf252ad1be2c89b69c2b068fc378daa"
        val topicB = "0x00000000000000000000000088e6a0c2ddd26feeb64f039a2c41296fcb3f5640"
        val topicC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

        val subscription = createLogsSubscription(
            "test-id",
            emptyList(),
            listOf(
                listOf(Hex32.from(topicA), Hex32.from(topicB)), // position 0: topicA OR topicB
                null, // position 1: any topic
            ),
        )

        val logWithTopicA = createLogMessage(
            "0x63bc4a36c66c64acb3d695298d492e8c1d909d3f",
            listOf(topicA, topicB),
        )

        val logWithTopicB = createLogMessage(
            "0x63bc4a36c66c64acb3d695298d492e8c1d909d3f",
            listOf(topicB, topicA),
        )

        val logWithTopicC = createLogMessage(
            "0x298d492e8c1d909d3f63bc4a36c66c64acb3d695",
            listOf(topicC),
        )

        assertTrue(matches(subscription, logWithTopicA))
        assertTrue(matches(subscription, logWithTopicB))
        assertFalse(matches(subscription, logWithTopicC))
    }

    @Test fun `LogsSubscription matches all logs when no filters applied`() {
        val subscription = createLogsSubscription("test-id")

        val log1 = createLogMessage(
            "0x298d492e8c1d909d3f63bc4a36c66c64acb3d695",
            listOf("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"),
        )
        val log2 = createLogMessage(
            "0x63bc4a36c66c64acb3d695298d492e8c1d909d3f",
            listOf("0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"),
        )
        val log3 = createLogMessage(
            "0x4a36c66c64acb3d695298d492e8c1d909d3f63bc",
            listOf("0x952ba7f163c4a11628f55a4df523b3efddf252ad1be2c89b69c2b068fc378daa"),
        )

        assertTrue(matches(subscription, log1))
        assertTrue(matches(subscription, log2))
        assertTrue(matches(subscription, log3))
    }
}
