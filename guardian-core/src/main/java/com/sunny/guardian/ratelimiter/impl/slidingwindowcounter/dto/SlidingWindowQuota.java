package com.sunny.guardian.ratelimiter.impl.slidingwindowcounter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SlidingWindowQuota {
    private long requestLimit;
    private long windowSizeInSeconds;
}
