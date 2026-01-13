package com.sunny.guardian.dto;

public record RateLimitRequest (
        String key,
        String plan,
        String quota
) {}
