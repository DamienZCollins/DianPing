package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1.从Redis查询店铺类型列表缓存
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (shopTypeListJson != null && !shopTypeListJson.isEmpty()) {
            // 3.存在，直接返回
            List<ShopType> typeList = JSONUtil.toList(shopTypeListJson, ShopType.class);
            return Result.ok(typeList);
        }
        // 4.不存在，查询数据库（按sort升序）
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 5.写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        // 6.返回
        return Result.ok(typeList);
    }
}
