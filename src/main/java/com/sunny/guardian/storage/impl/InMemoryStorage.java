package com.sunny.guardian.storage.impl;

import com.sunny.guardian.storage.Storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class InMemoryStorage<T> implements Storage<T> {

    private final Map<String, T> store;

    public InMemoryStorage() {
        this.store = new ConcurrentHashMap<>();
    }

    @Override
    public T compute(String key, BiFunction<String, T, T> remappingFunction) {
        return store.compute(key, remappingFunction);
    }

    @Override
    public T get(String key) {
        return store.get(key);
    }

}
