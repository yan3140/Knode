package com.knode.content.upload;

import java.net.URI;
import java.util.Locale;

public record UploadSettings(boolean enabled,String endpoint,String region,String bucket) {
    public static UploadSettings parse(String enabled,String endpoint,String region,String bucket) {
        if (!"true".equalsIgnoreCase(enabled) && !"false".equalsIgnoreCase(enabled))
            throw new IllegalStateException("Invalid upload configuration");
        if ("false".equalsIgnoreCase(enabled)) return new UploadSettings(false,"","","");
        if (endpoint==null || region==null || bucket==null ||
                !region.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || region.length()>63 ||
                !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new IllegalStateException("Invalid upload configuration");
        try {
            URI u=URI.create(endpoint);
            String expected="oss-"+region+".aliyuncs.com";
            if (!"https".equals(u.getScheme()) || u.getHost()==null ||
                    !expected.equals(u.getHost().toLowerCase(Locale.ROOT)) || u.getPort()!=-1 ||
                    u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null ||
                    !(u.getRawPath().isEmpty() || "/".equals(u.getRawPath())))
                throw new IllegalStateException("Invalid upload configuration");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Invalid upload configuration");
        }
        return new UploadSettings(true,endpoint,region,bucket);
    }
}
