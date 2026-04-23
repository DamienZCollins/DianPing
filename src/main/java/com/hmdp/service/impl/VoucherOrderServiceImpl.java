package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    /** Redis Stream 的 key，由 Lua 脚本中 XADD 使用（名称需与脚本内一致） */
    private static final String STREAM_KEY = "stream.orders";
    /** 消费组名 */
    private static final String GROUP_NAME = "g1";
    /** 消费者名称（单机使用固定名，多实例可按 hostname 区分） */
    private static final String CONSUMER_NAME = "c1";
    /** 死信队列 Stream key */
    private static final String STREAM_DLQ_KEY = "stream.orders.dlq";
    /** 每条消息最大投递次数，超过即转 DLQ */
    private static final long MAX_DELIVERY_COUNT = 3L;

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

    /** 秒杀资格判定 Lua 脚本：原子判库存 + 一人一单 + 预扣库存 + XADD 投递 */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 后台消费线程池：手动构造，避免 Executors 工厂类默认的无界队列/无界线程数 → OOM 风险。
     * <ul>
     *   <li>core/max = 1：单消费者，严格串行落库，减轻 DB 压力</li>
     *   <li>队列容量 1：本池只在启动时 submit 一条死循环任务，队列实际不会装载任何元素</li>
     *   <li>AbortPolicy：有任何意外提交直接抛错，暴露问题不隐藏</li>
     *   <li>命名线程：方便 thread dump / 日志定位</li>
     * </ul>
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new NamedThreadFactory("seckill-order-consumer"),
            new ThreadPoolExecutor.AbortPolicy());

    /** 给线程起可读名字：seckill-order-consumer-1 ... */
    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(false); // 非守护，保证应用关闭时能正常接收中断
            return t;
        }
    }

    @PostConstruct
    private void init() {
        initStream();
        SECKILL_ORDER_EXECUTOR.submit(this::handleVoucherOrderTask);
    }

    @PreDestroy
    private void shutdown() {
        log.info("正在关闭秒杀消费线程池...");
        SECKILL_ORDER_EXECUTOR.shutdownNow(); // 中断 handleVoucherOrderTask 中的 XREADGROUP BLOCK
        try {
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("消费线程未能在 5 秒内终止");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 启动时幂等创建 Stream + 消费组（已存在则吞掉 BUSYGROUP） */
    private void initStream() {
        stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
            try {
                connection.streamCommands().xGroupCreate(
                        STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                        GROUP_NAME,
                        ReadOffset.from("0"),
                        true /* mkstream */);
                log.info("Stream [{}] 及消费组 [{}] 初始化完成", STREAM_KEY, GROUP_NAME);
            } catch (Exception e) {
                log.info("Stream 消费组已存在，跳过初始化: {}", e.getMessage());
            }
            return null;
        });
    }

    /** 主消费循环： XREADGROUP 从 ">" 读取新消息 */
    private void handleVoucherOrderTask() {
        while (true) {
            try {
                // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
                if (list == null || list.isEmpty()) {
                    continue; // BLOCK 超时未读到消息，继续轮询
                }
                processRecord(list.get(0));
            } catch (Exception e) {
                log.error("消费 Stream 异常，转入 pending-list 重试", e);
                handlePendingList();
            }
        }
    }

    /** 处理 pending-list：上一轮取走但未 ACK 的消息会留在这里，从 "0" 偏移全部补消费 */
    private void handlePendingList() {
        while (true) {
            try {
                // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    return; // pending-list 清空，回主循环
                }
                processRecord(list.get(0));
            } catch (Exception e) {
                log.error("处理 pending-list 异常，稍后重试", e);
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 解析一条消息 → 落库 → ACK；失败时按投递次数转 DLQ 或保留在 pending-list */
    private void processRecord(MapRecord<String, Object, Object> record) {
        RecordId recordId = record.getId();
        try {
            Map<Object, Object> values = record.getValue();
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(Long.valueOf(values.get("id").toString()));
            voucherOrder.setUserId(Long.valueOf(values.get("userId").toString()));
            voucherOrder.setVoucherId(Long.valueOf(values.get("voucherId").toString()));

            handleVoucherOrder(voucherOrder);

            // 落库成功后 ACK，消息移出 pending-list
            stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, recordId);
        } catch (Exception e) {
            long deliveryCount = queryDeliveryCount(recordId);
            if (deliveryCount >= MAX_DELIVERY_COUNT) {
                // 毒药丸：转入 DLQ 并 ACK 主 Stream，释放 pending-list
                moveToDeadLetter(record, e, deliveryCount);
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, recordId);
                log.error("消息重试 {} 次仍失败，已转入死信队列: id={}, err={}",
                        deliveryCount, recordId, e.getMessage());
                return;
            }
            log.warn("消息处理失败，保留在 pending-list 等待重试: id={}, deliveryCount={}, err={}",
                    recordId, deliveryCount, e.getMessage());
            // 抑制重试风暴：抛出让外层落入 handlePendingList 路径（含 50ms 休眠）
            throw new RuntimeException(e);
        }
    }

    /** 查询指定消息的投递次数（XPENDING ... IDLE 0 <id> <id> 1） */
    private long queryDeliveryCount(RecordId recordId) {
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    STREAM_KEY,
                    Consumer.from(GROUP_NAME, CONSUMER_NAME),
                    Range.closed(recordId.getValue(), recordId.getValue()),
                    1L);
            if (pending == null || pending.isEmpty()) {
                return 0L;
            }
            PendingMessage pm = pending.get(0);
            return pm.getTotalDeliveryCount();
        } catch (Exception e) {
            log.warn("查询 deliveryCount 失败: id={}, err={}", recordId, e.getMessage());
            return 0L; // 查失败时保守估计为 0，让消息继续留在 pending-list
        }
    }

    /** 将毒药丸消息写入死信 Stream（保留完整上下文供人工对账） */
    private void moveToDeadLetter(MapRecord<String, Object, Object> record, Exception cause, long deliveryCount) {
        Map<String, String> payload = new HashMap<>();
        record.getValue().forEach((k, v) -> payload.put(k.toString(), v == null ? "" : v.toString()));
        payload.put("originalId", record.getId().getValue());
        payload.put("deliveryCount", String.valueOf(deliveryCount));
        payload.put("failReason", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        payload.put("failAt", String.valueOf(System.currentTimeMillis()));
        try {
            stringRedisTemplate.opsForStream().add(STREAM_DLQ_KEY, payload);
        } catch (Exception e) {
            log.error("写入死信队列失败: id={}, 仍将 ACK 主 Stream 避免阻塞", record.getId(), e);
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 兼顾崩溃恢复：Lua 已保证一人一单，这里仅作为多进程场景下的兑底
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("订单锁抢占失败, userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }
        try {
            self.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1. 先生成订单 ID，立即返回用户 + 作为消息体随 Lua 写入 Stream
        long orderId = redisIdWorker.nextId("order");
        // 2. Lua 脚本原子判资格 + 预扣库存 + XADD 投递
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        int r = result == null ? 1 : result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不允许重复下单！");
        }
        // 3. Lua 已完成 XADD，直接返回订单 ID
        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 1.一人一单 DB 兑底检查（Lua 已保证原子性，此处防御性）
        int count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if (count > 0) {
            log.error("用户已购买过该优惠券，userId={}, voucherId={}", userId, voucherId);
            return;
        }
        // 2.扣减 DB 库存（乐观锁：gt("stock", 0)）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足，voucherId={}", voucherId);
            return;
        }
        // 3.保存订单
        save(voucherOrder);
    }
}
