local key = KEYS[1]
local bucket_capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local now = tonumber(ARGV[3])

local F_TOKENS = "t"
local F_LAST_REFILL = "r"

-- 1. Get Current state
local fields = redis.call("HMGET", key, F_TOKENS, F_LAST_REFILL)
local current_tokens = tonumber(fields[1])
local last_refill = tonumber(fields[2])

-- 2. Intialize if missing
if current_tokens == nil or last_refill == nil then
    current_tokens = bucket_capacity
    last_refill = now
    else
        -- 3. Refill logic
        local time_elapsed = math.max(0, now - last_refill)
        local tokens_to_add = time_elapsed * refill_rate
        current_tokens = math.min(bucket_capacity, current_tokens + tokens_to_add)
end

-- 4. Consume logic
local allowed  = 0
if current_tokens >= cost then
    allowed = 1
    current_tokens = current_tokens - cost
end

-- 5. Update state
redis.call("HSET", key, F_TOKENS, current_tokens, F_LAST_REFILL, now)

-- 6. Expire key after certain duration
redis.call("EXPIRE", key, 3600)

return allowed
