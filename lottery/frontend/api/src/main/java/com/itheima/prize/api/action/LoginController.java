package com.itheima.prize.api.action;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.itheima.prize.commons.config.RedisKeys;
import com.itheima.prize.commons.db.entity.CardUser;
import com.itheima.prize.commons.db.mapper.CardUserMapper;
import com.itheima.prize.commons.db.service.CardUserService;
import com.itheima.prize.commons.utils.ApiResult;
import com.itheima.prize.commons.utils.PasswordUtil;
import com.itheima.prize.commons.utils.RedisUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.List;

@RestController
@RequestMapping(value = "/api")
@Api(tags = {"登录模块"})
public class LoginController {
    @Autowired
    private CardUserService userService;

    private final int LOCK_TIME=60 * 5;
    private final int LOGIN_LIMITS=5;
    private final int LOGIN_LIMIT_TIME=60*5;

    @Autowired
    private RedisUtil redisUtil;

    @PostMapping("/login")
    @ApiOperation(value = "登录")
    @ApiImplicitParams({
            @ApiImplicitParam(name="account",value = "用户名",required = true),
            @ApiImplicitParam(name="password",value = "密码",required = true)
    })
    public ApiResult login(HttpServletRequest request, @RequestParam String account,@RequestParam String password) {

        password=PasswordUtil.encodePassword(password);
        String key=RedisKeys.USERLOGINTIMES+account;
        Integer value=(Integer) redisUtil.get(key);
        if (value!=null&&value>=LOGIN_LIMITS) { // 已经被锁定
            return new ApiResult<>(0, "密码错误5次，请5分钟后登录", null);
        }
            CardUser user = userService.getOne(new QueryWrapper<CardUser>().eq("uname", account).eq("passwd", password));
            if (user == null) {
                redisUtil.incr(key,1);
                redisUtil.expire(key, LOGIN_LIMIT_TIME);
                return new ApiResult<>(0, "用户名或密码错误", null);
            }
            //脱敏
            user.setIdcard(null);
            user.setPasswd(null);
            HttpSession session = request.getSession();
            session.setAttribute("user", user);
            return new ApiResult<>(1, "登录成功", user);
    }

    @GetMapping("/logout")
    @ApiOperation(value = "退出")
    public ApiResult logout(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            return new ApiResult<>(1, "退出成功", null);
    }

}