package com.knode.content.counter;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class CounterIdentity {
    private CounterIdentity() {}
    static CounterStore.Identity of(long articleId,String remote,String agent) {
        try {
            String ip=peer(remote);
            if(agent==null) agent="";
            if(agent.getBytes(StandardCharsets.UTF_8).length>512) throw new IllegalArgumentException("Invalid user agent");
            String id=Long.toString(articleId);
            return new CounterStore.Identity(digest("ip",ip),digest("article",ip,id),digest("visitor",ip,agent,id));
        } catch(IllegalArgumentException e) { throw e; }
        catch(Exception e) { throw new CounterUnavailable(); }
    }
    static String peer(String remote) throws Exception {
        if(remote==null || remote.length()>45) throw new CounterUnavailable();
        if(remote.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts=remote.split("\\.",-1); byte[] bytes=new byte[4];
            for(int i=0;i<4;i++) { int n=Integer.parseInt(parts[i]); if(n>255) throw new CounterUnavailable(); bytes[i]=(byte)n; }
            return HexFormat.of().formatHex(bytes);
        }
        if(remote.indexOf(':')<0 || !remote.matches("[0-9A-Fa-f:.]+")) throw new CounterUnavailable();
        return HexFormat.of().formatHex(InetAddress.getByName(remote).getAddress());
    }
    private static String digest(String... values) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String value:values) {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
            hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
