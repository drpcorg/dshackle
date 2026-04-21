package io.emeraldpay.dshackle.startup.configure

import io.emeraldpay.dshackle.Chain
import io.emeraldpay.dshackle.config.ChainsConfig
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.foundation.ChainOptions
import io.emeraldpay.dshackle.upstream.CallTargetsHolder
import io.emeraldpay.dshackle.upstream.signature.DisabledSigner
import io.emeraldpay.dshackle.upstream.signature.ResponseSigner
import io.emeraldpay.dshackle.upstream.signature.RsaSigner
import spock.lang.Specification

import java.security.interfaces.RSAPrivateKey

class UpstreamCreatorLabelsSpec extends Specification {

    static class TestCreator extends UpstreamCreator {
        TestCreator(ChainsConfig chainsConfig, CallTargetsHolder callTargets, ResponseSigner signer) {
            super(chainsConfig, callTargets, signer)
        }

        @Override
        protected UpstreamCreationData createUpstream(
                UpstreamsConfig.Upstream<?> upstreamsConfig,
                Chain chain,
                ChainOptions.Options options,
                ChainsConfig.ChainConfig chainConf) {
            return UpstreamCreationData.default()
        }

        UpstreamsConfig.Labels callBuildLabels(Map<String, String> src) {
            return buildUpstreamLabels(src)
        }
    }

    TestCreator makeCreator(ResponseSigner signer) {
        return new TestCreator(Mock(ChainsConfig), Mock(CallTargetsHolder), signer)
    }

    def "Adds secure-signed label when signer is enabled"() {
        setup:
        def creator = makeCreator(new RsaSigner(Stub(RSAPrivateKey), 1L))

        when:
        def labels = creator.callBuildLabels(["provider": "drpc"])

        then:
        labels["provider"] == "drpc"
        labels["secure-signed"] == "true"
    }

    def "Does not add secure-signed label when signer is disabled"() {
        setup:
        def creator = makeCreator(new DisabledSigner())

        when:
        def labels = creator.callBuildLabels(["provider": "drpc"])

        then:
        labels["provider"] == "drpc"
        !labels.containsKey("secure-signed")
    }

    def "Does not override user-provided secure-signed label"() {
        setup:
        def creator = makeCreator(new RsaSigner(Stub(RSAPrivateKey), 1L))

        when:
        def labels = creator.callBuildLabels(["secure-signed": "false"])

        then:
        labels["secure-signed"] == "false"
    }
}
