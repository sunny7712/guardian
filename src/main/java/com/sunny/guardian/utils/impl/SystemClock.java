package com.sunny.guardian.utils.impl;

import com.sunny.guardian.utils.GuardianClock;
import org.springframework.stereotype.Component;

@Component
public class SystemClock implements GuardianClock {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

}
