package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.SystemConstants;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

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
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 0. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 1. 计算分页参数：起始索引 from 和结束索引 end
        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
        int from = (current - 1) * pageSize;
        int end = current * pageSize;

        
        // 2. 构建 Redis Geo 查询 Key
        String key = SHOP_GEO_KEY + typeId;

        // 3. 执行 Geo 搜索查询：以 (x, y) 为圆心，5 千米为半径，按距离升序排序，取前 end 条记录
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance() // 包含距离信息
                                .sortAscending()   // 按距离升序
                                .limit(end)        // 限制返回数量
                );

        // 4. 判断查询结果是否为空
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        // 5. 获取结果列表
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        // 6. 判断是否还有数据可供当前页展示（如果总记录数小于起始索引，则无数据）
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        // 7. 解析结果，提取店铺 ID 和距离信息
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageList = list.subList(from, Math.min(end, list.size()));
        List<Long> ids = new ArrayList<>(pageList.size());
        Map<String, Distance> distanceMap = new HashMap<>(pageList.size());
        // 跳过前 from 条记录，只处理当前页所需的数据
        pageList.forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        });

        // 8. 根据 ID 列表查询数据库中的店铺详细信息，并保持与 Geo 查询相同的顺序
        String idStr = StrUtil.join(",", ids);
        // ORDER BY FIELD 保证查询结果顺序与 ids 列表一致
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        // 9. 将距离信息填充到店铺对象中
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }

        // 10. 返回结果
        return Result.ok(shops);
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
