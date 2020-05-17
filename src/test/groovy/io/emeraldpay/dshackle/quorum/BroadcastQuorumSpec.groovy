/**
 * Copyright (c) 2019 ETCDEV GmbH
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
package io.emeraldpay.dshackle.quorum

import io.emeraldpay.dshackle.test.TestingCommons
import io.emeraldpay.dshackle.upstream.Head
import io.emeraldpay.dshackle.upstream.Upstream
import io.emeraldpay.dshackle.quorum.BroadcastQuorum
import io.infinitape.etherjar.rpc.RpcException
import spock.lang.Specification

class BroadcastQuorumSpec extends Specification {

    def objectMapper = TestingCommons.objectMapper()

    def "Resolved with first after 3 tries"() {
        setup:
        def q = Spy(new BroadcastQuorum(objectMapper, 3))
        def upstream1 = Stub(Upstream)
        def upstream2 = Stub(Upstream)
        def upstream3 = Stub(Upstream)

        when:
        q.init(Stub(Head))
        then:
        !q.isResolved()

        when:
        q.record('"0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c"'.bytes, upstream1)
        then:
        !q.isResolved()
        1 * q.recordValue(_, "0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c", _)

        when:
        q.record('"0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c"'.bytes, upstream2)
        then:
        !q.isResolved()
        1 * q.recordValue(_, "0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c", _)

        when:
        q.record(new RpcException(1, "Nonce too low"), upstream3)
        then:
        1 * q.recordError(_, _, _)
        q.isResolved()
        objectMapper.readValue(q.result, Object) == "0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c"
    }

    def "Remembers first response"() {
        setup:
        def q = Spy(new BroadcastQuorum(objectMapper, 3))
        def upstream1 = Stub(Upstream)
        def upstream2 = Stub(Upstream)
        def upstream3 = Stub(Upstream)

        when:
        q.init(Stub(Head))
        then:
        !q.isResolved()

        when:
        q.record(new RpcException(1, "Internal error"), upstream1)
        then:
        !q.isResolved()
        1 * q.recordError(_, _, _)

        when:
        q.record('"0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c"'.bytes, upstream2)
        then:
        !q.isResolved()
        1 * q.recordValue(_, "0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c", _)

        when:
        q.record(new RpcException(1, "Nonce too low"), upstream3)
        then:
        1 * q.recordError(_, _, _)
        q.isResolved()
        objectMapper.readValue(q.result, Object) == "0xeaa972c0d8d1ecd3e34fbbef6d34e06670e745c788bdba31c4234a1762f0378c"
    }
}
