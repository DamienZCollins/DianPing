package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀优惠券
     * @param voucherId 优惠券ID
     * @return 订单ID
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 异步消费：把已经通过 Redis 资格判定的订单写入数据库。
     * 由后台消费线程通过 AOP 代理调用，事务边界在此方法。
     *
     * @param voucherOrder 已经在生产者侧填好 id/userId/voucherId 的订单
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
