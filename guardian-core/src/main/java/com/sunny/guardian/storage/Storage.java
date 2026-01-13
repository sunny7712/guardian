package com.sunny.guardian.storage;

import java.util.function.BiFunction;

public interface Storage<T> {
    T compute(String key, BiFunction<String, T, T> remappingFunction);
    T get(String key);
}
