package io.emeraldpay.dshackle.config

import io.emeraldpay.dshackle.upstream.generic.connectors.GenericConnectorFactory.ConnectorMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.util.stream.Stream

internal class UpstreamsConfigTest {
    companion object {
        @JvmStatic
        fun data(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    UpstreamsConfig.RpcConnection(),
                    ConnectorMode.RPC_ONLY,
                ),
                Arguments.of(
                    UpstreamsConfig.RpcConnection()
                        .apply {
                            ws = UpstreamsConfig.WsEndpoint(URI("ws://localhost:8546"))
                        },
                    ConnectorMode.WS_ONLY,
                ),
                Arguments.of(
                    UpstreamsConfig.RpcConnection()
                        .apply {
                            ws = UpstreamsConfig.WsEndpoint(URI("ws://localhost:8546"))
                        },
                    ConnectorMode.WS_ONLY,
                ),
                Arguments.of(
                    UpstreamsConfig.RpcConnection()
                        .apply {
                            connectorMode = "RPC_REQUESTS_WITH_WS_HEAD"
                            ws = UpstreamsConfig.WsEndpoint(URI("ws://localhost:8546"))
                        },
                    ConnectorMode.RPC_REQUESTS_WITH_WS_HEAD,
                ),
                Arguments.of(
                    UpstreamsConfig.RpcConnection()
                        .apply {
                            connectorMode = "RPC_REQUESTS_WITH_MIXED_HEAD"
                            ws = UpstreamsConfig.WsEndpoint(URI("ws://localhost:8546"))
                        },
                    ConnectorMode.RPC_REQUESTS_WITH_MIXED_HEAD,
                ),
                Arguments.of(
                    UpstreamsConfig.RpcConnection()
                        .apply {
                            rpc = UpstreamsConfig.HttpEndpoint(URI("http://localhost:8546"))
                            ws = UpstreamsConfig.WsEndpoint(URI("ws://localhost:8546"))
                        },
                    ConnectorMode.RPC_REQUESTS_WITH_WS_HEAD,
                ),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    fun testKeepForwarded(input: UpstreamsConfig.RpcConnection, expected: ConnectorMode) {
        assertEquals(expected, input.resolveMode())
    }
}
