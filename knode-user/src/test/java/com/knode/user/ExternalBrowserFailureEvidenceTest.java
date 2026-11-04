package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBrowserFailureEvidenceTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @TempDir Path temp;
    private ObjectNode valid() {
        var node=mapper.createObjectNode();node.put("failed",true);node.put("stage","blog-update");
        node.put("step","blog-update-success-message");node.put("profilePutCode",200);
        var checks=node.putObject("checks");
        for (String name:new String[]{"me","checkedKeys","userRelation","roleRelation","rootParent"}) checks.put(name,false);
        var request=node.putArray("requests").addObject();request.put("method","PUT");request.put("path","/api/blog/user/userInfo");
        var response=node.putArray("events").addObject();response.put("method","PUT");response.put("path","/api/blog/user/userInfo");response.put("status",200);
        return node;
    }
    private void reject(ObjectNode node) {
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserFailureEvidence.sanitize(node,mapper));
    }
    @Test void rebuildsOnlyWhitelistedFailureFacts() throws Exception {
        Path input=temp.resolve("failure.json");mapper.writeValue(input.toFile(),valid());
        var result=ExternalBrowserFailureEvidence.read(input,mapper);
        assertEquals("blog-update-success-message",result.path("step").asText());
        assertEquals(200,result.path("profilePutCode").asInt());
        assertEquals("PUT",result.path("requests").get(0).path("method").asText());
        assertEquals(200,result.path("events").get(0).path("status").asInt());
        var noCode=valid();noCode.putNull("profilePutCode");
        assertTrue(ExternalBrowserFailureEvidence.sanitize(noCode,mapper).path("profilePutCode").isNull());
    }
    @Test void distinguishesNoPutNetworkFailureAndBusinessFailure() {
        var noPut=valid();noPut.withArray("requests").removeAll();noPut.withArray("events").removeAll();noPut.putNull("profilePutCode");
        var absent=ExternalBrowserFailureEvidence.sanitize(noPut,mapper);
        assertTrue(absent.path("requests").isEmpty());assertTrue(absent.path("events").isEmpty());
        var noResponse=valid();noResponse.withArray("events").removeAll();noResponse.putNull("profilePutCode");
        var pending=ExternalBrowserFailureEvidence.sanitize(noResponse,mapper);
        assertEquals(1,pending.path("requests").size());assertTrue(pending.path("events").isEmpty());
        var business=valid();business.put("profilePutCode",515);
        var failure=ExternalBrowserFailureEvidence.sanitize(business,mapper);
        assertEquals(200,failure.path("events").get(0).path("status").asInt());
        assertEquals(515,failure.path("profilePutCode").asInt());
        var http=valid();((ObjectNode)http.path("events").get(0)).put("status",503);http.putNull("profilePutCode");
        assertEquals(503,ExternalBrowserFailureEvidence.sanitize(http,mapper).path("events").get(0).path("status").asInt());
    }
    @Test void rejectsUnknownFieldsSecretsAndUnapprovedPaths() {
        var node=valid();node.put("token","secret");reject(node);
        node=valid();((ObjectNode)node.path("events").get(0)).put("headers","secret");reject(node);
        node=valid();((ObjectNode)node.path("requests").get(0)).put("path","/api/blog/user/userInfo?token=secret");reject(node);
        node=valid();node.put("step","blog-update-secret");reject(node);
        node=valid();node.put("stage","other");reject(node);
        node=valid();node.put("profilePutCode","secret");reject(node);
        node=valid();node.put("profilePutCode",10000);reject(node);
        node=valid();((ObjectNode)node.path("checks")).put("extra","secret");reject(node);
        node=valid();((ObjectNode)node.path("events").get(0)).put("status",999);reject(node);
        node=valid();((ObjectNode)node.path("events").get(0)).put("method","PATCH");reject(node);
        node=valid();for (int i=0;i<121;i++) node.withArray("requests").addObject();
        reject(node);
    }
    @Test void rejectsOversizedAndDuplicateFieldsBeforeCopying() throws Exception {
        Path input=temp.resolve("bad.json");
        Files.writeString(input,"x".repeat(65537));
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserFailureEvidence.read(input,mapper));
        Files.writeString(input,"{\"failed\":true,\"failed\":true}");
        assertThrows(java.io.IOException.class,()->ExternalBrowserFailureEvidence.read(input,mapper));
        Files.writeString(input,mapper.writeValueAsString(valid())+" {\"token\":\"secret\"}");
        assertThrows(java.io.IOException.class,()->ExternalBrowserFailureEvidence.read(input,mapper));
    }
}
