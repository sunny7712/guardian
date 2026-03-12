package com.sunny.guardian.controller;

import com.sunny.guardian.annotation.GuardianRateLimit;
import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.ratelimiter.RateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RateLimitTestController {

    private final RateLimiter rateLimiter;

    public RateLimitTestController(
            @Qualifier("tokenBucketRateLimiter") RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("PONG");
    }

    @GetMapping("/limit-token-bucket-manual")
    public ResponseEntity<String> checkLimit(
            @RequestParam(defaultValue = "test_user") String user,
            @RequestParam(defaultValue = "load_test_plan") String plan,
            @RequestParam(defaultValue = "default") String quota
    ) {
        RateLimitRequest request = new RateLimitRequest(user, plan, quota);

        boolean allowed = rateLimiter.allow(request);

        if (allowed) {
            return ResponseEntity.ok("ALLOWED");
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("BLOCKED");
        }
    }

    @GetMapping("/limit-token-bucket")
    @GuardianRateLimit(key = "#user", plan = "load_test_plan", quota = "default")
    public ResponseEntity<String> checkLimitAnnotation(
            @RequestParam(defaultValue = "test_user") String user
    ) {
        return ResponseEntity.ok("ALLOWED");
    }

    @GetMapping("/limit-sliding-window-counter")
    @GuardianRateLimit(algorithm = "slidingWindowCounterRateLimiter", key = "#user", plan = "strict_plan")
    public ResponseEntity<String> checkSlidingLimit(@RequestParam(defaultValue = "test_user") String user) {
        return ResponseEntity.ok("ALLOWED BY SLIDING WINDOW");
    }


}
