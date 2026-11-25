package com.knode.community.auth;

import com.knode.auth.AuthFailure;
import com.knode.auth.ServiceTokenVerifier;
import com.knode.community.support.InternalFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class MachineAuth {
    public static final String PRINCIPAL = MachineAuth.class.getName() + ".principal";
    public static final String AUDIENCE = "knode-community";

    private final ServiceTokenVerifier verifier;

    public MachineAuth(
            AuthConfiguration.TrustedKeyRing defaultRing,
            Clock clock,
            @Value("${KNODE_COMMUNITY_OPS_PUBLIC_KEYS:}") String explicitOpsKeys) {
        try {
            Map<String, RSAPublicKey> keys = explicitOpsKeys.isBlank()
                    ? defaultRing.keys()
                    : AuthConfiguration.parseKeyRingSpec(explicitOpsKeys, true);
            this.verifier = new ServiceTokenVerifier(keys, clock);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid community machine public keys", failure);
        }
    }

    public ServiceTokenVerifier.Principal authenticate(
            HttpServletRequest request,
            String requiredPermission,
            boolean allowOptionalViewerTokenHeader) {
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1
                || !headers.get(0).startsWith("Bearer ")
                || headers.get(0).contains(",")
                || !Collections.list(request.getHeaders("Cookie")).isEmpty()
                || !Collections.list(request.getHeaders("Proxy-Authorization")).isEmpty()
                || !Collections.list(request.getHeaders("X-Api-Key")).isEmpty()) {
            throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Invalid service token", false);
        }
        if (!allowOptionalViewerTokenHeader && !Collections.list(request.getHeaders("token")).isEmpty()) {
            throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Unexpected user token on machine endpoint", false);
        }
        String compact = headers.get(0).substring(7);
        if (compact.isBlank() || compact.length() > 8192 || compact.chars().anyMatch(Character::isWhitespace)) {
            throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Invalid service token", false);
        }
        try {
            ServiceTokenVerifier.Principal principal = verifier.verify(compact, AUDIENCE, Set.of(requiredPermission));
            if (principal.service() == null || !principal.service().matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Invalid service subject", false);
            }
            return principal;
        } catch (AuthFailure failure) {
            if (failure.status() == 403) {
                throw new InternalFailure(403, "SERVICE_PERMISSION_DENIED", "Service permission denied", false);
            }
            throw new InternalFailure(401, "INVALID_SERVICE_TOKEN", "Invalid service token", false);
        }
    }
}
