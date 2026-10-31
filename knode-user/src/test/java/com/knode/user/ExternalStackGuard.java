package com.knode.user;

import java.net.URI;

final class ExternalStackGuard {
    private ExternalStackGuard() {}
    static void check(String environment, String jdbcUrl) {
        if (environment == null || !environment.matches("verify-[a-z0-9-]{1,25}"))
            throw new IllegalArgumentException("A verify- authentication environment is required");
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI uri = URI.create(jdbcUrl.substring(5));
            if (!"mysql".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                    || uri.getPort() < 1 || uri.getPort() == 3306 || uri.getPort() > 65535
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                    || uri.getPath() == null || !uri.getPath().matches("/[a-zA-Z0-9_]+"))
                throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("A dedicated local MySQL URL with an explicit non-3306 port and schema is required");
        }
    }
}
