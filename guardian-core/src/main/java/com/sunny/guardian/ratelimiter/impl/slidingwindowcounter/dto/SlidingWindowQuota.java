package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto;

import lombok.Data;

@Data
public class SlidingWindowQuota {
    private final long requestLimit;
    private final long windowSizeInSeconds;
}
