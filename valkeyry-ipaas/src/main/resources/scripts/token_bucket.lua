-- Atomic token-bucket rate limiter for Valkey.
-- KEYS[1] = bucket key
-- ARGV[1] = capacity
-- ARGV[2] = refill_tokens (per refill_period)
-- ARGV[3] = refill_period_seconds
-- ARGV[4] = now (epoch seconds)
-- Returns: { allowed (1|0), remaining_tokens }
local key             = KEYS[1]
local capacity        = tonumber(ARGV[1])
local refill_tokens   = tonumber(ARGV[2])
local refill_period   = tonumber(ARGV[3])
local now             = tonumber(ARGV[4])

local data            = redis.call('HMGET', key, 'tokens', 'ts')
local tokens          = tonumber(data[1])
local ts              = tonumber(data[2])

if tokens == nil then
  tokens = capacity
  ts = now
end

local elapsed = math.max(0, now - ts)
local add = math.floor(elapsed * (refill_tokens / refill_period))
if add > 0 then
  tokens = math.min(capacity, tokens + add)
  ts = now
end

local allowed = 0
if tokens > 0 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
redis.call('EXPIRE', key, refill_period * 2)

return { allowed, tokens }
