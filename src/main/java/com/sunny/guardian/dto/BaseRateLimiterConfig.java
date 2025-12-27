package com.sunny.guardian.dto;

import lombok.Data;

import java.util.Map;

@Data
public class BaseRateLimiterConfig<T>{
    private Map<String, Map<String, T>> plans;
}
