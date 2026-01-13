package com.sunny.guardian.storage.impl;

import com.sunny.guardian.storage.Storage;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;


public class RedisTransactionStorage<T> implements Storage<T> {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Class<T> type;

    public RedisTransactionStorage(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper, Class<T> type) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.type = type;
    }

    @Override
    public T compute(@NonNull String key, BiFunction<String, T, T> remappingFunction) {
        while(true) {
            try {
                T result = redisTemplate.execute(new SessionCallback<T>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <K, V> T execute(@NonNull RedisOperations<K, V> operations) throws DataAccessException {
                        RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                        ops.watch(key);

                        String json = ops.opsForValue().get(key);

                        T existingState = null;
                        if(Objects.nonNull(json)) {
                            existingState = objectMapper.readValue(json, type);
                        }

                        T newState = remappingFunction.apply(key, existingState);

                        String newJson = objectMapper.writeValueAsString(newState);

                        ops.multi();
                        ops.opsForValue().set(key, newJson);

                        List<Object> execResults = ops.exec();

                        if(execResults.isEmpty()) {
                            return null;
                        }
                        return newState;
                    }
                });
                if(Objects.nonNull(result)) {
                    return result;
                }
            } catch (Exception e ) {
                throw new RuntimeException("Redis Transaction failed", e);
            }
        }
    }

    @Override
    public T get(String key) {
        String json = redisTemplate.opsForValue().get(key);
        return objectMapper.readValue(json, type);
    }
}
