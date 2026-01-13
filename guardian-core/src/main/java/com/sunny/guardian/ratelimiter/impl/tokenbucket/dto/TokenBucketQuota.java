package com.sunny.guardian.ratelimiter.impl.tokenbucket.dto;

import lombok.Data;

@Data
public class TokenBucketQuota {
    private final long bucketCapacity;
    private final long refillRate;
}
