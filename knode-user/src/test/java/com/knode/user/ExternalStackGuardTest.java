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
}
