package com.sunny.guardian.storage;

public interface Storage<T> {
    void set(String key, T value);
    T get(String key);
}
