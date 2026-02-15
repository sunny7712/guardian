package com.sunny.guardian.aspect;

import com.sunny.guardian.annotation.GuardianRateLimit;
import com.sunny.guardian.dto.RateLimitRequest;
import com.sunny.guardian.exception.RateLimitExceededException;
import com.sunny.guardian.ratelimiter.RateLimiter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.stream.IntStream;

@Aspect
@Component
public class RateLimitAspect {
    private final RateLimiter rateLimiter;
    private final ExpressionParser parser = new SpelExpressionParser();

    public RateLimitAspect(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Around("@annotation(guardianRateLimit)")
    public Object enforceLimit(ProceedingJoinPoint joinPoint, GuardianRateLimit guardianRateLimit) throws Throwable {
        EvaluationContext context = buildContext(joinPoint);

        String key = resolveExpression(guardianRateLimit.key(), context, "key");
        String plan = resolveExpression(guardianRateLimit.plan(), context, "plan");
        String quota = resolveExpression(guardianRateLimit.quota(), context, "quota");

        RateLimitRequest request = new RateLimitRequest(key, plan, quota);

        // 4. Ask the RateLimiter
        boolean allowed = rateLimiter.allow(request);

        if (!allowed) {
            throw new RateLimitExceededException("Rate limit exceeded for key: " + key);
        }

        return joinPoint.proceed();
    }

    /**
     * Helper method to parse SpEL expressions.
     * It maps method argument names (e.g., "userId") to their values (e.g., "user_123").
     */
    private String resolveExpression(String expressionString, EvaluationContext evaluationContext, String fieldName) {
        if(expressionString == null || expressionString.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Guardian Rate Limiter: '" + fieldName + "' is mandatory in @GuardianRateLimit"
            );
        }
        try {
            String result = parser.parseExpression(expressionString).getValue(evaluationContext, String.class);
            if (result == null || result.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Guardian Rate Limiter: Evaluated '" + fieldName + "' is null or empty. Expression: " + expressionString
                );
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Guardian Rate Limiter: Failed to parse SpEL expression for '" + fieldName + "': " + expressionString, e
            );
        }
    }

    /**
     * Helper to build the context.
     */
    private EvaluationContext buildContext(ProceedingJoinPoint joinPoint) {
        // 1. Get the Metadata (The Names)
        MethodSignature signature  =  (MethodSignature) joinPoint.getSignature();
        String[] parameterNames = signature.getParameterNames();

        // 2. Get the Actual Values (The Data)
        Object[] args = joinPoint.getArgs();

        // 3. Create the Dictionary (Context)
        EvaluationContext evaluationContext = new StandardEvaluationContext();

        // 4. The Mapping Loop (The Stitching)
        // This loop matches the names to the values by index.
        // i=0 -> context.setVariable("user", "test_user")
        // i=1 -> context.setVariable("plan", "load_test_plan")
        if(parameterNames != null) {
            IntStream.range(0, parameterNames.length).forEach(i -> evaluationContext.setVariable(parameterNames[i], args[i]));
        }
        return evaluationContext;
    }
}
