package com.hmdp.utils;

/**
 * 分布式锁接口，定义最基础的加锁/解锁能力。
 */
public interface ILock {
    /**
     * 尝试获取锁。
     *
     * @param timeoutSec 锁的超时时间（秒）
     * @return true 表示加锁成功
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁。
     */
    void unlock();
}
