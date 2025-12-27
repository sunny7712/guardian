package com.sunny.guardian.storage.impl;

import com.sunny.guardian.storage.Storage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class InMemoryStorage<T> implements Storage<T> {

    private final ReadWriteLock readWriteLock;
    private final Map<String, T> store;

    public InMemoryStorage() {
        this.readWriteLock = new ReentrantReadWriteLock();
        this.store = new HashMap<>();
    }

    @Override
    public void set(String key, T value) {
        readWriteLock.writeLock().lock();
        try {
            store.put(key, value);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    @Override
    public T get(String key) {
        readWriteLock.readLock().lock();
        try {
            return store.get(key);
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

}
