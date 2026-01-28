package com.sunny.guardian.ratelimiter.impl.tokenbucket.storage;

import com.sunny.guardian.ratelimiter.impl.tokenbucket.dto.TokenBucketQuota;

public interface TokenBucketStore {
    boolean allowRequest(String key, TokenBucketQuota quota, long costTokens);
}
