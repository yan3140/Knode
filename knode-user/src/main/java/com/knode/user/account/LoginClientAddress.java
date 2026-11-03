package com.knode.user.account;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Only an explicitly trusted direct peer may provide a bounded X-Forwarded-For chain. */
@Component
public final class LoginClientAddress {
    private final List<Cidr> trusted;
    public LoginClientAddress(Environment env) {
        String configured=env.getProperty("USER_TRUSTED_PROXY_CIDRS","");
        if (configured.isBlank()) {trusted=List.of();return;}
        String[] entries=configured.split(",",-1);
        if (entries.length>32) throw new IllegalStateException("Invalid trusted proxy configuration");
        var parsed=new ArrayList<Cidr>();
        try {
            for (String entry:entries) {
                String[] parts=entry.trim().split("/",-1);
                if (parts.length!=2 || !parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException();
                byte[] address=literal(parts[0]);int bits=Integer.parseInt(parts[1]);
                if (bits<0 || bits>address.length*8) throw new IllegalArgumentException();
                parsed.add(new Cidr(address,bits));
            }
        } catch (RuntimeException ex) {throw new IllegalStateException("Invalid trusted proxy configuration");}
        trusted=List.copyOf(parsed);
    }
    public byte[] resolve(HttpServletRequest request) {
        byte[] peer;
        try {peer=literal(request.getRemoteAddr());}
        catch (RuntimeException invalid) {throw new InvalidAddress();}
        if (!isTrusted(peer)) return peer;
        List<String> headers=Collections.list(request.getHeaders("X-Forwarded-For"));
        if (headers.isEmpty()) return peer;
        if (headers.size()!=1 || headers.get(0)==null || headers.get(0).length()>1024) throw new InvalidAddress();
        String[] parts=headers.get(0).split(",",-1);
        if (parts.length==0 || parts.length>16) throw new InvalidAddress();
        var chain=new ArrayList<byte[]>();
        try {for (String part:parts) chain.add(literal(part.trim()));}
        catch (RuntimeException invalid) {throw new InvalidAddress();}
        byte[] current=peer;
        for (int i=chain.size()-1;i>=0 && isTrusted(current);i--) current=chain.get(i);
        return current;
    }
    private boolean isTrusted(byte[] address) {return trusted.stream().anyMatch(cidr -> cidr.contains(address));}
    private record Cidr(byte[] address,int bits) {
        boolean contains(byte[] other) {
            if (address.length!=other.length) return false;
            int bytes=bits/8,rest=bits%8;
            for (int i=0;i<bytes;i++) if (address[i]!=other[i]) return false;
            int mask=0xff << (8-rest);
            return rest==0 || (address[bytes]&mask)==(other[bytes]&mask);
        }
    }
    private static byte[] literal(String value) {
        if (value==null || value.isEmpty() || value.length()>45) throw new IllegalArgumentException();
        if (value.indexOf(':')>=0) {
            if (!value.matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException();
            try {return InetAddress.getByName(value).getAddress();}
            catch (java.net.UnknownHostException ex) {throw new IllegalArgumentException();}
        }
        String[] parts=value.split("\\.",-1);
        if (parts.length!=4) throw new IllegalArgumentException();
        byte[] result=new byte[4];
        for (int i=0;i<4;i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
            int n=Integer.parseInt(parts[i]);if (n>255) throw new IllegalArgumentException();result[i]=(byte)n;
        }
        return result;
    }
    public static final class InvalidAddress extends RuntimeException {}
}
