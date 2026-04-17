package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳（2026-01-01 00:00:00）
     */
    public static final long BEGIN_TIMESTAMP = 1735689600L;

    /**
     * 序列号位数（32位）
     */
    public static final int COUNT_BITS = 32;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一ID
     * @param keyPrefix 业务前缀
     * @return 全局唯一ID
     */
    public long nextId(String keyPrefix) {
        // 1.生成时间戳（31位）
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号（32位）
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增（Redis的incr命令）
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        // 符号位（1位，始终为0，保证正数）| 时间戳（31位）| 序列号（32位）
        return timestamp << COUNT_BITS | count;
    }
}
