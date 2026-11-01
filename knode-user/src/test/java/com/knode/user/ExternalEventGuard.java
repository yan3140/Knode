package com.knode.user;

import java.net.URI;

final class ExternalEventGuard {
    private ExternalEventGuard() {}
    static void check(String environment,String businessPrefix,String stream,String probe,String endpoint) {
        String required="knode:"+environment+":v1:business:";
        if (environment==null || !environment.matches("verify-[a-z0-9-]{1,25}") || businessPrefix==null || !businessPrefix.startsWith(required)
                || stream==null || probe==null || !stream.startsWith(businessPrefix) || !probe.startsWith(businessPrefix)
                || !stream.matches("[A-Za-z0-9:_-]{1,255}") || !probe.matches("[A-Za-z0-9:_-]{1,218}")
                || stream.equals(probe) || stream.startsWith(probe+":")) throw new IllegalArgumentException("Invalid event test namespace");
        try {
            URI uri=URI.create(endpoint);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1
                    || !"/v1/traces".equals(uri.getPath()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException();
        } catch (RuntimeException e) {throw new IllegalArgumentException("Explicit loopback OTLP HTTP traces endpoint required");}
    }
}
