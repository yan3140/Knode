package com.knode.content.relay;

import com.knode.auth.ServiceTokenVerifier;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsControllerTest {
    @Test void handlerRequiresVerifiedPrincipalAndItsOwnOperationPermissionBeforeDatabaseAccess() {
        JdbcTemplate db=mock(JdbcTemplate.class);ReplayService replay=mock(ReplayService.class);
        OpsController controller=new OpsController(db,replay);
        MockHttpServletRequest request=new MockHttpServletRequest();
        String event=UUID.randomUUID().toString();ReplayInput input=new ReplayInput("request-1");

        assertEquals(401,assertThrows(OpsFailure.class,() -> controller.summary(request)).status());
        assertEquals(401,assertThrows(OpsFailure.class,() -> controller.redeliver(event,input,request)).status());

        request.setAttribute(MachineAuth.PRINCIPAL,new ServiceTokenVerifier.Principal("ops","knode-content",Set.of("ops:redeliver"),1));
        assertEquals(403,assertThrows(OpsFailure.class,() -> controller.summary(request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,new ServiceTokenVerifier.Principal("ops","knode-content",Set.of("ops:read"),1));
        assertEquals(403,assertThrows(OpsFailure.class,() -> controller.redeliver(event,input,request)).status());
        request.setAttribute(MachineAuth.PRINCIPAL,new ServiceTokenVerifier.Principal("ops","other",Set.of("ops:read","ops:redeliver"),1));
        assertEquals(403,assertThrows(OpsFailure.class,() -> controller.summary(request)).status());
        assertEquals(403,assertThrows(OpsFailure.class,() -> controller.redeliver(event,input,request)).status());
        verifyNoInteractions(db,replay);

        request.setAttribute(MachineAuth.PRINCIPAL,new ServiceTokenVerifier.Principal("ops","knode-content",Set.of("ops:redeliver"),1));
        controller.redeliver(event,input,request);
        verify(replay).apply(event,"request-1","ops");
    }
}
