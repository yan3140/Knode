package com.yan.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * JWT工具类 (已适配 JJWT 0.12.x 版本)
 */
public class JwtUtil {
    //有效期为
    public static final Long JWT_TTL = 60 * 60 * 1000L;// 60 * 60 *1000  一个小时
    //设置秘钥明文 (注意：生产环境中应使用更长的、Base64编码的秘钥)
    public static final String JWT_KEY = "sangengsangengsangengsangengsangengsangenghbhbhhbhbhfcdddgvvvgbnjnj";

    public static String getUUID() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    /**
     * 生成jtw
     *
     * @param subject token中要存放的数据（json格式）
     * @return
     */
    public static String createJWT(String subject) {
        JwtBuilder builder = getJwtBuilder(subject, null, getUUID());// 设置过期时间
        return builder.compact();
    }

    /**
     * 生成jtw
     *
     * @param subject   token中要存放的数据（json格式）
     * @param ttlMillis token超时时间
     * @return
     */
    public static String createJWT(String subject, Long ttlMillis) {
        JwtBuilder builder = getJwtBuilder(subject, ttlMillis, getUUID());// 设置过期时间
        return builder.compact();
    }

    private static JwtBuilder getJwtBuilder(String subject, Long ttlMillis, String uuid) {
        SecretKey secretKey = generalKey();
        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);
        if (ttlMillis == null) {
            ttlMillis = JwtUtil.JWT_TTL;
        }
        long expMillis = nowMillis + ttlMillis;
        Date expDate = new Date(expMillis);

        // 注意：所有 set 方法在新版中都变成了无 set 前缀的同名方法
        return Jwts.builder()
                .id(uuid)              // 唯一的ID
                .subject(subject)   // 主题  可以是JSON数据
                .issuer("sg")     // 签发者
                .issuedAt(now)      // 签发时间
                .signWith(secretKey) // 使用HS256对称加密算法签名, 第二个参数为秘钥
                .expiration(expDate);
    }

    /**
     * 创建token
     *
     * @param id
     * @param subject
     * @param ttlMillis
     * @return
     */
    public static String createJWT(String id, String subject, Long ttlMillis) {
        JwtBuilder builder = getJwtBuilder(subject, ttlMillis, id);// 设置过期时间
        return builder.compact();
    }

    /**
     * 生成加密后的秘钥 secretKey
     *
     * @return
     */
    public static SecretKey generalKey() {
        // 旧的 Base64 解码方式在新版中已不推荐
        // byte[] encodedKey = Base64.getDecoder().decode(JwtUtil.JWT_KEY);
        // return new SecretKeySpec(encodedKey, 0, encodedKey.length, "AES"); // AES 用于加密，而非签名

        // 新的、更安全的方式是使用 Keys.hmacShaKeyFor
        // 它会根据你的字符串自动生成一个安全的 HMAC-SHA 密钥
        byte[] keyBytes = Decoders.BASE64.decode(JWT_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 解析
     *
     * @param jwt
     * @return
     */
    public static Claims parseJWT(String jwt) {
        SecretKey secretKey = generalKey();
        try {
            return Jwts.parser()
                    .verifyWith(secretKey) // 使用 verifyWith 设置签名密钥
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
        } catch (Exception e) {
            // 在实际项目中，这里应该处理各种异常，例如签名验证失败、token过期等
            e.printStackTrace();
            throw new RuntimeException("JWT解析失败", e);
        }
    }
}