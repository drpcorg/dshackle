package io.emeraldpay.dshackle.reader

import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.MaximumValueQuorum
import io.emeraldpay.dshackle.upstream.Multistream
import io.emeraldpay.dshackle.upstream.Selector
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.mock

class RequestReaderFactoryTest {
    private val defaultFactory = RequestReaderFactory.Default()

    @ParameterizedTest
    @MethodSource("data")
    fun `create BroadcastReader for MaximumValueQuorum and BroadcastQuorum`(
        readerData: RequestReaderFactory.ReaderData,
    ) {
        val reader = defaultFactory.create(readerData)

        assertTrue(reader is BroadcastReader)
    }

    companion object {
        private val ms = mock<Multistream>()

        @JvmStatic
        fun data(): List<Arguments> {
            return listOf(
                Arguments.of(
                    RequestReaderFactory.ReaderData(
                        ms,
                        Selector.UpstreamFilter(Selector.empty),
                        MaximumValueQuorum(),
                        null,
                    ),
                ),
                Arguments.of(
                    RequestReaderFactory.ReaderData(
                        ms,
                        Selector.UpstreamFilter(Selector.empty),
                        BroadcastQuorum(),
                        null,
                    ),
                ),
            )
        }
    }
}
