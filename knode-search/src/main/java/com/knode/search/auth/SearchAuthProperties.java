package com.knode.search.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "knode.search.auth")
public class SearchAuthProperties {
    private String expectedServiceAudience = "knode-search";
    private String publicKeyId = "";
    private String publicKeyFile = "";
    private Map<String, String> trustedPublicKeys = new LinkedHashMap<>();
    private Map<String, String> serviceTrustedPublicKeys = new LinkedHashMap<>();
    private SessionProps session = new SessionProps();
    private AdminProps admin = new AdminProps();

    public String getExpectedServiceAudience() {
        return expectedServiceAudience;
    }

    public void setExpectedServiceAudience(String expectedServiceAudience) {
        this.expectedServiceAudience = expectedServiceAudience;
    }

    public String getPublicKeyId() {
        return publicKeyId;
    }

    public void setPublicKeyId(String publicKeyId) {
        this.publicKeyId = publicKeyId;
    }

    public String getPublicKeyFile() {
        return publicKeyFile;
    }

    public void setPublicKeyFile(String publicKeyFile) {
        this.publicKeyFile = publicKeyFile;
    }

    public Map<String, String> getTrustedPublicKeys() {
        return trustedPublicKeys;
    }

    public void setTrustedPublicKeys(Map<String, String> trustedPublicKeys) {
        this.trustedPublicKeys = trustedPublicKeys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trustedPublicKeys);
    }

    public Map<String, String> getServiceTrustedPublicKeys() {
        return serviceTrustedPublicKeys;
    }

    public void setServiceTrustedPublicKeys(Map<String, String> serviceTrustedPublicKeys) {
        this.serviceTrustedPublicKeys = serviceTrustedPublicKeys == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(serviceTrustedPublicKeys);
    }

    public SessionProps getSession() {
        return session;
    }

    public void setSession(SessionProps session) {
        this.session = session == null ? new SessionProps() : session;
    }

    public AdminProps getAdmin() {
        return admin;
    }

    public void setAdmin(AdminProps admin) {
        this.admin = admin == null ? new AdminProps() : admin;
    }

    public static class SessionProps {
        private RedisProps redis = new RedisProps();

        public RedisProps getRedis() {
            return redis;
        }

        public void setRedis(RedisProps redis) {
            this.redis = redis == null ? new RedisProps() : redis;
        }
    }

    public static class RedisProps {
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 6379;
        private int database = 0;
        private String username = "";
        private String password = "";
        private boolean tls = false;
        private long connectTimeoutMs = 400L;
        private long commandTimeoutMs = 800L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isTls() {
            return tls;
        }

        public void setTls(boolean tls) {
            this.tls = tls;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getCommandTimeoutMs() {
            return commandTimeoutMs;
        }

        public void setCommandTimeoutMs(long commandTimeoutMs) {
            this.commandTimeoutMs = commandTimeoutMs;
        }
    }

    public static class AdminProps {
        private long meConnectTimeoutMs = 400L;
        private long meTimeoutMs = 1000L;
        private int meMaxResponseBytes = 3_145_728;
        private List<String> rebuildPermissions = new ArrayList<>();
        private List<String> retryPermissions = new ArrayList<>();
        private List<String> syncPermissions = new ArrayList<>();

        public long getMeConnectTimeoutMs() {
            return meConnectTimeoutMs;
        }

        public void setMeConnectTimeoutMs(long meConnectTimeoutMs) {
            this.meConnectTimeoutMs = meConnectTimeoutMs;
        }

        public long getMeTimeoutMs() {
            return meTimeoutMs;
        }

        public void setMeTimeoutMs(long meTimeoutMs) {
            this.meTimeoutMs = meTimeoutMs;
        }

        public int getMeMaxResponseBytes() {
            return meMaxResponseBytes;
        }

        public void setMeMaxResponseBytes(int meMaxResponseBytes) {
            this.meMaxResponseBytes = meMaxResponseBytes;
        }

        public List<String> getRebuildPermissions() {
            return rebuildPermissions;
        }

        public void setRebuildPermissions(List<String> rebuildPermissions) {
            this.rebuildPermissions = rebuildPermissions == null ? new ArrayList<>() : new ArrayList<>(rebuildPermissions);
        }

        public List<String> getRetryPermissions() {
            return retryPermissions;
        }

        public void setRetryPermissions(List<String> retryPermissions) {
            this.retryPermissions = retryPermissions == null ? new ArrayList<>() : new ArrayList<>(retryPermissions);
        }

        public List<String> getSyncPermissions() {
            return syncPermissions;
        }

        public void setSyncPermissions(List<String> syncPermissions) {
            this.syncPermissions = syncPermissions == null ? new ArrayList<>() : new ArrayList<>(syncPermissions);
        }
    }
}
