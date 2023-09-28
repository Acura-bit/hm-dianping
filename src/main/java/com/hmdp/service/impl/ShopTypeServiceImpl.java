package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getList() {
        String key = CACHE_SHOP_TYPE_KEY;
        List<String> shopTypeList = new ArrayList<>();
        // 1. 在 Redis 中查找
        shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);

        // 2. 如果找到，返回
        if (!shopTypeList.isEmpty()) {
            List<ShopType> typeList = new ArrayList<>();
            for (String s : shopTypeList) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);
        }

        // 3. 不存在，去 MySQL 数据库查询，若数据库中没记录，会返回一个空集合，空集合和 null 不一样
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 4. 不存在，返回错误
        if (typeList.isEmpty()){
            return Result.fail("不存在分类！");
        }

        // 5. 存在，保存到 Redis 中
        for (ShopType shopType : typeList) {
            String s = JSONUtil.toJsonStr(shopType);
            shopTypeList.add(s);
        }

        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList);

        // 6. 并返回
        return Result.ok(typeList);
    }
}
