package io.emeraldpay.dshackle.auth.service

import io.emeraldpay.dshackle.auth.processor.AuthProcessor
import io.emeraldpay.dshackle.auth.processor.AuthProcessorFactory
import io.emeraldpay.dshackle.config.AuthorizationConfig
import io.emeraldpay.dshackle.config.MainConfig
import io.grpc.StatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.CompletableFuture

class AuthServiceTest {
    private val mainConfig = MainConfig()
    private val rsaKeyReader = mock(KeyReader::class.java)
    private val mockV1Processor = mock(AuthProcessor::class.java)
    private val factory = AuthProcessorFactory(mockV1Processor)
    private val authService = AuthService(mainConfig, rsaKeyReader, factory)

    private val token = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJkcnBjIiwiaWF0IjoxNjkyMTg1OTMxLCJ2ZXJzaW9uI" +
        "joiVjEifQ.BZILN0GQ7JzXGFz-GZIbFTT9E5L-miB4Nga0v4o_cQThk8gbDelBRzEfdsqxCq_ppPr3v_Own8M-vR9yQElx5nEdlI4xe5QAMdIvr3g" +
        "12fMckydX9IsW4sVQ1kJJY8RrHb-WL-uI0WSWqoMSwf-Psb-UyiEHAjc3oK7fA72lBaGT4waPHOxRBPvezwg7N934vCZvZMAftFfVgmeEtbCeD7bF" +
        "umEr0uEmkIKPTg4QwP-VMvqoLBYpMiJVzP_Ipg_wRHJ7fUN0BGEPjjMvhQ_6TWByiQUBz1kTMd0Ebf_kEuXFQeiwA-FXHJpWczzh66CbbmmWAWsi" +
        "ehKw3KPZeBj0oQ"

    @Test
    fun `unimplemented error if auth is disabled`() {
        mainConfig.authorization = AuthorizationConfig.default()

        val e = assertThrows(StatusException::class.java) { authService.authenticate("") }
        assertEquals("UNIMPLEMENTED: Authentication process is not enabled", e.message)
    }

    @Test
    fun `auth is successful`() {
        val pair = KeyReader.KeyPair(mock(PrivateKey::class.java), mock(PublicKey::class.java))
        mainConfig.authorization = AuthorizationConfig(true, "privPath", "pubPath")

        `when`(rsaKeyReader.getKeyPair("privPath", "pubPath"))
            .thenReturn(pair)

        authService.authenticate(token)
        verify(rsaKeyReader).getKeyPair("privPath", "pubPath")
        verify(mockV1Processor).process(pair, token)
    }

    @Test
    fun `parallel try to auth is successful`() {
        val pair = KeyReader.KeyPair(mock(PrivateKey::class.java), mock(PublicKey::class.java))
        mainConfig.authorization = AuthorizationConfig(true, "privPath", "pubPath")

        `when`(rsaKeyReader.getKeyPair("privPath", "pubPath")).thenReturn(pair)

        val task = Runnable { authService.authenticate(token) }

        CompletableFuture.allOf(
            CompletableFuture.runAsync(task), CompletableFuture.runAsync(task)
        ).join()

        verify(rsaKeyReader, times(2)).getKeyPair("privPath", "pubPath")
        verify(mockV1Processor, times(2)).process(pair, token)
    }
}
