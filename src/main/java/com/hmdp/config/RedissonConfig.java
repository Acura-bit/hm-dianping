package com.hmdp.config;

import io.lettuce.core.RedisClient;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Description: Redisson配置类
 * @Author: MyPhoenix
 * @Create: 2023-09-29 9:49
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.197.130:6379").setPassword("1236");

        // 创建 RedissonClient 对象
        return Redisson.create(config);
    }
}
