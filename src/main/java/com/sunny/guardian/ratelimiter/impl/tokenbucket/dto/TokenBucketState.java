package com.sunny.guardian.ratelimiter.impl.tokenbucket.dto;

import lombok.Data;

@Data
public class TokenBucketState {
    private long lastRefillTimeInEpochMillis;
    private long availableMillisTokens;

}
