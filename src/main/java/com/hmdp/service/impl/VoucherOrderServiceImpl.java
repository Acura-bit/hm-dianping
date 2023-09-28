package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
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
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 尝试获取锁
        boolean isLock = lock.tryLock(1200);
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

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5. 一人一单：对于当前的优惠券 voucher_id，判断当前用户 user_id 是否已经下过单了
        // 包装类，就算同一个用户的多个线程过来了，会 new 多个不同的包装类对象 userId
        Long userId = UserHolder.getUser().getId();
        // 5.1 查询订单：根据 userId 和 voucherId 查询
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2 判断订单是否存在，
        if (count > 0) {
            // 若存在，说明此用户已经下单了此优惠券，不允许再次下单
            return Result.fail("用户已经购买过一次！");
        }

        // 6. 扣减库存：满足一人一单，可以下单
        // TODO MyBatis Plus 操作
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // 相当于 set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // 相当于 where id = ? and stock > 0 // 乐观锁需要访问数据库
                .update();

        if (!success) {
            // 扣减失败
            // 库存不足
            return Result.fail("库存不足！");
        }

        // 否则，当前用户可以购买当前优惠券
        // 7. 创建订单：因为需要操作共享资源，所以存在线程安全问题
        // 表 tb_voucher_order  订单 id、用户 id、代金券 id
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 订单 id：全局 id 生成器生成
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 用户 id：从 ThreadLocal 中取
        voucherOrder.setUserId(userId);
        // 7.3 代金券 id：从请求参数中取
        voucherOrder.setVoucherId(voucherId);
        // 7.4 保存到数据库
        save(voucherOrder);

        // 8. 返回订单 id
        return Result.ok(orderId);
    }
}
