-- HCR Framework — Rate Limit Token Bucket Script
--
-- Thuật toán Token Bucket: bucket tích lũy token theo thời gian,
-- mỗi request tiêu 1 token. Nếu hết token → từ chối request.
-- Chạy atomically trên Redis — không có race condition.
--
-- KEYS[1] = tokens key   (e.g., "hcr:ratelimit:user-123:tokens")
-- KEYS[2] = timestamp key (e.g., "hcr:ratelimit:user-123:ts")
--
-- ARGV[1] = requested permits (integer, thường = 1)
-- ARGV[2] = permits per second (refill rate)
-- ARGV[3] = burst capacity (max tokens = kích thước bucket)
-- ARGV[4] = current time in milliseconds (System.currentTimeMillis())
--
-- Return: {allowed, remaining_tokens, reset_after_ms, limit_per_second}
--   allowed:          1 = cho phép, 0 = từ chối
--   remaining_tokens: số token còn lại sau khi xử lý
--   reset_after_ms:   ms cho đến khi bucket đầy trở lại (dùng cho Retry-After header)
--   limit_per_second: giới hạn đã config (trả về để client hiển thị)

local tokens_key = KEYS[1]
local ts_key     = KEYS[2]
local requested  = tonumber(ARGV[1])
local rate       = tonumber(ARGV[2])
local capacity   = tonumber(ARGV[3])
local now_ms     = tonumber(ARGV[4])

-- Đọc trạng thái hiện tại của bucket
local last_tokens = tonumber(redis.call('GET', tokens_key))
local last_ts     = tonumber(redis.call('GET', ts_key))

-- Lần đầu tiên: khởi tạo bucket đầy
if last_tokens == nil then
    last_tokens = capacity
    last_ts     = now_ms
end

-- Tính số token được refill kể từ lần cuối
-- rate (tokens/s) × elapsed (s) = tokens được thêm
local elapsed_ms = math.max(0, now_ms - last_ts)
local refilled   = math.floor(elapsed_ms * rate / 1000)
local tokens     = math.min(capacity, last_tokens + refilled)

-- TTL cho Redis keys = thời gian để refill bucket từ rỗng + 1s buffer
-- Nếu không có request trong thời gian này, key tự xóa (tiết kiệm memory)
local ttl_ms = math.ceil(capacity * 1000 / rate) + 1000

-- Thời gian (ms) cho đến khi bucket đầy — dùng cho X-RateLimit-Reset header
local reset_ms = 0
if tokens < capacity then
    reset_ms = math.ceil((capacity - tokens) * 1000 / rate)
end

if tokens >= requested then
    -- Cho phép: tiêu token và lưu trạng thái mới
    local remaining = tokens - requested
    redis.call('SET', tokens_key, remaining, 'PX', ttl_ms)
    redis.call('SET', ts_key,     now_ms,    'PX', ttl_ms)
    return {1, remaining, reset_ms, rate}
else
    -- Từ chối: lưu trạng thái (đã refill) nhưng không tiêu token
    -- Client biết có bao nhiêu token hiện có và khi nào có thể retry
    redis.call('SET', tokens_key, tokens,  'PX', ttl_ms)
    redis.call('SET', ts_key,     now_ms,  'PX', ttl_ms)
    return {0, tokens, reset_ms, rate}
end
