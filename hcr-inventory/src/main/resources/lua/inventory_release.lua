-- HCR Framework — Inventory Release Script (P3 / RedisAtomicStrategy)
--
-- KEYS[1] = inventory key (vd: "hcr:inventory:concert-123")
-- KEYS[2] = total key (vd: "hcr:inventory:total:concert-123")
-- ARGV[1] = quantity cần release (integer)
--
-- Return values:
--   >= 0  SUCCESS — trả về số lượng còn lại sau release
--     -1  ERROR   — key chưa được khởi tạo
--
-- Revision: đọc totalQuantity từ Redis (KEYS[2]) thay vì truyền qua ARGV
-- → giảm từ 2 round-trip xuống 1 ở Java side.

local key = KEYS[1]
local totalKey = KEYS[2]
local quantity = tonumber(ARGV[1])

local available = tonumber(redis.call('GET', key))

if available == nil then
    return -1   -- key chưa init
end

-- Đọc total trực tiếp từ Redis — không cần Java GET riêng
local totalStr = redis.call('GET', totalKey)
local totalQuantity
if totalStr then
    totalQuantity = tonumber(totalStr)
else
    totalQuantity = nil
end

-- Tăng available
local newAvailable = redis.call('INCRBY', key, quantity)

-- Đảm bảo không vượt quá totalQuantity (guard against double-release)
if totalQuantity and tonumber(newAvailable) > totalQuantity then
    redis.call('SET', key, totalQuantity)
    return totalQuantity
end

return newAvailable
