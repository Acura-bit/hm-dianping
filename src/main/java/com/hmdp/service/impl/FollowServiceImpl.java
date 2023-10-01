package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.security.Key;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.COMMON_FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /**
     * 关注&取消关注
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        String key = COMMON_FOLLOW_KEY + userId;
        // 2. 判断是关注还是取关
        if (isFollow){
            // 3. 关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isFollow){
                // 把关注用户的 id，放入 Redis 的 set 集合中 sadd userId followUserId
                // 表示当前用户及其关注的用户
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            // 4. 取关，删除。delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 移除当前用户关注的那个用户的 id
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 前段传来当前笔记所属用户的 id，根据此 id 以及当前用户的 id 去 tb_follow 表中查询是否有记录
     *   有记录，说明当前登录用户已关注笔记所属用户
     *   无记录，说明当前登录用户未关注笔记所属用户
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 查询是否关注：select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    /**
     * 求共同关注：求当前登录用户和当前主页用户的共同关注
     * @param id：当前主页用户的 id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = COMMON_FOLLOW_KEY + userId;

        // 2. 求交集
        String key2 = COMMON_FOLLOW_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 3. 解析出 id 集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4. 查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
