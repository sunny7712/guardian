package com.sunny.guardian.ratelimiter.impl.tokenbucket.dto;


public record TokenBucketState (
        long lastRefillTimeInEpochMillis,
        long availableTokens
) {}
