package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 缓存穿透：利用封装的工具类
         Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        // 用互斥锁解决缓存击穿
        // 该场景下，此店铺 id 即为热点 key，存在潜在的缓存击穿可能性
        // Shop shop = queryWithMutex(id);

        // 用逻辑过期解决缓存击穿
        // Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }

        // 7. 返回
        return Result.ok(shop);
    }


    /**
     * 将热点数据添加到 Redis 中
     * @param id
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1. 查询店铺数据
        Shop shop = getById(id);

        Thread.sleep(2000);

        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3. 写入 Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    // 创建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 解决缓存击穿的方案一：逻辑过期
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1. 从 Redis 中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否命中缓存
        if (StrUtil.isBlank(shopJson)) {
            // 3. 不存在，直接返回
            return null;
        }

        // 4. 命中缓存，需要先把 JSON 反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 5.1 未过期，直接返回
            return shop;
        }

        // 5.2 已过期，需要缓存重建
        // 6. 缓存重建
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);

        // 6.2 判断是否获取锁成功
        if (isLock) {
            // 6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 6.4 返回商铺信息（过期的）
        return shop;
    }

    /**
     * 解决缓存击穿的方案一：互斥锁
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1. 从 Redis 中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否命中缓存
        if (StrUtil.isNotBlank(shopJson)) {
            // 2.1.1 为 true，命中缓存，不会发生缓存击穿问题
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 2.1.2 命中了缓存穿透设置的空字符串 ""
        if (shopJson != null){
            return null;
        }

        // 3. 发生了缓存击穿，开始实现缓存重建
        // 3.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 尝试上锁
            boolean isLock = tryLock(lockKey);

            // 3.2 判断是否获取成功
            if (!isLock){
                // 3.3 获取失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id); // 重试的过程中可能是 L61 命中缓存，也可能获取到锁 L80
            }

            // 3.4 执行 3.5 之前，获取锁成功后应该再次检测 Redis 缓存是否存在，做 DoubleCheck，如果存在则无需重建缓存
            // 因为获取锁后，可能是别的线程已经重建完缓存了，就不需要重建缓存了
            String shopJson1 = stringRedisTemplate.opsForValue().get(key);
            // 判断是否命中缓存
            if (StrUtil.isNotBlank(shopJson1)) {
                // 为 true，命中缓存，不会发生缓存击穿问题
                return JSONUtil.toBean(shopJson1, Shop.class);
            }

            // 3.5 成功，根据 id 查询数据库
            shop = getById(id);

            // 模拟重建缓存的延时
            Thread.sleep(200);

            // 5. 如果数据库中也不存在此数据，将空字符串写入 Redis
            if (shop == null) {
                // 将空值写入 Redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }

            // 6. 如果数据库中存在此数据，写入 Redis，并设置超时时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放互斥锁
            unlock(lockKey);
        }

        // 8. 返回
        return shop;
    }

    /**
     * 封装缓存穿透的代码
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;

        // 1. 从 Redis 中查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 判断是否命中缓存
        /**
         * StrUtil.isNotBlank(null) // false
         * StrUtil.isNotBlank("") // false
         * StrUtil.isNotBlank(" \t\n") // false
         * StrUtil.isNotBlank("abc") // true
         */
        if (StrUtil.isNotBlank(shopJson)) {
            // 2.1.1 为 true，说明缓存中有店铺数据，命中缓存存，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 2.1.2 命中后，需要判断 shopJson 是否为 ""，为 ""，说明缓存了空字符串，发生了缓存穿透问题
        if (shopJson != null){
            return null;
        }


        // 3. 未命中缓存，根据 id 查询数据库
        Shop shop = getById(id);

        // 3.1 如果数据库中也不存在此数据，将空字符串写入 Redis
        if (shop == null) {
            // 将空值写入 Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }

        // 3.2 如果数据库中存在此数据，写入 Redis，并设置超时时间
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 4. 返回
        return shop;
    }

    /**
     * 尝试上锁：string 中的 setnx key value
     * 当 key 不存在时，才能创建 key 并赋值成功。
     * 因此，可以用来做互斥锁，只有第一个到来的线程才能设置 key，后来的线程都无法设置，
     * 从而模拟互斥锁
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁：直接删除键，那么后续的线程就可以设置 setnx key value 了，
     * 相当于能够获得互斥锁
     * @param key
     * @return
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据可
        updateById(shop);

        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
