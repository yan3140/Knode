package com.yan.utils;

import com.yan.constants.AuthConstants;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {
    @Test
    void scopedTokenCarriesUserAndScope() {
        String token = JwtUtil.createJWT("42", AuthConstants.ADMIN_SCOPE);

        Claims claims = JwtUtil.parseJWT(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get(AuthConstants.SCOPE_CLAIM, String.class))
                .isEqualTo(AuthConstants.ADMIN_SCOPE);
    }
}
