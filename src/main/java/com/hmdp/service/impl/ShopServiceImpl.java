package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1.从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.命中有效值
        if (StrUtil.isNotBlank(shopJson)) {
            return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
        }
        // 3.命中空值（防穿透）
        if (shopJson != null) {
            return Result.fail("店铺不存在");
        }
        // 4.缓存重建：基于 Redisson 分布式互斥锁
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        boolean isLock = false;
        try {
            // 4.1 非阻塞抢锁，持有期 = LOCK_SHOP_TTL 秒，避免持锁线程崩溃导致长期死锁
            isLock = lock.tryLock(0, LOCK_SHOP_TTL, TimeUnit.SECONDS);
            if (!isLock) {
                // 4.2 抢锁失败：短暂休眠后重试，等待赢家完成重建
                Thread.sleep(50);
                return queryById(id);
            }
            // 4.3 双重检查：拿到锁后再查一次缓存，避免重复回源
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return Result.ok(JSONUtil.toBean(shopJson, Shop.class));
            }
            if (shopJson != null) {
                return Result.fail("店铺不存在");
            }
            // 4.4 回源数据库
            Shop shop = getById(id);
            if (shop == null) {
                // 写入空值防穿透
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }
            // 7.存在，写入Redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.ok(shop);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            // 仅在持锁成功时释放，避免 Redisson 抛 IllegalMonitorStateException
            if (isLock && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 1.判空
        if (shop == null || shop.getId() == null) {
            return Result.fail("店铺ID不能为空");
        }
        // 2.更新数据库
        boolean success = updateById(shop);
        if (!success) {
            return Result.fail("店铺信息更新失败");
        }
        // 3.删除缓存
        String key = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    }
}
