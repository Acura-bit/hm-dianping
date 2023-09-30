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
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 提前读取 lua 脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 当一个线程尝试从队列中获取元素时，若没有元素，线程就会被阻塞，直到
    // 队列中有元素，才会被唤醒，并获取元素
    private BlockingQueue <VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct // 当前类初始化完毕后执行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 内部匿名类：线程任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    // 1. 获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2. 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    // 异步下单
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户：不能从 ThreadLocal 中获取
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 尝试获取锁
        boolean isLock = lock.tryLock(); // 释放不等待
        // 4. 判断是否获取锁
        if (!isLock) {
            // 获取锁失败，返回错误信息
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 基于 Lua 脚本实现秒杀资格验证
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1. 执行 Lua 脚本：判断用户有无购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        // 2. 判断结构是否为 0
        int r = result.intValue();
        if (r != 0) {
            // 2.1 不为 0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2 为 0，有购买资格，把下单信息保存到阻塞队列
        // TODO  保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3 订单 id：全局 id 生成器生成
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4 用户 id：从 ThreadLocal 中取
        voucherOrder.setUserId(userId);
        // 2.5 代金券 id：从请求参数中取
        voucherOrder.setVoucherId(voucherId);
        // 2.6 放到阻塞队列中去
        orderTasks.add(voucherOrder);

        // 业务到这，在逻辑上已经结束了，缓存中已经有订单数据。后续再将订单保存到数据库中

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单 id
        return Result.ok(orderId);
    }

    // 基于 Java 代码实现的，秒杀资格验证
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券 -> 直接查询秒杀券表 tb_seckill_voucher
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }

        // 3. 判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 已经结束
            return Result.fail("秒杀已经结束！");
        }

        // 4. 判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        // 一人一单：单机模式
//        // 先加锁，再提交事务，再释放锁，确保数据库数据已经提交成功
//        synchronized (userId.toString().intern()) { // 去字符串常量池找唯一的 userId，最终只要是同一个用户就会被锁定
//            // VoucherOrderServiceImpl 对象调用方法，导致事务失效
//            // return this.createVoucherOrder(voucherId);
//
//            // TODO 利用代理来生效
//            // 获取当前对象的代理对象，代理对象可以被 AOP 管理，能够执行事务
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
        // 集群模式
        // 锁定 userId，范围较小
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 单机需要关闭复杂均衡
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock(); // 释放不等待
        // 判断是否获取锁
        if (!isLock) {
            // 获取锁失败，返回错误信息
            // 即使同一个人发来了多个请求，也只有一个请求能够成功下单，其他的请求都将失败
            return Result.fail("一个人只允许下一单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5. 一人一单：对于当前的优惠券 voucher_id，判断当前用户 user_id 是否已经下过单了
        // 包装类，就算同一个用户的多个线程过来了，会 new 多个不同的包装类对象 userId
        Long userId = voucherOrder.getUserId();
        // 5.1 查询订单：根据 userId 和 voucherId 查询
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2 判断订单是否存在，
        if (count > 0) {
            // 若存在，说明此用户已经下单了此优惠券，不允许再次下单
            log.error("用户已经购买过一次！");
            return;
        }

        // 6. 扣减库存：满足一人一单，可以下单
        // TODO MyBatis Plus 操作
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // 相当于 set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // 相当于 where id = ? and stock > 0 // 乐观锁需要访问数据库
                .update();

        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }

        // 否则，当前用户可以购买当前优惠券
        // 7. 创建订单
        save(voucherOrder);
    }
}
