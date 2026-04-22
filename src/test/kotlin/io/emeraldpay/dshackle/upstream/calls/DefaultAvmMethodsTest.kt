package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.NotNullQuorum
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class DefaultAvmMethodsTest {

    private val methods = DefaultAvmMethods(Chain.UNSPECIFIED)

    @Test
    fun commonMethodsAreCallable() {
        Assertions.assertThat(methods.isCallable("algod_status")).isTrue()
        Assertions.assertThat(methods.isCallable("algod_getBlock")).isTrue()
        Assertions.assertThat(methods.isCallable("algod_getAccount")).isTrue()
        Assertions.assertThat(methods.isCallable("algod_sendRawTransaction")).isTrue()
    }

    @Test
    fun unknownMethodsAreNotCallable() {
        Assertions.assertThat(methods.isCallable("eth_blockNumber")).isFalse()
        Assertions.assertThat(methods.isCallable("some_unknown_method")).isFalse()
    }

    @Test
    fun sendTransactionsUseBroadcastQuorum() {
        Assertions.assertThat(methods.createQuorumFor("algod_sendRawTransaction"))
            .isInstanceOf(BroadcastQuorum::class.java)
        Assertions.assertThat(methods.createQuorumFor("algod_sendTransaction"))
            .isInstanceOf(BroadcastQuorum::class.java)
    }

    @Test
    fun blockMethodsUseNotNullQuorum() {
        Assertions.assertThat(methods.createQuorumFor("algod_getBlock"))
            .isInstanceOf(NotNullQuorum::class.java)
        Assertions.assertThat(methods.createQuorumFor("algod_getBlockHash"))
            .isInstanceOf(NotNullQuorum::class.java)
    }

    @Test
    fun defaultMethodsUseAlwaysQuorum() {
        Assertions.assertThat(methods.createQuorumFor("algod_status"))
            .isInstanceOf(AlwaysQuorum::class.java)
    }

    @Test
    fun chainIdIsHardcoded() {
        Assertions.assertThat(methods.isHardcoded("algod_chainId")).isTrue()
        Assertions.assertThat(methods.isHardcoded("algod_genesisId")).isTrue()
        Assertions.assertThat(methods.isHardcoded("algod_status")).isFalse()
    }

    @Test
    fun supportedMethodsIncludeHardcoded() {
        val supported = methods.getSupportedMethods()
        Assertions.assertThat(supported).contains("algod_status", "algod_chainId", "algod_genesisId")
    }

    @Test
    fun defaultGroupReturnsAllSupported() {
        Assertions.assertThat(methods.getGroupMethods("default")).isEqualTo(methods.getSupportedMethods())
        Assertions.assertThat(methods.getGroupMethods("unknown")).isEmpty()
    }
}
