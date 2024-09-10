package io.emeraldpay.dshackle.upstream.ethereum

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Mono

class BasicEthUpstreamRpcMethodsDetectorTest {
    @Test
    fun `rpc_modules without web3 and eth_getBlockReceipts`() {
        val reader =
            mock<ChainReader> {
                on {
                    read(ChainRequest("rpc_modules", ListParams()))
                } doReturn
                    Mono.just(
                        ChainResponse(
                            """{"net": "1.0","debug": "1.0","txpool": "1.0","drpc": "1.0","erigon": "1.0","eth": "1.0","trace": "1.0"}"""
                                .toByteArray(),
                            null,
                        ),
                    )
                on {
                    read(ChainRequest("eth_getBlockReceipts", ListParams("latest")))
                } doReturn
                    Mono.just(
                        ChainResponse(
                            """[{"blockHash": "0xd12897f54acaa79f4824aa4f8e1d0f045b5568f5b942073555e9977202c5c474","blockNumber": "0x13c1108"}]"""
                                .toByteArray(),
                            null,
                        ),
                    )
            }

        val upstream =
            mock<Upstream> {
                on { getIngressReader() } doReturn reader
                on { getChain() } doReturn Chain.ETHEREUM__MAINNET
            }
        val detector = BasicEthUpstreamRpcMethodsDetector(upstream)
        Assertions.assertThat(detector.detectRpcMethods().block()).apply {
            isNotNull()
            hasSize(2)
            containsEntry("web3_clientVersion", false)
            containsEntry("eth_getBlockReceipts", true)
        }
    }

    @Test
    fun `rpc_modules disabled and eth_getBlockReceipts`() {
        val reader =
            mock<ChainReader> {
                on {
                    read(ChainRequest("rpc_modules", ListParams()))
                } doReturn
                    Mono.just(
                        ChainResponse(
                            null,
                            ChainCallError(32601, "the method rpc_modules does not exist/is not available"),
                        ),
                    )
                on {
                    read(ChainRequest("eth_getBlockReceipts", ListParams("latest")))
                } doReturn
                    Mono.just(
                        ChainResponse(
                            """[{"blockHash": "0xd12897f54acaa79f4824aa4f8e1d0f045b5568f5b942073555e9977202c5c474","blockNumber": "0x13c1108"}]"""
                                .toByteArray(),
                            null,
                        ),
                    )
            }

        val upstream =
            mock<Upstream> {
                on { getIngressReader() } doReturn reader
                on { getChain() } doReturn Chain.ETHEREUM__MAINNET
            }
        val detector = BasicEthUpstreamRpcMethodsDetector(upstream)
        Assertions.assertThat(detector.detectRpcMethods().block()).apply {
            isNotNull()
            hasSize(1)
            containsEntry("eth_getBlockReceipts", true)
        }
    }
}
