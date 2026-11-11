package io.towersofhanoi.serialization;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Jackson-based Redis serializer that embeds type information so arbitrary objects
 * can be deserialized without compile-time type knowledge.
 *
 * <p>Accepts a custom {@link ObjectMapper} for applications that need to restrict
 * deserializable types or add custom modules:
 * <pre>
 *   ObjectMapper mapper = new ObjectMapper();
 *   mapper.activateDefaultTyping(...);
 *   new GenericJacksonRedisSerializer(mapper);
 * </pre>
 */
public class GenericJacksonRedisSerializer implements RedisSerializer<Object> {

    private final ObjectMapper objectMapper;

    public GenericJacksonRedisSerializer() {
        this(buildDefaultMapper());
    }

    public GenericJacksonRedisSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] serialize(@Nullable Object value) throws SerializationException {
        if (value == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new SerializationException("Could not serialize cache value: " + e.getMessage(), e);
        }
    }

    @Override
    @Nullable
    public Object deserialize(@Nullable byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readValue(bytes, Object.class);
        } catch (IOException e) {
            throw new SerializationException(
                "Could not deserialize: " + new String(bytes, StandardCharsets.UTF_8), e);
        }
    }

    private static ObjectMapper buildDefaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // NON_FINAL typing embeds @class metadata so round-tripping works for any non-primitive type.
        // allowIfSubType(Object.class) is permissive by design — this is a general-purpose cache.
        // Restrict this in security-sensitive environments by supplying a custom ObjectMapper.
        mapper.activateDefaultTyping(
            BasicPolymorphicTypeValidator.builder()
                .allowIfSubType(Object.class)
                .build(),
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
