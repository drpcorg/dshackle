package io.emeraldpay.dshackle.upstream.aztec

import io.emeraldpay.dshackle.reader.ChainReader
import io.emeraldpay.dshackle.upstream.ChainCallError
import io.emeraldpay.dshackle.upstream.ChainRequest
import io.emeraldpay.dshackle.upstream.ChainResponse
import io.emeraldpay.dshackle.upstream.UNKNOWN_CLIENT_VERSION
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.upstream.rpcclient.ListParams
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class AztecUpstreamSettingsDetectorTest {

    private val versionRequest = ChainRequest("node_getNodeVersion", ListParams())

    @Test
    fun `detect client version - quoted string with v prefix`() {
        val detector = detectorFor("\"v1.2.3\"")

        StepVerifier.create(detector.detectClientVersion())
            .expectNext("1.2.3")
            .expectComplete()
            .verify()
    }

    @Test
    fun `detect client version - quoted string without v prefix`() {
        val detector = detectorFor("\"0.84.0\"")

        StepVerifier.create(detector.detectClientVersion())
            .expectNext("0.84.0")
            .expectComplete()
            .verify()
    }

    @Test
    fun `detect client version - unquoted plain string`() {
        // some clients answer with a raw string (no JSON quoting)
        val detector = detectorFor("v1.2.3")

        StepVerifier.create(detector.detectClientVersion())
            .expectNext("1.2.3")
            .expectComplete()
            .verify()
    }

    @Test
    fun `detect client version - empty payload returns unknown`() {
        val detector = detectorFor("\"\"")

        StepVerifier.create(detector.detectClientVersion())
            .expectNext(UNKNOWN_CLIENT_VERSION)
            .expectComplete()
            .verify()
    }

    @Test
    fun `detect client version - upstream error returns unknown`() {
        val reader = mock<ChainReader> {
            on { read(versionRequest) } doReturn
                Mono.just(ChainResponse(null, ChainCallError(1, "boom")))
        }
        val up = mock<Upstream> {
            on { getIngressReader() } doReturn reader
        }

        val detector = AztecUpstreamSettingsDetector(up)

        StepVerifier.create(detector.detectClientVersion())
            .expectNext(UNKNOWN_CLIENT_VERSION)
            .expectComplete()
            .verify()
    }

    @Test
    fun `detect labels - quoted string yields client_type and client_version`() {
        val detector = detectorFor("\"v1.2.3\"")

        StepVerifier.create(detector.detectLabels())
            .expectNext("client_type" to "aztec")
            .expectNext("client_version" to "1.2.3")
            .expectComplete()
            .verify()
    }

    @Test
    fun `detect labels - object payload with nodeVersion field`() {
        val detector = detectorFor("""{"nodeVersion": "v0.84.0", "l1ChainId": 1}""")

        StepVerifier.create(detector.detectLabels())
            .expectNext("client_type" to "aztec")
            .expectNext("client_version" to "0.84.0")
            .expectComplete()
            .verify()
    }

    private fun detectorFor(versionResponse: String): AztecUpstreamSettingsDetector {
        val reader = mock<ChainReader> {
            on { read(versionRequest) } doReturn
                Mono.just(ChainResponse(versionResponse.toByteArray(), null))
        }
        val up = mock<Upstream> {
            on { getIngressReader() } doReturn reader
        }
        return AztecUpstreamSettingsDetector(up)
    }
}
