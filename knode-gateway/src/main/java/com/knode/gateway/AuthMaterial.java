package com.knode.gateway;
import com.knode.auth.UserTokenVerifier;
import java.time.Duration;
public record AuthMaterial(UserTokenVerifier verifier, Duration timeout) {}
