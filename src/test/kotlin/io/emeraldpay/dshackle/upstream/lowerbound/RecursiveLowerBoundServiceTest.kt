package io.emeraldpay.dshackle.upstream.lowerbound

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.Global
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
        private const val STATE_CHECKER_ADDRESS = "0x0000000000000000000000000000000000000001"
        private const val STATE_CHECKER_CALL_DATA = "0x26121ff0"
        private const val STATE_CHECKER_BYTECODE = "0x608060405234801561001057600080fd5b506004361061002b5760003560e01c806326121ff014610030575b600080fd5b610038610048565b6040516100459190610073565b60405180910390f35b6000602a905090565b6000819050919050565b61006d81610052565b82525050565b60006020820190506100886000830184610064565b9291505056fea2646970667358221220c7b6d4bc2c1b1d5b5c2e8e2a5b5c5d5e5f5a5b5c5d5e5f5a5b5c5d5e5f5a5b5c64736f6c63430008130033"
        private const val SUCCESS_RESPONSE = "\"0x000000000000000000000000000000000000000000000000000000000000002a\""

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
                    } doReturn Mono.just(ChainResponse(ByteArray(0), null))
                    on {
                        read(ChainRequest("eth_getBlockByNumber", ListParams(it.toHex(), false)))
                    } doReturn Mono.just(ChainResponse(blockBytes, null))
                    on {
                        read(ChainRequest("eth_getTransactionByHash", ListParams("0x99e52a94cfdf83a5bdadcd2e25c71574a5a24fa4df56a33f9f8b5cb6fa0ac657")))
                    } doReturn Mono.just(ChainResponse(ByteArray(0), null))
                } else {
                    // Mock failed state override call
                    on { read(createStateOverrideCallRequest(it.toHex())) } doReturn Mono.error(RuntimeException("missing trie node"))

                    // Fallback mocks for original methods
                    on {
                        read(ChainRequest("eth_getBalance", ListParams(ZERO_ADDRESS, it.toHex())))
                    } doReturn Mono.error(RuntimeException("missing trie node"))
                    for (block in it downTo it - MAX_OFFSET - 1) {
                        on {
                            read(ChainRequest("eth_getBlockByNumber", ListParams(block.toHex(), false)))
                        } doReturn Mono.just(ChainResponse(Global.nullValue, null))
                    }
                }
            }
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
            .expectNoEvent(Duration.ofSeconds(15))
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.STATE }
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.TRACE }
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.BLOCK }
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.LOGS }
            .expectNextMatches { it.lowerBound == 17964844L && it.type == LowerBoundType.TX }
            .thenCancel()
            .verify(Duration.ofSeconds(3))

        assertThat(detector.getLowerBounds().toList())
            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("timestamp")
            .hasSameElementsAs(
                listOf(
                    LowerBoundData(17964844L, LowerBoundType.STATE),
                    LowerBoundData(17964844L, LowerBoundType.TRACE),
                    LowerBoundData(17964844L, LowerBoundType.BLOCK),
                    LowerBoundData(17964844L, LowerBoundType.LOGS),
                    LowerBoundData(17964844L, LowerBoundType.TX),
                ),
            )
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
