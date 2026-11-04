package com.knode.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBrowserEvidenceTest {
    private static ObjectNode evidence(boolean logout,boolean secondLogin) {
        var json=new ObjectMapper();var root=json.createObjectNode();root.put("version",1);
        var checks=root.putObject("checks");
        for (String key:new String[]{"me","checkedKeys","userRelation","roleRelation","rootParent"}) checks.put(key,true);
        root.putObject("otherFailureStatuses");ArrayNode events=root.putArray("events");
        String[][] requests={
                {"POST","/api/blog/auth/login"},{"GET","/api/blog/user/userInfo"},{"GET","/api/blog/user/userInfo"},
                {"PUT","/api/blog/user/userInfo"},{"POST","/api/blog/auth/logout"},
                {"POST","/dev-api/api/admin/auth/login"},{"GET","/dev-api/api/admin/auth/me"},
                {"GET","/dev-api/api/admin/auth/me"},{"GET","/dev-api/api/admin/auth/routers"},
                {"GET","/dev-api/api/admin/auth/routers"},{"GET","/dev-api/api/admin/system/user/9007199254740993"},
                {"PUT","/dev-api/api/admin/system/user"},{"GET","/dev-api/api/admin/system/role/9007199254740995"},
                {"PUT","/dev-api/api/admin/system/role"},{"GET","/dev-api/api/admin/system/menu/roleMenuTreeselect/9007199254740995"},
                {"GET","/dev-api/api/admin/system/menu/9007199254740997"},{"PUT","/dev-api/api/admin/system/menu"}
        };
        for (String[] request:requests) add(events,request[0],request[1]);
        if (logout) add(events,"POST","/dev-api/api/admin/auth/logout");
        if (secondLogin) add(events,"POST","/dev-api/api/admin/auth/login");
        return root;
    }
    private static void add(ArrayNode events,String method,String path) {
        var event=events.addObject();event.put("method",method);event.put("path",path);event.put("status",200);
    }
    @Test void requiresActualAdminLogoutAndSecondLogin() {
        var stack=new ExternalStackIT();
        assertThrows(IllegalStateException.class,()->stack.validateBrowserEvidence(evidence(false,true)));
        assertThrows(IllegalStateException.class,()->stack.validateBrowserEvidence(evidence(true,false)));
        assertDoesNotThrow(()->stack.validateBrowserEvidence(evidence(true,true)));
    }
}
