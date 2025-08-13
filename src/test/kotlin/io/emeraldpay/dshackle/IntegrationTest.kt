package io.emeraldpay.dshackle

import io.emeraldpay.dshackle.config.MainConfig
import io.emeraldpay.dshackle.config.MainConfigReader
import io.emeraldpay.dshackle.config.UpstreamsConfig
import io.emeraldpay.dshackle.foundation.ChainOptions
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.emeraldpay.dshackle.quorum.MaximumValueQuorum
import io.emeraldpay.dshackle.reader.BroadcastReader
import io.emeraldpay.dshackle.reader.RequestReaderFactory
import io.emeraldpay.dshackle.upstream.MultistreamHolder
import io.emeraldpay.dshackle.upstream.Selector
import io.grpc.BindableService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cloud.sleuth.Tracer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.ResourceUtils
import org.testcontainers.containers.GenericContainer
import java.net.URI

@SpringBootTest(properties = ["spring.main.allow-bean-definition-overriding=true"])
@Import(Config::class)
@ActiveProfiles("integration-test")
class IntegrationTest {

    @Autowired
    lateinit var services: List<BindableService>

    @Autowired
    lateinit var multistreamHolder: MultistreamHolder

    companion object {
        var ganache: GenericContainer<*> = GenericContainer<Nothing>("trufflesuite/ganache:latest").apply {
            withExposedPorts(8545)
            withCommand(
                "--server.host=0.0.0.0",
                "--server.port=8545",
                "--chain.chainId=1",
                "--chain.networkId=1", // alias: -i=80001
            )
        }

        init {
            ganache.start()
        }
    }

    @Test
    fun testUpstreamTxMethodQuorumAndReader() {
        val ms = multistreamHolder.getUpstream(Chain.ETHEREUM__MAINNET)
        val ethUpstream = ms.getUpstreams()[0]
        val tracer = mock<Tracer>()
        val reqReader = RequestReaderFactory.default()

        val txQuorum = ethUpstream.getMethods().createQuorumFor("eth_sendRawTransaction")
        val txCountQuorum = ethUpstream.getMethods().createQuorumFor("eth_getTransactionCount")

        val txReader = reqReader.create(
            RequestReaderFactory.ReaderData(
                ms,
                Selector.UpstreamFilter.default,
                txQuorum,
                null,
                tracer,
            ),
        )
        val txCountReader = reqReader.create(
            RequestReaderFactory.ReaderData(
                ms,
                Selector.UpstreamFilter.default,
                txCountQuorum,
                null,
                tracer,
            ),
        )

        assertThat(txQuorum).isInstanceOf(BroadcastQuorum::class.java)
        assertThat(txCountQuorum).isInstanceOf(MaximumValueQuorum::class.java)
        assertThat(txReader).isInstanceOf(BroadcastReader::class.java)
        assertThat(txCountReader).isInstanceOf(BroadcastReader::class.java)
    }

    @TestConfiguration
    open class Config {
        @Bean
        @Profile("integration-test")
        open fun mainConfig(@Autowired fileResolver: FileResolver): MainConfig {
            val reader = MainConfigReader(fileResolver)
            val config = reader.read(
                ResourceUtils.getFile("classpath:integration/dshackle.yaml")
                    .inputStream(),
            )!!
            patch(config)
            return config
        }

        private fun patch(config: MainConfig) {
            config.upstreams?.upstreams?.add(
                UpstreamsConfig.Upstream<UpstreamsConfig.EthereumPosConnection>().apply {
                    id = "ganache"
                    nodeId = 1
                    chain = "ethereum"
                    options = ChainOptions.PartialOptions()
                        .apply {
                            validateChain = false
                        }
                    connection = UpstreamsConfig.EthereumPosConnection().apply {
                        execution = UpstreamsConfig.RpcConnection().apply {
                            rpc = UpstreamsConfig.HttpEndpoint(
                                URI.create(
                                    "http://" + ganache.host + ":" + ganache.getMappedPort(8545) + "/",
                                ),
                            )
                        }
                    }
                },
            )
        }
    }
}
