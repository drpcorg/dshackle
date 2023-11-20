package io.emeraldpay.dshackle.rpc

import io.emeraldpay.dshackle.quorum.CallQuorum
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import io.emeraldpay.dshackle.upstream.rpcclient.JsonRpcError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock

class ErrorProcessingTest {

    @Test
    fun `fix nethermind eth_call reverted error`() {
        val result = result("eth_call")
        val error = result.error!!
        val corrector = ErrorCorrector(listOf(NethermindEthCallRevertedErrorProcessor()))

        val fixedError = corrector.correctError(result)


        assertEquals(
            NativeCall.CallError(3, error.message, error.upstreamError, "0x0111", error.upstreamId),
            fixedError
        )
    }

    @Test
    fun `return the same error if there is no suitable processor`() {
        val result = result("eth_getBlockByNumber")
        val error = result.error!!
        val corrector = ErrorCorrector(listOf(NethermindEthCallRevertedErrorProcessor()))

        val fixedError = corrector.correctError(result)


        assertEquals(
            NativeCall.CallError(55, error.message, error.upstreamError, "Reverted 0x0111", error.upstreamId),
            fixedError
        )
    }

    @Test
    fun `throw an exception if there is no error in result`() {
        val result = NativeCall.CallResult(
            1,
            2,
            null,
            null,
            null,
            null,
        )
        assertThrows<IllegalStateException>("No error to correct") {
            val corrector = ErrorCorrector(listOf(NethermindEthCallRevertedErrorProcessor()))
            corrector.correctError(result)
        }
    }

    private fun result(method: String) =
        NativeCall.CallResult(
            1,
            2,
            null,
            NativeCall.CallError(
                55,
                "reverted",
                JsonRpcError(1, "errMessage", null),
                "Reverted 0x0111",
                "upId"
            ),
            null,
            NativeCall.ValidCallContext(
                1,
                2,
                mock<Multistream>(),
                Selector.empty,
                mock<CallQuorum>(),
                NativeCall.ParsedCallDetails(method, emptyList()),
                "req",
                1
            )
        )
}
