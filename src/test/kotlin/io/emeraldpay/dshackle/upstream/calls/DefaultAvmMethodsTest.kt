package io.emeraldpay.dshackle.upstream.calls

import io.emeraldpay.dshackle.quorum.AlwaysQuorum
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class DefaultAvmMethodsTest {

    private val methods = DefaultAvmMethods()

    @Test
    fun rootLevelCommonEndpointsAreCallable() {
        Assertions.assertThat(methods.isCallable("GET#/genesis")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/health")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/ready")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/versions")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/metrics")).isTrue()
    }

    @Test
    fun v2ReadMethodsAreCallable() {
        Assertions.assertThat(methods.isCallable("GET#/v2/status")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/v2/blocks/*")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/v2/blocks/*/hash")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/v2/blocks/*/txids")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/v2/blocks/*/lightheader/proof")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/v2/accounts/*")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/v2/accounts/*/transactions/pending")).isTrue()
        Assertions.assertThat(methods.isCallable("GET#/v2/transactions/pending")).isTrue()
    }

    @Test
    fun sendMethodsAreCallable() {
        Assertions.assertThat(methods.isCallable("POST#/v2/transactions")).isTrue()
        Assertions.assertThat(methods.isCallable("POST#/v2/transactions/async")).isTrue()
    }

    @Test
    fun spuriousAlgodEndpointsAreNotCallable() {
        // These paths don't exist in algod's OpenAPI spec — regression guards.
        Assertions.assertThat(methods.isCallable("GET#/v2/genesis")).isFalse()
        Assertions.assertThat(methods.isCallable("GET#/v2/versions")).isFalse()
        Assertions.assertThat(methods.isCallable("GET#/v2/health")).isFalse()
        Assertions.assertThat(methods.isCallable("GET#/v2/ready")).isFalse()
        Assertions.assertThat(methods.isCallable("GET#/v2/metrics")).isFalse()
        Assertions.assertThat(methods.isCallable("GET#/v2/blocks/*/header")).isFalse()
        Assertions.assertThat(methods.isCallable("GET#/v2/blocks/*/transactions")).isFalse()
        Assertions.assertThat(methods.isCallable("GET#/v2/lightheader/*")).isFalse()
        Assertions.assertThat(methods.isCallable("POST#/v2/transactions/dryrun")).isFalse()
    }

    @Test
    fun unknownMethodsAreNotCallable() {
        Assertions.assertThat(methods.isCallable("GET#/eth/blockNumber")).isFalse()
        Assertions.assertThat(methods.isCallable("algod_status")).isFalse()
        Assertions.assertThat(methods.isCallable("DELETE#/v2/status")).isFalse()
    }

    @Test
    fun sendMethodsUseBroadcastQuorum() {
        Assertions.assertThat(methods.createQuorumFor("POST#/v2/transactions"))
            .isInstanceOf(BroadcastQuorum::class.java)
        Assertions.assertThat(methods.createQuorumFor("POST#/v2/transactions/async"))
            .isInstanceOf(BroadcastQuorum::class.java)
    }

    @Test
    fun readMethodsUseAlwaysQuorum() {
        Assertions.assertThat(methods.createQuorumFor("GET#/v2/status"))
            .isInstanceOf(AlwaysQuorum::class.java)
        Assertions.assertThat(methods.createQuorumFor("GET#/v2/blocks/*"))
            .isInstanceOf(AlwaysQuorum::class.java)
    }

    @Test
    fun noHardcodedMethods() {
        Assertions.assertThat(methods.isHardcoded("GET#/v2/status")).isFalse()
        Assertions.assertThat(methods.isHardcoded("GET#/genesis")).isFalse()
    }

    @Test
    fun defaultGroupReturnsAllSupported() {
        Assertions.assertThat(methods.getGroupMethods("default")).isEqualTo(methods.getSupportedMethods())
        Assertions.assertThat(methods.getGroupMethods("unknown")).isEmpty()
    }

    @Test
    fun supportedMethodsIncludeCoreEndpoints() {
        val supported = methods.getSupportedMethods()
        Assertions.assertThat(supported).contains(
            "GET#/v2/status",
            "GET#/genesis",
            "POST#/v2/transactions",
            "POST#/v2/teal/compile",
        )
    }
}
