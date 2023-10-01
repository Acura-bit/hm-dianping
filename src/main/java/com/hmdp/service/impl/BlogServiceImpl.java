package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.hmdp.utils.RedisConstants;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询 blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }

        // 2. 查询 blog 有关的用户
        queryBlogUser(blog);

        // 3. 查询是否被点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    /**
     * 点赞或取消点赞：所需数据结构的特点，① 存储多个；② 唯一；
     * 先在 Redis 中查询以该笔记为键的 zset 是否存在当前用户的 id：
     *    若不存在，说明当前用户未点赞该笔记，则执行点赞逻辑：
     *      更新数据库该笔记的点赞数 +1
     *      将当前用户 id 作为 value 插入 zset 的 key 中
     *    若已存在，说明当前用户已点赞过该笔记，则执行取消点赞的逻辑：
     *      更新数据库该笔记的点赞数 -1
     *      移除笔记 key 对应的 value 中当前用户的 id
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();

        // 2. 判断当前用户是否点赞过
        String key = "blog:liked:" + id;
        // zscore key member：查询 member 对应的 score，曲线救国实现判断当前用户是否已点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            // 3. 如果未点赞，可以点赞
            // 3.1 数据库点赞数 +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2 保存用户到 Redis 的 Zset 集合中：key 笔记 id，value userId。zadd key value score
            if (isSuccess) { // 时间戳作为 score
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            // 4. 如果已点赞
            // 4.1 数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 把用户从 Redis 的 Zset 集合中移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    /**
     * 查询当前笔记的 top5 点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 1. 查询 top5 点赞用户 zrange key 0 4：查到的是用户 id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(RedisConstants.BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        // 2. 解析出其中的用户 id
        // TODO Stream 流够骚
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        // 3. 根据用户 id 查询用户
        // User -> UserDTO
        // where id in (5, 1) order by field(id, 5, 1);
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        // 4. 返回
        return Result.ok(userDTOS);
    }

    /**
     * 根据 blog 查询用户
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 从 Redis 中查询该笔记是否被点赞，如果被点赞了，需要更新 Blog 中的 isLike 属性为 true（已点赞）
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            // 用户未登录，如浏览首页面不需要查询是否点赞
            return;
        }

        Long userId = user.getId();

        // 2. 判断当前用户是否点赞过
        String key = "blog:liked:" + blog.getId();
        // 判断 Redis 的 set 中是否存在该 key
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // TODO 够骚的写法
        blog.setIsLike(score != null);
    }
}
