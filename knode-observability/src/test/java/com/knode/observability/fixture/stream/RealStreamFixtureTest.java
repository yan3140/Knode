package com.knode.observability.fixture.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in: crash mode intentionally halts this disposable Maven fork with exit 73. */
@EnabledIfSystemProperty(named="knode.fixture.real.enabled", matches="true")
class RealStreamFixtureTest {
    @Test void executeExplicitOperatorMode() throws Exception {
        StreamFixture.run(StreamFixture.Config.from(System.getenv()));
    }
}
