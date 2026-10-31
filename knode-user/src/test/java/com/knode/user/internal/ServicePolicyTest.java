package com.knode.user.internal;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServicePolicyTest {
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
    private String policy(String hash, String ttl) {
        return "{\"ttlSeconds\":" + ttl + ",\"services\":[{\"id\":\"knode-client\",\"credentialHash\":\"" + hash
                + "\",\"audiences\":{\"knode-user\":[\"users:public:read\"]}}]}";
    }
    @Test void rejectsInvalidPolicyAtStartupAndDisabledIssuerFailsClosed() {
        String hash = bcrypt.encode("secret");
        assertThrows(IllegalArgumentException.class, () -> ServicePolicy.load(policy(hash, "301"), bcrypt));
        assertThrows(IllegalArgumentException.class, () -> ServicePolicy.load(policy(hash.replace("$04$", "$99$"), "120"), bcrypt));
        assertThrows(IllegalArgumentException.class, () -> ServicePolicy.load(policy(hash, "1.5"), bcrypt));
        assertThrows(IllegalArgumentException.class, () -> ServicePolicy.load(policy(hash, "120").replace("knode-client", "knode-client\",\"unexpected\":true,\"x\":\""), bcrypt));
        var disabled = ServicePolicy.disabled(bcrypt);
        assertFalse(disabled.enabled());
        assertEquals(503, assertThrows(InternalFailure.class, () -> disabled.authorize("knode-client", "secret", "knode-user", List.of("users:public:read"))).status());
        assertEquals(1, ServicePolicy.load(policy(hash, "1"), bcrypt).ttl());
    }
}
