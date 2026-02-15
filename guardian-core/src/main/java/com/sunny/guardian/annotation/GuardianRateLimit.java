package com.sunny.guardian.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GuardianRateLimit {
    /**
     * The unique key for the rate limit (e.g., user ID, IP address).
     * Supports Spring Expression Language (SpEL).
     * Example: "#userId" or "#request.remoteAddr"
     */
    String key() default "";

    /**
     * The plan to use (e.g., "free", "premium", "load_test_plan").
     */
    String plan() default "default";

    /**
     * The specific quota within the plan (e.g., "read", "write").
     */
    String quota() default "default";
}
