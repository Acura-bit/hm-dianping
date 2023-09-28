package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁：非阻塞模式，只尝试一次
     * @param timeoutSec：锁持有时间，过期后自动释放
     * @return true 释放锁成功；false 释放锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
