package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @Description: 唯一主键生成器
 * @Author: MyPhoenix
 * @Create: 2023-09-27 16:53
 */
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1640995200L;
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * @param keyPrefix：用于不同业务的前缀
     */
    public long nextId(String keyPrefix){
        // 1. 生成时间戳
        // TODO 日期时间 API 的使用
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        // 2. 生成序列号
        // 每天下的单产生一个 key
        // 2.1 获取到当前日期，精确到天
        // 2.2 自增长，并且可以统计每天、每月、每年的订单量
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // return the value of key after the increment
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 拼接并返回
        // 不能直接拼接时间戳和序列号，因为拼接产生的是一个字符串，而我们想要的是一个 long 类型的值
        return timeStamp << COUNT_BITS | count;
    }
}
