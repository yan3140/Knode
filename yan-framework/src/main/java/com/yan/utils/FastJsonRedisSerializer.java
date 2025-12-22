package com.yan.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Redis使用FastJson2序列化
 * 注意：此类是为了兼容需要写入类名的场景。
 *
 * @author sg (updated for fastjson2)
 */
public class FastJsonRedisSerializer<T> implements RedisSerializer<T> {
    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    private final Class<T> clazz;

    public FastJsonRedisSerializer(Class<T> clazz) {
        super();
        this.clazz = clazz;
    }

    @Override
    public byte[] serialize(T t) throws SerializationException {
        if (t == null) {
            return new byte[0];
        }
        try {
            // 使用 fastjson2 的新特性来写入类名，确保反序列化时能找到正确的类型
            return JSON.toJSONString(t, JSONWriter.Feature.WriteClassName).getBytes(DEFAULT_CHARSET);
        } catch (Exception ex) {
            throw new SerializationException("Could not serialize object: " + ex.getMessage(), ex);
        }
    }

    @Override
    public T deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length <= 0) {
            return null;
        }
        String str = new String(bytes, DEFAULT_CHARSET);

        try {
            // 使用 fastjson2 的新特性来支持AutoType，这样才能根据序列化的类名正确地反序列化
            return JSON.parseObject(str, clazz, JSONReader.Feature.SupportAutoType);
        } catch (Exception ex) {
            throw new SerializationException("Could not deserialize object: " + ex.getMessage(), ex);
        }
    }
}