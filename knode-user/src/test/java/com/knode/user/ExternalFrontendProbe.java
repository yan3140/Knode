package com.knode.user;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Frontend dev servers must not receive the JDK client's h2c upgrade request. */
final class ExternalFrontendProbe {
    record Observation(Integer status,String errorType,boolean listening) {}
    private final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(3)).build();

    Observation observe(int port) throws InterruptedException {
        if (port<1 || port>65535) throw new IllegalArgumentException("Invalid local frontend port");
        try {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return new Observation(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),null,true);
        } catch (IOException error) {
            return new Observation(null,error.getClass().getSimpleName(),listening(port));
        }
    }
    boolean listening(int port) {
        try (Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",port),250);return true;
        } catch (IOException ignored) {return false;}
    }
}
