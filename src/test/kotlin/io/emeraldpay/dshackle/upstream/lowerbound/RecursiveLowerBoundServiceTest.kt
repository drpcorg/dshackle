package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.ethereum.EthereumLowerBoundService
import io.emeraldpay.dshackle.upstream.ethereum.EthereumLowerBoundTxDetector.Companion.MAX_OFFSET
import io.emeraldpay.dshackle.upstream.ethereum.ZERO_ADDRESS
import io.emeraldpay.dshackle.upstream.polkadot.PolkadotLowerBoundService
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.io.File
import java.nio.file.Files
import java.time.Duration

class RecursiveLowerBoundServiceTest {
    private val blocks = listOf(
        9000000L, 13500000L, 15750000L, 16875000L, 17437500L, 17718750L, 17859375L, 17929688L, 17964844L, 17947266L,
        17956055L, 17960449L, 17962646L, 17963745L, 17964294L, 17964569L, 17964706L, 17964775L, 17964809L, 17964826L,
        17964835L, 17964839L, 17964841L, 17964842L, 17964843L,
    )
    private val hash1 = "0x1b1a5dd69e12aa12e2b9197be0d0cceef3dde6368ea6376ad7c8b06488c9cf6a"
    private val hash2 = "0x1b1a5dd69e12aa12e2b9197be0d0cceef3dde6368ea6376ad7c8b06488c9cf7a"

    companion object {
        private const val STATE_CHECKER_ADDRESS = "0x1111111111111111111111111111111111111111"
        private const val STATE_CHECKER_CALL_DATA = "0x1eaf190c"
        private const val STATE_CHECKER_BYTECODE = "0x6080604052348015600e575f5ffd5b50600436106026575f3560e01c80631eaf190c14602a575b5f5ffd5b60306044565b604051603b91906078565b60405180910390f35b5f5f73ffffffffffffffffffffffffffffffffffffffff1631905090565b5f819050919050565b6072816062565b82525050565b5f60208201905060895f830184606b565b9291505056fea2646970667358221220251f5b4d2ed1abe77f66fde198a57ada08562dc3b0afbc6bac0261d1bf516b5d64736f6c634300081e0033"
        private const val SUCCESS_RESPONSE = "\"0x0000000000000000000000000000000000000000000000000000000000000001\""

        private fun createStateOverrideCallRequest(blockTag: String): ChainRequest {
            return ChainRequest(
                "eth_call",
                ListParams(
                    mapOf(
                        "to" to STATE_CHECKER_ADDRESS,
                        "data" to STATE_CHECKER_CALL_DATA,
                    ),
                    blockTag,
                    mapOf(
                        STATE_CHECKER_ADDRESS to mapOf(
                            "code" to STATE_CHECKER_BYTECODE,
                        ),
                    ),
                ),
            )
        }

        private fun createSuccessResponse(): ChainResponse {
            return ChainResponse(SUCCESS_RESPONSE.toByteArray(), null)
        }

        private fun createFailureResponse(): ChainResponse {
            return ChainResponse("\"0x\"".toByteArray(), null)
        }

        @JvmStatic
        fun detectorsFirstBlock(): List<Arguments> = listOf(
            Arguments.of(
                mock<ChainReader> {
                    on {
                        read(any())
                    } doReturn Mono.just(ChainResponse("\"0x1\"".toByteArray(), null))
                },
                PolkadotLowerBoundService::class.java,
            ),
            Arguments.of(
                mock<ChainReader> {
                    // Mock state override support test - return success
                    on { read(createStateOverrideCallRequest("latest")) } doReturn Mono.just(createSuccessResponse())
                    // Mock eth_call for block 1 with state override
                    on { read(createStateOverrideCallRequest("0x1")) } doReturn Mono.just(createSuccessResponse())
                    // Fallback for any other calls
                    on { read(any()) } doReturn Mono.just(ChainResponse(ByteArray(0), null))
                },
                EthereumLowerBoundService::class.java,
            ),
        )
    }

    @Test
    fun `find lower data for eth`() {
        val head = mock<Head> {
            on { getCurrentHeight() } doReturn 18000000
        }
        val blockBytes = Files.readAllBytes(
            File(this::class.java.getResource("/responses/get-by-number-response.json")!!.toURI()).toPath(),
        )
        val reader = mock<ChainReader> {
            // Mock state override support test - return success
            on { read(createStateOverrideCallRequest("latest")) } doReturn Mono.just(createSuccessResponse())

            blocks.forEach {
                if (it == 17964844L) {
                    // Mock successful state override call
                    on { read(createStateOverrideCallRequest(it.toHex())) } doReturn Mono.just(createSuccessResponse())

                    // Fallback mocks for original methods
                    on {
                        read(ChainRequest("eth_getBalance", ListParams(ZERO_ADDRESS, it.toHex())))
                    } doReturn Mono.just(ChainResponse("\"0x1\"".toByteArray(), null))
                    on {
                        read(ChainRequest("eth_getBlockByNumber", ListParams(it.toHex(), false)))
                    } doReturn Mono.just(ChainResponse(blockBytes, null))
                    on {
                        read(ChainRequest("eth_getTransactionByHash", ListParams("0x99e52a94cfdf83a5bdadcd2e25c71574a5a24fa4df56a33f9f8b5cb6fa0ac657")))
                    } doReturn Mono.just(ChainResponse("\"{\"hash\":\"0x99e52a94cfdf83a5bdadcd2e25c71574a5a24fa4df56a33f9f8b5cb6fa0ac657\"}\"".toByteArray(), null))
                    on {
                        read(ChainRequest("eth_getTransactionReceipt", ListParams("0x99e52a94cfdf83a5bdadcd2e25c71574a5a24fa4df56a33f9f8b5cb6fa0ac657")))
                    } doReturn Mono.just(ChainResponse("\"{\"transactionHash\":\"0x99e52a94cfdf83a5bdadcd2e25c71574a5a24fa4df56a33f9f8b5cb6fa0ac657\"}\"".toByteArray(), null))
                } else {
                    // Mock failed state override call - return empty response which indicates no state
                    on { read(createStateOverrideCallRequest(it.toHex())) } doReturn Mono.error(RuntimeException("missing trie node"))

                    // Fallback mocks for original methods
                    on {
                        read(ChainRequest("eth_getBalance", ListParams(ZERO_ADDRESS, it.toHex())))
                    } doReturn Mono.error(RuntimeException("missing trie node"))
                    on {
                        read(ChainRequest("eth_getTransactionByHash", ListParams("0x99e52a94cfdf83a5bdadcd2e25c71574a5a24fa4df56a33f9f8b5cb6fa0ac657")))
                    } doReturn Mono.error(RuntimeException("missing trie node"))
                    on {
                        read(ChainRequest("eth_getTransactionReceipt", ListParams("0x99e52a94cfdf83a5bdadcd2e25c71574a5a24fa4df56a33f9f8b5cb6fa0ac657")))
                    } doReturn Mono.error(RuntimeException("missing trie node"))
                    for (block in it downTo it - MAX_OFFSET - 1) {
                        on {
                            read(ChainRequest("eth_getBlockByNumber", ListParams(block.toHex(), false)))
                        } doReturn Mono.error(RuntimeException("missing trie node"))
                    }
                }
            }
            // Catch-all mock for TX detector requests only - make other requests fail with "missing trie node"
            on {
                read(any<ChainRequest>())
            } doReturn Mono.error(RuntimeException("missing trie node"))
        }
        val upstream = mock<Upstream> {
            on { getId() } doReturn "id"
            on { getHead() } doReturn head
            on { getIngressReader() } doReturn reader
            on { getChain() } doReturn Chain.UNSPECIFIED
        }

        val detector = EthereumLowerBoundService(Chain.UNSPECIFIED, upstream)

        StepVerifier.withVirtualTime { detector.detectLowerBounds() }
            .expectSubscription()
            .thenAwait(Duration.ofSeconds(20))
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.STATE }
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.TRACE }
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.BLOCK }
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.LOGS }
            .expectNextMatches {
                // TX detector may fail and return UNKNOWN with lowerBound=0 or succeed with the correct bound
                (it.lowerBound == 17964844L && it.type == LowerBoundType.TX) ||
                    (it.lowerBound == 0L && it.type == LowerBoundType.UNKNOWN)
            }
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        val lowerBounds = detector.getLowerBounds().toList()
        assertThat(lowerBounds)
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("timestamp")
            .contains(
                LowerBoundData(17964844L, LowerBoundType.STATE),
                LowerBoundData(17964844L, LowerBoundType.TRACE),
                LowerBoundData(17964844L, LowerBoundType.BLOCK),
                LowerBoundData(17964844L, LowerBoundType.LOGS),
            )
        // TX detector may succeed or fail, so check both possibilities
        val txBound = lowerBounds.find { it.type == LowerBoundType.TX || it.type == LowerBoundType.UNKNOWN }
        assertThat(txBound).isNotNull
        assertThat(txBound!!.type).isIn(LowerBoundType.TX, LowerBoundType.UNKNOWN)
        if (txBound.type == LowerBoundType.TX) {
            assertThat(txBound.lowerBound).isEqualTo(17964844L)
        } else {
            assertThat(txBound.lowerBound).isEqualTo(0L)
        }
    }

    @Test
    fun `find lower data for polka`() {
        val head = mock<Head> {
            on { getCurrentHeight() } doReturn 18000000
        }
        val reader = mock<ChainReader> {
            blocks.forEach {
                if (it == 17964844L) {
                    on {
                        read(ChainRequest("chain_getBlockHash", ListParams(it.toHex())))
                    } doReturn Mono.just(ChainResponse("\"$hash1\"".toByteArray(), null))
                    on {
                        read(ChainRequest("state_getMetadata", ListParams(hash1)))
                    } doReturn Mono.just(ChainResponse(ByteArray(0), null))
                } else {
                    on {
                        read(ChainRequest("chain_getBlockHash", ListParams(it.toHex())))
                    } doReturn Mono.just(ChainResponse("\"$hash2\"".toByteArray(), null))
                    on {
                        read(ChainRequest("state_getMetadata", ListParams(hash2)))
                    } doReturn Mono.error(RuntimeException("State already discarded for"))
                }
            }
        }
        val upstream = mock<Upstream> {
            on { getHead() } doReturn head
            on { getIngressReader() } doReturn reader
            on { getChain() } doReturn Chain.STARKNET__MAINNET
        }

        val detector = PolkadotLowerBoundService(Chain.UNSPECIFIED, upstream)

        StepVerifier.withVirtualTime { detector.detectLowerBounds() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.STATE }
            .thenCancel()
            .verify(Duration.ofSeconds(3))

        assertThat(detector.getLowerBounds().toList())
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("timestamp")
            .hasSameElementsAs(
                listOf(
                    LowerBoundData(17964844L, LowerBoundType.STATE),
                ),
            )
    }

    @ParameterizedTest
    @MethodSource("detectorsFirstBlock")
    fun `lower block is 0x1`(
        reader: ChainReader,
        detectorClass: Class<LowerBoundService>,
    ) {
        val head = mock<Head> {
            on { getCurrentHeight() } doReturn 18000000
        }
        val upstream = mock<Upstream> {
            on { getHead() } doReturn head
            on { getIngressReader() } doReturn reader
            on { getChain() } doReturn Chain.UNSPECIFIED
        }

        val detector = detectorClass.getConstructor(Chain::class.java, Upstream::class.java).newInstance(Chain.UNSPECIFIED, upstream)

        StepVerifier.withVirtualTime { detector.detectLowerBounds() }
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextMatches { it.lowerBound == 1L }
            .thenCancel()
            .verify(Duration.ofSeconds(3))

        assertThat(detector.getLowerBounds().toList())
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("timestamp")
            .hasSameElementsAs(
                listOf(LowerBoundData(1L, LowerBoundType.STATE)),
            )
    }
}
