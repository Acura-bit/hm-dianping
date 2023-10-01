package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注与取关
     * @param followUserId：当前笔记所属用户的 id（待关注用户 id）
     * @param isFollow：状态，关注 or 未关注
     * @return
     */
    @PutMapping("/{id}/{isFollow}") // .../follow/2/true
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 判断当前登录用户是否已关注笔记所属用户
     * @param followUserId：当前笔记所属用户 id
     * @return
     */
    @GetMapping("/or/not/{id}") // .../follow/2/true
    public Result isFollow(@PathVariable("id") Long followUserId){
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }
}
