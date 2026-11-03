package com.knode.user;

import com.knode.user.account.LoginClientAddress;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class LoginClientAddressTest {
    private static MockHttpServletRequest request(String peer,String... values) {
        var r=new MockHttpServletRequest();r.setRemoteAddr(peer);
        for (String value:values) r.addHeader("X-Forwarded-For",value);
        return r;
    }
    @Test void onlyTrustedPeersCanSupplyAValidatedRightToLeftChain() throws Exception {
        var open=new LoginClientAddress(new MockEnvironment());
        assertArrayEquals(InetAddress.getByName("203.0.113.9").getAddress(),open.resolve(request("203.0.113.9","198.51.100.1")));
        var trusted=new LoginClientAddress(new MockEnvironment().withProperty("USER_TRUSTED_PROXY_CIDRS","10.0.0.0/8,2001:db8::/32"));
        assertArrayEquals(InetAddress.getByName("198.51.100.20").getAddress(),trusted.resolve(request("10.1.1.1","198.51.100.20, 10.2.3.4")));
        assertArrayEquals(InetAddress.getByName("203.0.113.9").getAddress(),trusted.resolve(request("10.1.1.1","198.51.100.5,203.0.113.9,10.2.3.4")));
        assertArrayEquals(InetAddress.getByName("2001:4860::1").getAddress(),trusted.resolve(request("2001:db8::1","2001:4860::1,2001:db8::2")));
        var forwarded=request("10.1.1.1");forwarded.addHeader("Forwarded","for=198.51.100.5");
        assertArrayEquals(InetAddress.getByName("10.1.1.1").getAddress(),trusted.resolve(forwarded));
    }
    @Test void rejectsMalformedDuplicateAndUnboundedTrustedHeadersWithoutDns() {
        var trusted=new LoginClientAddress(new MockEnvironment().withProperty("USER_TRUSTED_PROXY_CIDRS","127.0.0.0/8"));
        for (String value:new String[]{"localhost","1.2.3","001.2.3.4","256.1.1.1","1.2.3.4,","[::1]","fe80::1%1","1.2.3.4:99","x".repeat(1025),String.join(",",java.util.Collections.nCopies(17,"1.2.3.4"))})
            assertThrows(LoginClientAddress.InvalidAddress.class,() -> trusted.resolve(request("127.0.0.1",value)));
        assertThrows(LoginClientAddress.InvalidAddress.class,() -> trusted.resolve(request("127.0.0.1","1.2.3.4","5.6.7.8")));
        assertThrows(IllegalStateException.class,() -> new LoginClientAddress(new MockEnvironment().withProperty("USER_TRUSTED_PROXY_CIDRS","localhost/32")));
        assertThrows(IllegalStateException.class,() -> new LoginClientAddress(new MockEnvironment().withProperty("USER_TRUSTED_PROXY_CIDRS","0.0.0.0/33")));
    }
}
