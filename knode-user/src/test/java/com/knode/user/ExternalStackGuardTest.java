package com.knode.user;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalStackGuardTest {
    @Test void permitsOnlyExplicitLocalTestDatabaseAndVerifyEnvironment() {
        assertDoesNotThrow(() -> ExternalStackGuard.check("verify-example", "jdbc:mysql://127.0.0.1:13306/g5_test"));
        for (String url : new String[]{"jdbc:mysql://127.0.0.1:3306/g5_test", "jdbc:mysql://localhost:13306/g5_test",
                "jdbc:mysql://127.0.0.1/g5_test", "jdbc:mysql://127.0.0.1:13306/g5_test?user=x",
                "jdbc:mysql://user@127.0.0.1:13306/g5_test", "jdbc:mysql://192.0.2.1:13306/g5_test"}) {
            assertThrows(IllegalArgumentException.class, () -> ExternalStackGuard.check("verify-example", url));
        }
        assertThrows(IllegalArgumentException.class, () -> ExternalStackGuard.check("production", "jdbc:mysql://127.0.0.1:13306/g5_test"));
    }

    @org.junit.jupiter.api.Test void eventHandoffRequiresExactOwnedNamespaceAndLoopbackCollector() {
        String prefix="knode:verify-g8:v1:business:test:";
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> ExternalEventGuard.check("verify-g8",prefix,prefix+"stream",prefix+"probe","http://127.0.0.1:4318/v1/traces"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,() -> ExternalEventGuard.check("verify-g8",prefix,"knode:verify-other:v1:business:test:stream",prefix+"probe","http://127.0.0.1:4318/v1/traces"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,() -> ExternalEventGuard.check("verify-g8",prefix,prefix+"probe:stream",prefix+"probe","http://127.0.0.1:4318/v1/traces"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,() -> ExternalEventGuard.check("verify-g8",prefix,prefix+"stream",prefix+"probe","http://example.org:4318/v1/traces"));
    }
    @Test void handoffMustUseTheUserProducerSpanInTheIngressTrace() {
        String incoming="00-11111111111111111111111111111111-2222222222222222-01";
        String producer="00-11111111111111111111111111111111-3333333333333333-01";
        assertDoesNotThrow(() -> ExternalEventGuard.checkProducerTrace(producer,incoming));
        assertThrows(IllegalArgumentException.class,() -> ExternalEventGuard.checkProducerTrace(incoming,incoming));
        assertThrows(IllegalArgumentException.class,() -> ExternalEventGuard.checkProducerTrace(
                "00-44444444444444444444444444444444-3333333333333333-01",incoming));
        assertThrows(IllegalArgumentException.class,() -> ExternalEventGuard.checkProducerTrace("not-a-traceparent",incoming));
    }

}
