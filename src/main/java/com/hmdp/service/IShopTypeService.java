package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IShopTypeService extends IService<ShopType> {
    /**
     * 查询店铺类型列表（带Redis缓存）
     * @return 店铺类型列表
     */
    Result queryTypeList();
}
