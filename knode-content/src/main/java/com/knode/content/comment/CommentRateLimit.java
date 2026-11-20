package com.knode.content.comment;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class CommentRateLimit {
    private final CommentRateTransaction tx;
    public CommentRateLimit(CommentRateTransaction tx) { this.tx=tx; }
    public int consume(String actor,String remote,String content) {
        try {
            String ip=canonicalPeer(remote);
            String user=digest("user",actor);
            String source=digest("ip",ip);
            String duplicate=digest("duplicate",actor,content);
            tx.cleanup();
            return tx.consume(user,source,duplicate);
        } catch(Exception failure) { throw CommentFailure.unavailable(); }
    }
    private static String canonicalPeer(String remote) throws Exception {
        if (remote==null || remote.length()>45) throw new IllegalArgumentException();
        if (remote.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts=remote.split("\\.",-1);byte[] address=new byte[4];
            for(int i=0;i<4;i++) {
                int octet=Integer.parseInt(parts[i]);if(octet>255) throw new IllegalArgumentException();
                address[i]=(byte)octet;
            }
            return HexFormat.of().formatHex(address);
        }
        if(remote.indexOf(':')<0 || !remote.matches("[0-9A-Fa-f:.]+")) throw new IllegalArgumentException();
        return HexFormat.of().formatHex(InetAddress.getByName(remote).getAddress());
    }
    private static String digest(String... values) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(String value:values) {
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
            hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            hash.update(bytes);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
}
