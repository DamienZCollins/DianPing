package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.net.NetUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的简单分布式锁实现（优化版）：
 * 1) 使用 SET NX EX 抢锁
 * 2) 使用 Lua 脚本原子解锁（先比对锁标识，再删除）
 * 3) 添加机器 IP 保证锁标识全局唯一
 * 4) 实现看门狗自动续期机制
 */
public class SimpleRedisLock implements ILock {
    // Redis 锁 key 前缀
    private static final String KEY_PREFIX = "lock:";
    // 机器 IP + UUID + 线程ID 组成锁持有者标识，保证分布式环境唯一性
    private static final String ID_PREFIX = getMachineIp() + "-" + UUID.randomUUID().toString(true) + "-";
    // 解锁脚本：保证 "校验持有者 + 删除" 的原子性
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // 脚本文件放在 resources 根目录
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    // 业务锁名称（会拼接到 lock: 前缀后）
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    // 当前线程的锁标识
    private final String lockId;
    // 看门狗线程
    private Thread watchDogThread;
    // 是否启用看门狗
    private volatile boolean watchDogEnabled = false;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockId = ID_PREFIX + Thread.currentThread().getId();
    }

    /**
     * 获取本机 IP 地址
     */
    private static String getMachineIp() {
        try {
            String ip = NetUtil.getLocalhostStr();
            return ip != null ? ip.replace(".", "") : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, lockId, timeoutSec, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(success)) {
            // 启动看门狗自动续期
            startWatchDog(timeoutSec);
            return true;
        }
        return false;
    }

    /**
     * 启动看门狗线程，自动续期
     * @param timeoutSec 锁的超时时间（秒）
     */
    private void startWatchDog(long timeoutSec) {
        watchDogEnabled = true;
        watchDogThread = new Thread(() -> {
            while (watchDogEnabled) {
                try {
                    // 每隔 timeoutSec / 3 时间续期一次（与 Redisson 一致）
                    Thread.sleep(timeoutSec * 1000 / 3);
                    if (watchDogEnabled) {
                        // 续期：重新设置过期时间
                        stringRedisTemplate.expire(KEY_PREFIX + name, timeoutSec, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        watchDogThread.setDaemon(true);
        watchDogThread.start();
    }

    @Override
    public void unlock() {
        // 停止看门狗
        stopWatchDog();
        // 仅当当前线程是锁持有者时才允许删除锁
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                lockId
        );
    }

    /**
     * 停止看门狗线程
     */
    private void stopWatchDog() {
        watchDogEnabled = false;
        if (watchDogThread != null && watchDogThread.isAlive()) {
            watchDogThread.interrupt();
        }
    }
}
