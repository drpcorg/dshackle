package io.emeraldpay.dshackle.rpc

import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightSpec extends Specification {

    def "Callers that arrive while a request runs share it"() {
        setup:
        def flight = new SingleFlight<String>()
        def calls = new AtomicInteger()
        def sink = Sinks.<String> one()
        def supplier = { calls.incrementAndGet(); sink.asMono() }

        when:
        def aP = flight.run("k", supplier)
        def a = aP.first
        def leaderA = aP.second
        def bP = flight.run("k", supplier)
        def b = bP.first
        def leaderB = bP.second
        def ra = a.toFuture()
        def rb = b.toFuture()
        sink.tryEmitValue("v")

        then:
        leaderA
        !leaderB
        ra.get() == "v"
        rb.get() == "v"
        calls.get() == 1
        flight.size() == 0
    }

    def "Nothing is kept after the request finished"() {
        setup:
        def flight = new SingleFlight<String>()
        def calls = new AtomicInteger()
        def supplier = { Mono.just("v" + calls.incrementAndGet()) }

        when:
        def first = flight.run("k", supplier).first.block(Duration.ofSeconds(1))
        def second = flight.run("k", supplier).first.block(Duration.ofSeconds(1))

        then:
        first == "v1"
        second == "v2"
        flight.size() == 0
    }

    def "Different keys don't share"() {
        setup:
        def flight = new SingleFlight<String>()
        def sink = Sinks.<String> one()

        when:
        def aP = flight.run("a", { sink.asMono() })
        def a = aP.first
        def leaderA = aP.second
        def bP = flight.run("b", { Mono.just("b") })
        def b = bP.first
        def leaderB = bP.second

        then:
        leaderA
        leaderB
        b.block(Duration.ofSeconds(1)) == "b"
    }

    def "An error is shared with the followers"() {
        setup:
        def flight = new SingleFlight<String>()
        def sink = Sinks.<String> one()

        when:
        def aP = flight.run("k", { sink.asMono() })
        def a = aP.first
        def bP = flight.run("k", { Mono.just("never") })
        def b = bP.first
        def leaderB = bP.second
        def fa = a.toFuture()
        def fb = b.toFuture()
        sink.tryEmitError(new IllegalStateException("boom"))
        fb.get()

        then:
        !leaderB
        def e = thrown(Exception)
        e.cause instanceof IllegalStateException
        flight.size() == 0
    }

    def "A follower still gets the result when the leader's client goes away"() {
        setup:
        def flight = new SingleFlight<String>()
        def sink = Sinks.<String> one()

        when:
        def aP = flight.run("k", { sink.asMono() })
        def a = aP.first
        def bP = flight.run("k", { Mono.just("never") })
        def b = bP.first
        def leaderB = bP.second
        def leaderSub = a.subscribe()
        def fb = b.toFuture()
        leaderSub.dispose()
        sink.tryEmitValue("v")

        then:
        !leaderB
        fb.get() == "v"
    }
}
