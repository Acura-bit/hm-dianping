package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @Description: 拦截器
 * @Author: MyPhoenix
 * @Create: 2023-09-23 8:53
 */

public class RefreshTokenInterceptor implements HandlerInterceptor {
    // 这里无法直接注入 StringRedisTemplate 的对象， 因为此类未交由 IOC 容器管理
    private StringRedisTemplate stringRedisTemplate;

    // 只能使用构造器注入
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取请求头中获取 token
        String token = request.getHeader("authorization");
//        System.out.println("登录token = " + token);
        /**
         * 有两种情况：
         * ① 未登录用户访问首页面，应该放行
         * ② 登录用户访问非首页面功能，但某种原因导致刚被拦截，token 就失效，不能放行
         *    极端情况：29 min 59 s 发出请求，被拦截时就失效了
         * 综上，显然只使用这一层拦截器是无法实现上述复杂逻辑的
         */
        if (StrUtil.isBlank(token)) {
            return true;
        }

        // 2. 基于 token 获取 Redis 中的用户
        // 获取键对应的值中的所有键值对（属性-属性值对）
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().
                entries(key);  // 已经判空

        // 3. 判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }

        // 5. 将查询到的 Hash 数据转为 UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false); // 不会略报错

        // 6. 存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(userDTO);

        // 7. 刷新 token 有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8. 放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，避免内存泄漏
        UserHolder.removeUser();
    }
}
