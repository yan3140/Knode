package com.knode.user.internal;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import org.springframework.web.bind.annotation.*;

/** New internal endpoints use bounded request reads and bare DTOs. */
@RestController
@RequestMapping("/internal/v1/auth")
public final class DelegationController {
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ServiceTokens services;private final DelegationService delegation;
    public DelegationController(ServiceTokens services,DelegationService delegation) {this.services=services;this.delegation=delegation;}
    private String bearer(HttpServletRequest request) {
        var headers=Collections.list(request.getHeaders("Authorization"));
        if (headers.size()!=1 || headers.get(0).contains(",") || !headers.get(0).startsWith("Bearer ")) throw unauthorized();
        String value=headers.get(0).substring(7);
        if (value.isBlank() || value.length()>8192 || value.indexOf(' ')>=0) throw unauthorized();
        return value;
    }
    private JsonNode body(HttpServletRequest request) {
        try {
            byte[] bytes=request.getInputStream().readNBytes(32769);
            if (bytes.length==0 || bytes.length>32768) throw new IllegalArgumentException();
            JsonNode json=JSON.readTree(bytes);
            if (json==null || !json.isObject()) throw new IllegalArgumentException();return json;
        } catch (IOException | IllegalArgumentException e) {throw new InternalFailure(400,"INVALID_ARGUMENT");}
    }
    @PostMapping("/delegations")
    public DelegationService.Issued issue(HttpServletRequest request) {
        var caller=services.verify(bearer(request),"auth:delegations:issue");return delegation.issue(caller,body(request));
    }
    @PostMapping("/delegations:validate")
    public DelegationService.Validation validate(HttpServletRequest request) {
        var receiver=services.verify(bearer(request),"auth:delegations:validate");return delegation.validate(receiver,body(request));
    }
    @PostMapping("/delegations:validate-checkpoint")
    public DelegationService.CheckpointValidation checkpoint(HttpServletRequest request) {
        var receiver=services.verify(bearer(request),"auth:delegations:validate-checkpoint");return delegation.validateCheckpoint(receiver,body(request));
    }
    private static InternalFailure unauthorized(){return new InternalFailure(401,"INVALID_SERVICE_TOKEN");}
}
