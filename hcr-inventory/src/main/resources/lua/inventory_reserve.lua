-- HCR Framework — Inventory Reserve Script (P3 / RedisAtomicStrategy)
--
-- Được Redis thực thi atomically — không có command nào khác chạy xen vào.
-- Đảm bảo zero-oversell: không có race condition giữa GET và DECRBY.
--
-- KEYS[1] = inventory key (vd: "hcr:inventory:concert-123")
-- ARGV[1] = quantity cần reserve (integer)
--
-- Return values:
--   >= 0  SUCCESS — trả về số lượng còn lại sau khi reserve
--     -1  ERROR   — key chưa được khởi tạo (InventoryInitializer chưa chạy)
--     -2  FAIL    — không đủ hàng (INSUFFICIENT)

local key = KEYS[1]
local quantity = tonumber(ARGV[1])

local available = tonumber(redis.call('GET', key))

if available == nil then
    return -1   -- key chưa init
end

if available < quantity then
    return -2   -- không đủ hàng
end

-- Atomic decrement — trả về số còn lại
return redis.call('DECRBY', key, quantity)
