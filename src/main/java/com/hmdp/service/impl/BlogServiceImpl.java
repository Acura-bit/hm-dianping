package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import com.hmdp.utils.RedisConstants;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    @Resource
    private IFollowService followService;

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

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店笔记
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败！");
        }
        // 查询笔记作者的所有粉丝，推送笔记 id 给所有粉丝 select * from tb_follow where follow_user_id = user_id
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        // 推送笔记给所有粉丝
        for (Follow follow : follows) {
            // 获取粉丝 id
            Long userId = follow.getUserId();
            // 4.2 推送，将当前笔记的 id 保存到 Redis 中。粉丝的 id 为键，笔记 id 为值
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回 id
        return Result.ok(blog.getId());
    }

    /**
     * 动态分页查询收件箱中博主的推送
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();

        // 2. 查询收件箱：zrevrangebyscore key max 0 limit offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        // 3. 非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        // 4. 解析数据：blogId、minTime（时间戳）、offset（跟 minTime 相同的元素的个数）
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1 获取 id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2 获取分数（时间戳）
            long time = tuple.getScore().longValue();
            // TODO 在一趟遍历中找到最小值及其出现次数（骚）
            if (time == minTime) {
                os++;
            }else {
                minTime = time;
                os = 1;
            }
        }

        // 5. 根据 id 查询 blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1 查询 blog 有关的用户
            queryBlogUser(blog);

            // 5.2 查询是否被点赞
            isBlogLiked(blog);
        }

        // 6. 封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
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
