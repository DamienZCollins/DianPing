-- 秒杀资格判定脚本：原子地判断库存与一人一单，并完成预扣库存
--
-- ARGV[1] = voucherId
-- ARGV[2] = userId
--
-- 返回值：
--   0 = 抢购成功（已预扣库存、已记录该用户）
--   1 = 库存不足
--   2 = 该用户已购买过

local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1. 库存判断（key 不存在视为无库存）
local stock = tonumber(redis.call('get', stockKey))
if (stock == nil or stock <= 0) then
    return 1
end

-- 2. 一人一单判断
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 3. 预扣库存 + 记录购买用户
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
