package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.buf.UEncoder;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate; // 操作 string 类型的数据

    /**
     * 发送验证码
     * @param phone
     * @param session
     */
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 3. 符合，生成验证码：6 位
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到 session → 保存到 Redis 中
        // session.setAttribute("code", code); （×）
        // key 手机号；值 验证码
        // 设置键的前缀：唯一标识键
        // 设置过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);

        // 返回 ok
        return Result.ok();
    }

    /**
     * 用户登录和注册
     * @param loginForm
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        // 2. 从 Redis 中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode(); // 读取在前端页面提交的验证码
        // 反向校验：用户没有提交过或验证码过期，导致 cacheCode 为空
        if (cacheCode == null || !cacheCode.equals(code)){
            // 3. 不一致，报错
            return Result.fail("验证码错误");
        }

        // 4. 一致，根据手机号查询用户 select * from tb_user where phone = ?
        // 借助 MyBatis-plus 实现单表查询
        User user = query().eq("phone", phone).one();

        // 5. 判断用户是否存在
        if (user == null){
            // 6. 不存在，创建新用户，并保存
            user = createUserWithPhone(phone);
        }

        // 7. 保存用户信息到 Redis 中
        // 查到用户了，需要将其保存到 session 中；没有查询到用户，为其注册，并保存到 session 中
        // cn.hutool.core.bean.BeanUtil
        // 脱敏，只在 session 中保存 User 的部分信息
        // 7.1 随机生成 token，作为登录令牌
        String token = UUID.randomUUID().toString(true); // 不带中划线

        // 7.2 将 User 对象转为 Hash 类型
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 把对象转为 map，然后使用 Redis 中的 Hash 类型，同时将 UserDTO Long 类型的字段也转化为 String 类型的
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().
                setIgnoreNullValue(true).
                setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // 7.3 存储数据到 Redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap); // userMap

        // 7.4 需要为 token 设置有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES); // 从登录起，30 min 后过期

//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class)); // 会返回给前端，前端将 session_id 存储在 cookie 中


        // 8. 返回 token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(10));
        // 2. 保存用户，到 MySQL 数据库
        save(user);
        return user;
    }
}
