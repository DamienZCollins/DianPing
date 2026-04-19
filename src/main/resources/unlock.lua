-- 原子解锁脚本：
-- 1) 先比对锁的 value 是否是当前线程标识
-- 2) 一致才删除，避免误删其他线程/实例的锁
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
    return redis.call('DEL', KEYS[1])
end
-- 不一致则不做处理
return 0
