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
}
