package com.knode.user;

import java.net.URI;

final class ExternalEventGuard {
    private ExternalEventGuard() {}
    static String checkJaegerEndpoint(String endpoint) {
        try {
            URI uri=URI.create(endpoint);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort()<1 || uri.getPort()>65535 || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) throw new IllegalArgumentException();
            return "http://127.0.0.1:"+uri.getPort();
        } catch (RuntimeException e) {throw new IllegalArgumentException("Explicit loopback Jaeger HTTP origin required");}
    }
    static void checkProducerTrace(String producer,String ingress) {
        var context=new com.knode.observability.EventTraceContext(io.opentelemetry.api.OpenTelemetry.noop());
        String saved=context.fromStored(producer,null).orElseThrow(() -> new IllegalArgumentException("Invalid producer traceparent")).traceparent();
        String incoming=context.fromStored(ingress,null).orElseThrow(() -> new IllegalArgumentException("Invalid ingress traceparent")).traceparent();
        if (!saved.substring(3,35).equals(incoming.substring(3,35)) || saved.substring(36,52).equals(incoming.substring(36,52)))
            throw new IllegalArgumentException("Producer must be a different span in the ingress trace");
    }
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
