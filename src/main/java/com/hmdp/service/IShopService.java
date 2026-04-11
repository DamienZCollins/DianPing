package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopService extends IService<Shop> {
    /**
     * 根据id查询商铺信息（带Redis缓存）
     * @param id 商铺id
     * @return 商铺详情数据
     */
    Result queryById(Long id);
}
