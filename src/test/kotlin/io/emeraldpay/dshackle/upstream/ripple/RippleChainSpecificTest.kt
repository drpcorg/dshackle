package io.emeraldpay.dshackle.upstream.ripple

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.UpstreamAvailability
import io.emeraldpay.dshackle.upstream.ValidateUpstreamSettingsResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Instant

// ledger_closed response format
val ledgerClosedResponse = """
{
    "ledger_hash": "17ACB57A0F73B5160713E81FE72B2AC9F6064541004E272BD09F257D57C30C02",
    "ledger_index": 6643099
}
""".trimIndent()

// ledgerClosed WebSocket subscription event
val ledgerClosedEvent = """
{
    "type": "ledgerClosed",
    "fee_base": 10,
    "fee_ref": 10,
    "ledger_hash": "17ACB57A0F73B5160713E81FE72B2AC9F6064541004E272BD09F257D57C30C02",
    "ledger_index": 6643099,
    "ledger_time": 780804221,
    "reserve_base": 10000000,
    "reserve_inc": 2000000,
    "txn_count": 5,
    "validated_ledgers": "6643000-6643099"
}
""".trimIndent()

// server_state response for validation
val serverStateOk = """
{
    "state": {
        "build_version": "1.12.0",
        "complete_ledgers": "32570-6643099",
        "io_latency_ms": 1,
        "jq_trans_overflow": "0",
        "last_close": {
            "converge_time": 2000,
            "proposers": 34
        },
        "load_base": 256,
        "load_factor": 256,
        "load_factor_fee_escalation": 256,
        "load_factor_fee_queue": 256,
        "load_factor_fee_reference": 256,
        "load_factor_server": 256,
        "network_id": 0,
        "peers": 21,
        "ports": [],
        "server_state": "full",
        "time": "2024-Jan-01 00:00:00",
        "uptime": 123456,
        "validated_ledger": {
            "base_fee": 10,
            "close_time": 780804221,
            "hash": "17ACB57A0F73B5160713E81FE72B2AC9F6064541004E272BD09F257D57C30C02",
            "reserve_base": 10000000,
            "reserve_inc": 2000000,
            "seq": 6643099
        },
        "validation_quorum": 28
    }
}
""".trimIndent()

val serverStateSyncing = """
{
    "state": {
        "build_version": "1.12.0",
        "complete_ledgers": "32570-6643099",
        "io_latency_ms": 1,
        "jq_trans_overflow": "0",
        "last_close": {
            "converge_time": 2000,
            "proposers": 34
        },
        "load_base": 256,
        "load_factor": 256,
        "load_factor_fee_escalation": 256,
        "load_factor_fee_queue": 256,
        "load_factor_fee_reference": 256,
        "load_factor_server": 256,
        "network_id": 0,
        "peers": 21,
        "ports": [],
        "server_state": "connected",
        "time": "2024-Jan-01 00:00:00",
        "uptime": 123456,
        "validated_ledger": {
            "base_fee": 10,
            "close_time": 780804221,
            "hash": "17ACB57A0F73B5160713E81FE72B2AC9F6064541004E272BD09F257D57C30C02",
            "reserve_base": 10000000,
            "reserve_inc": 2000000,
            "seq": 6643099
        },
        "validation_quorum": 28
    }
}
""".trimIndent()

class RippleChainSpecificTest {

    private val dummyReader = object : ChainReader {
        override fun read(key: ChainRequest): Mono<ChainResponse> = Mono.empty()
    }

    @Test
    fun `parseBlock with ledger_closed format`() {
        val result = RippleChainSpecific.parseBlock(
            ledgerClosedResponse.toByteArray(),
            "test-upstream",
            dummyReader,
        ).block()!!

        assertThat(result.height).isEqualTo(6643099)
        assertThat(result.hash)
            .isEqualTo(BlockId.from("17ACB57A0F73B5160713E81FE72B2AC9F6064541004E272BD09F257D57C30C02"))
        assertThat(result.upstreamId).isEqualTo("test-upstream")
        assertThat(result.parentHash).isNull()
    }

    @Test
    fun `getFromHeader with ledgerClosed WS event`() {
        val result = RippleChainSpecific.getFromHeader(
            ledgerClosedEvent.toByteArray(),
            "test-upstream",
            dummyReader,
        ).block()!!

        assertThat(result.height).isEqualTo(6643099)
        assertThat(result.hash)
            .isEqualTo(BlockId.from("17ACB57A0F73B5160713E81FE72B2AC9F6064541004E272BD09F257D57C30C02"))
        assertThat(result.upstreamId).isEqualTo("test-upstream")
        assertThat(result.parentHash).isNull()
        // Ripple epoch (2000-01-01) + 780804221 seconds = expected timestamp
        assertThat(result.timestamp).isEqualTo(Instant.ofEpochSecond(780804221 + 946684800L))
    }

    @Test
    fun `validate returns OK for full server state`() {
        val result = RippleChainSpecific.validate(serverStateOk.toByteArray())
        assertThat(result).isEqualTo(UpstreamAvailability.OK)
    }

    @Test
    fun `validate returns SYNCING for connected server state`() {
        val result = RippleChainSpecific.validate(serverStateSyncing.toByteArray())
        assertThat(result).isEqualTo(UpstreamAvailability.SYNCING)
    }

    @Test
    fun `validateSettings returns VALID for matching network`() {
        val chain = Chain.RIPPLE__MAINNET
        val result = RippleChainSpecific.validateSettings(serverStateOk.toByteArray(), chain)
        assertThat(result).isEqualTo(ValidateUpstreamSettingsResult.UPSTREAM_VALID)
    }
}
