package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private IVoucherOrderService self;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        // 5.加分布式锁（锁必须包裹事务，确保 unlock 在事务提交之后，避免并发下一人多单）
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            return Result.fail("不允许重复下单！");
        }
        try {
            // 6.创建订单（通过代理调用以触发事务）
            return self.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
    }


    
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 5.1.一人一单校验（先查Redis缓存）
        String cacheKey = "order:" + voucherId + ":" + userId;
        Boolean isPurchased = stringRedisTemplate.hasKey(cacheKey);
        if (Boolean.TRUE.equals(isPurchased)) {
            return Result.fail("用户已经购买过一次！");
        }
        // 5.2.缓存未命中，查数据库
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            // 缓存购买记录，TTL设置为优惠券结束时间
            stringRedisTemplate.opsForValue().set(cacheKey, "1", 24, TimeUnit.HOURS);
            return Result.fail("用户已经购买过一次！");
        }
        // 6.扣减库存（乐观锁）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单ID
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户ID
        voucherOrder.setUserId(userId);
        // 7.3.优惠券ID
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 7.4.删除购买记录缓存（Cache-Aside：避免事务回滚后缓存与DB不一致；下次查询由 count>0 分支自动回填）
        stringRedisTemplate.delete(cacheKey);
        // 8.返回订单ID
        return Result.ok(orderId);
    }
}
