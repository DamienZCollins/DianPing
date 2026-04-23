-- 秒杀资格判定脚本：原子地判断库存、一人一单、预扣库存，并把订单消息投入 Stream
--
-- ARGV[1] = voucherId
-- ARGV[2] = userId
-- ARGV[3] = orderId（由 Java 端雪花算法生成，一并写入消息体）
--
-- 返回值：
--   0 = 抢购成功（已预扣库存、已记录该用户、已 XADD 投递消息）
--   1 = 库存不足
--   2 = 该用户已购买过

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

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

-- 3. 预扣库存 + 记录购买用户 + 投递订单消息到 Stream
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.orders', '*',
    'id', orderId,
    'userId', userId,
    'voucherId', voucherId)
return 0
