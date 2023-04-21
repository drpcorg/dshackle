/**
 * Copyright (c) 2020 EmeraldPay, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.emeraldpay.dshackle.upstream

import io.emeraldpay.dshackle.data.BlockContainer
import io.emeraldpay.dshackle.data.BlockId
import io.emeraldpay.dshackle.upstream.forkchoice.ForkChoice
import org.jetbrains.annotations.NotNull
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import spock.lang.Specification
import reactor.core.scheduler.Schedulers

import java.time.Duration
import java.time.Instant

class AbstractHeadSpec extends Specification {

    def blocks = [1L, 2, 3, 4].collect { i ->
        byte[] hash = new byte[32]
        hash[0] = i as byte
        new BlockContainer(i, BlockId.from(hash), BigInteger.valueOf(i), Instant.now(), false, null, null, BlockId.from(hash), [], 0, "AbstractHeadSpec")
    }

    def "Calls beforeBlock on each block"() {
        setup:
        Sinks.Many<BlockContainer> source = Sinks.many().unicast().onBackpressureBuffer()
        def head = new TestHead()
        def called = false
        when:
        head.follow(source.asFlux())
        head.onBeforeBlock {
            called = true
        }
        def act = head.flux
        then:
        StepVerifier.create(act)
                .then { source.tryEmitNext(blocks[0]) }
                .expectNext(blocks[0])
                .then {
                    assert called
                    called = false
                    source.tryEmitNext(blocks[1])
                }
                .expectNext(blocks[1])
                .then {
                    assert called
                    head.stop()
                    source.tryEmitComplete()
                }
                .thenCancel()
                .verify(Duration.ofSeconds(1))
    }

    def "Follows source"() {
        setup:
        Sinks.Many<BlockContainer> source = Sinks.many().unicast().onBackpressureBuffer()
        def head = new TestHead()
        when:
        head.follow(source.asFlux())
        def act = head.flux
        then:
        StepVerifier.create(act)
                .then { source.tryEmitNext(blocks[0]) }
                .expectNext(blocks[0])
                .then { source.tryEmitNext(blocks[1]) }
                .expectNext(blocks[1])
                .then { source.tryEmitNext(blocks[2]) }
                .expectNext(blocks[2])
                .then { source.tryEmitNext(blocks[3]) }
                .expectNext(blocks[3])
                .then {
                    head.stop()
                    source.tryEmitComplete()
                }
                .thenCancel()
                .verify(Duration.ofSeconds(1))
    }

    def "Ignores block that is filtered by forkchoice"() {
        setup:
        Sinks.Many<BlockContainer> source = Sinks.many().unicast().onBackpressureBuffer()
        def head = new TestHead()
        def hash = BlockId.from(blocks[1].hash.value.clone().tap { it[1] = 0xff as byte })
        def wrongblock = new BlockContainer(
                blocks[1].height, hash,
                blocks[1].difficulty - 1,
                Instant.now(),
                false, null, null, hash, [], 0, "AbstractHeadSpec"
        )
        when:
        head.follow(source.asFlux())
        def act = head.flux
        then:
        StepVerifier.create(act)
                .then { source.tryEmitNext(blocks[0]) }
                .expectNext(blocks[0])
                .then { source.tryEmitNext(blocks[1]) }
                .expectNext(blocks[1])
                .then { source.tryEmitNext(wrongblock) }
                .then { source.tryEmitNext(blocks[3]) }
                .expectNext(blocks[3])
                .then {
                    head.stop()
                    source.tryEmitComplete()
                }
                .thenCancel()
                .verify(Duration.ofSeconds(1))
    }

    class TestHead extends AbstractHead {
        TestHead() {
            super(new ForkChoice() {
                @Override
                boolean filter(@NotNull BlockContainer block) {
                    return block.hash != BlockId.from("02ff000000000000000000000000000000000000000000000000000000000000")
                }

                @Override
                ForkChoice.ChoiceResult choose(@NotNull BlockContainer block) {
                    return new ForkChoice.ChoiceResult.Updated(block)
                }

                @Override
                BlockContainer getHead() {
                    return null
                }
            }, Schedulers.boundedElastic(),   new BlockValidator.AlwaysValid() , 100_000)
        }
    }
}
