package com.sunny.guardian.ratelimiter.impl.tokenbucket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenBucketQuota {
    private long bucketCapacity;
    private long refillRate;
}
