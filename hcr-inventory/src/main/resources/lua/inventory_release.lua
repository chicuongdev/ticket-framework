-- HCR Framework — Inventory Release Script (P3 / RedisAtomicStrategy)
--
-- KEYS[1] = inventory key (vd: "hcr:inventory:concert-123")
-- ARGV[1] = quantity cần release (integer)
-- ARGV[2] = totalQuantity (để đảm bảo không vượt quá tổng số lượng ban đầu)
--
-- Return values:
--   >= 0  SUCCESS — trả về số lượng còn lại sau release
--     -1  ERROR   — key chưa được khởi tạo

local key = KEYS[1]
local quantity = tonumber(ARGV[1])
local totalQuantity = tonumber(ARGV[2])

local available = tonumber(redis.call('GET', key))

if available == nil then
    return -1   -- key chưa init
end

-- Tăng available
local newAvailable = redis.call('INCRBY', key, quantity)

-- Đảm bảo không vượt quá totalQuantity (guard against double-release)
if tonumber(newAvailable) > totalQuantity then
    redis.call('SET', key, totalQuantity)
    return totalQuantity
end

return newAvailable
