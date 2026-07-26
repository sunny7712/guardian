local key_prefix = KEYS[1]
local limit = tonumber(ARGV[1])
local window_size_sec = tonumber(ARGV[2])
local current_time_ms = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

local window_size_ms = window_size_sec * 1000

local current_window_start = current_time_ms - (current_time_ms % window_size_ms)
local previous_window_start = current_window_start - window_size_ms

local current_key = key_prefix .. ":" .. current_window_start
local previous_key = key_prefix .. ":" .. previous_window_start

local current_count = tonumber(redis.call("GET", current_key) or "0")
local previous_count = tonumber(redis.call("GET", previous_key) or "0")

-- time_into_window = current_time_sec - current_window_start = current_time_sec - (current_time_sec - (current_time_sec % window_size_ms))
local time_into_window = current_time_ms % window_size_ms
local previous_window_weight = 1 - (time_into_window / window_size_ms)

local estimated_count = math.floor((previous_count * previous_window_weight) + current_count)

if (estimated_count + cost) > limit then
       return 0
else
    redis.call("INCRBY", current_key, cost)
    -- EXPIRE takes seconds, not ms — use window_size_sec (not window_size_ms) here
    redis.call("EXPIRE", current_key, window_size_sec * 2)
    return 1
end

