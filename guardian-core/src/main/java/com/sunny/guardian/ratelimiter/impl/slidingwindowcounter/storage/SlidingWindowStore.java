package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.storage;

import com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto.SlidingWindowQuota;

public interface SlidingWindowStore {
    boolean allowRequest(String key, SlidingWindowQuota quota, long cost);
}
