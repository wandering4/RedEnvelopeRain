package com.itheima.prize.api.action;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.prize.api.config.LuaScript;
import com.itheima.prize.commons.config.RabbitConfig;
import com.itheima.prize.commons.config.RabbitKeys;
import com.itheima.prize.commons.config.RedisKeys;
import com.itheima.prize.commons.db.entity.*;
import com.itheima.prize.commons.db.mapper.CardGameMapper;
import com.itheima.prize.commons.db.service.CardGameService;
import com.itheima.prize.commons.utils.ApiResult;
import com.itheima.prize.commons.utils.RedisUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import springfox.documentation.spring.web.json.Json;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.util.*;

@RestController
@RequestMapping("/api/act")
@Api(tags = {"抽奖模块"})
public class ActController {

    @Autowired
    private RedisUtil redisUtil;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private LuaScript luaScript;

    @GetMapping("/limits/{gameid}")
    @ApiOperation(value = "剩余次数")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult<Object> limits(@PathVariable int gameid, HttpServletRequest request) {
        //获取活动基本信息
        CardGame game = (CardGame) redisUtil.get(RedisKeys.INFO + gameid);
        if (game == null) {
            return new ApiResult<>(-1, "活动未加载", null);
        }
        //获取当前用户
        HttpSession session = request.getSession();
        CardUser user = (CardUser) session.getAttribute("user");
        if (user == null) {
            return new ApiResult(-1, "未登陆", null);
        }
        //用户可抽奖次数
        Integer enter = (Integer) redisUtil.get(RedisKeys.USERENTER + gameid + "_" + user.getId());
        if (enter == null) {
            enter = 0;
        }
        //根据会员等级，获取本活动允许的最大抽奖次数
        Integer maxenter = (Integer) redisUtil.hget(RedisKeys.MAXENTER + gameid, user.getLevel() + "");
        //如果没设置，默认为0，即：不限制次数
        maxenter = maxenter == null ? 0 : maxenter;

        //用户已中奖次数
        Integer count = (Integer) redisUtil.get(RedisKeys.USERHIT + gameid + "_" + user.getId());
        if (count == null) {
            count = 0;
        }
        //根据会员等级，获取本活动允许的最大中奖数
        Integer maxcount = (Integer) redisUtil.hget(RedisKeys.MAXGOAL + gameid, user.getLevel() + "");
        //如果没设置，默认为0，即：不限制次数
        maxcount = maxcount == null ? 0 : maxcount;

        //幸运转盘类，先给用户随机剔除，再获取令牌，有就中，没有就说明抢光了
        //一般这种情况会设置足够的商品，卡在随机上
        Integer randomRate = (Integer) redisUtil.hget(RedisKeys.RANDOMRATE + gameid, user.getLevel() + "");
        if (randomRate == null) {
            randomRate = 100;
        }

        Map map = new HashMap();
        map.put("maxenter", maxenter);
        map.put("enter", enter);
        map.put("maxcount", maxcount);
        map.put("count", count);
        map.put("randomRate", randomRate);

        return new ApiResult<>(1, "成功", map);
    }

    @GetMapping("/go/{gameid}")
    @ApiOperation(value = "抽奖")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult<Object> act(@PathVariable int gameid, HttpServletRequest request) throws JsonProcessingException {

        ObjectMapper objectMapper = new ObjectMapper();

        CardUser user = (CardUser) request.getSession().getAttribute("user");
        if (user == null) {
            return new ApiResult<>(-1, "未登录", null);
        }
        CardGame game = (CardGame) redisUtil.get(RedisKeys.INFO + gameid);
        Date now = new Date();
        if (now.compareTo(game.getStarttime()) == -1) {
            return new ApiResult<>(-1, "活动未开始", null);
        }
        if (now.compareTo(game.getEndtime()) == 1) {
            return new ApiResult<>(-1, "活动已结束", null);
        }

        //redis分布式锁
        if (redisUtil.setNx(RedisKeys.USERGAME+user.getId()+"_"+gameid,1)) {
            //发送消息到抽奖消息队列
            CardUserGame cardUserGame = new CardUserGame(null, user.getId(), gameid, new Date());
            String jsonString = objectMapper.writeValueAsString(cardUserGame);
            rabbitTemplate.convertAndSend(RabbitKeys.EXCHANGE_DIRECT, RabbitKeys.QUEUE_PLAY, jsonString);
        }

        //用户可抽奖次数
        Integer enter = (Integer) redisUtil.get(RedisKeys.USERENTER + gameid + "_" + user.getId());
        if (enter == null) {
            enter = 0;
            redisUtil.set(RedisKeys.USERENTER + gameid + "_" + user.getId(), enter, (game.getEndtime().getTime() - now.getTime()) / 1000);
        }
        //根据会员等级，获取本活动允许的最大抽奖次数
        Integer maxenter = (Integer) redisUtil.hget(RedisKeys.MAXENTER + gameid, user.getLevel() + "");
        //如果没设置，默认为0，即：不限制次数
        maxenter = maxenter == null ? 0 : maxenter;

        if (maxenter != 0 && enter >= maxenter) {
            return new ApiResult<>(-1, "您的抽奖次数已用完", null);
        }

        //用户已中奖次数
        Integer count = (Integer) redisUtil.get(RedisKeys.USERHIT + gameid + "_" + user.getId());
        if (count == null) {
            count = 0;
            redisUtil.set(RedisKeys.USERHIT + gameid + "_" + user.getId(), count, (game.getEndtime().getTime() - now.getTime()) / 1000);
        }
        //根据会员等级，获取本活动允许的最大中奖数
        Integer maxcount = (Integer) redisUtil.hget(RedisKeys.MAXGOAL + gameid, user.getLevel() + "");
        //如果没设置，默认为0，即：不限制次数
        maxcount = maxcount == null ? 0 : maxcount;


        if (maxcount != 0 && count >= maxcount) {
            return new ApiResult<>(-1, "您已达到最大中奖数", null);
        }

        redisUtil.incr(RedisKeys.USERENTER + gameid + "_" + user.getId(), 1);



        Long token;
        switch (game.getType()) {
            case 1:
                token = luaScript.tokenCheck(RedisKeys.TOKENS + gameid, String.valueOf(new Date().getTime()));
                if (token == 0) {
                    return new ApiResult(-1, "奖品已抽光", null);
                } else if (token == 1) {
                    return new ApiResult(0, "未中奖", null);
                }
                break;
            case 2:
                //瞬间秒杀类简单，直接获取令牌，有就中，没有就说明抢光了
                token = (Long) redisUtil.leftPop(RedisKeys.TOKENS+gameid);
                if (token == null){
                    //令牌已用光，说明奖品抽光了
                    return new ApiResult(-1,"奖品已抽光",null);
                }

                break;

            case 3:

                //幸运转盘类，先给用户随机剔除，再获取令牌，有就中，没有就说明抢光了
                //一般这种情况会设置足够的商品，卡在随机上
                Integer randomRate = (Integer) redisUtil.hget(RedisKeys.RANDOMRATE+gameid,user.getLevel()+"");
                if (randomRate == null){
                    randomRate = 100;
                }
                //注意这里的概率设计思路：
                //每次请求取一个0-100之间的随机数，如果这个数没有落在范围内，直接返回未中奖
                if( new Random().nextInt(100) > randomRate ){
                    return new ApiResult(0,"未中奖",null);
                }

                token = (Long) redisUtil.leftPop(RedisKeys.TOKENS+gameid);
                if (token == null){
                    //令牌已用光，说明奖品抽光了
                    return new ApiResult(-1,"奖品已抽光",null);
                }

                break;

            default:
                return new ApiResult(-1,"不支持的活动类型",null);
        }
        //token有效，中奖！
        redisUtil.incr(RedisKeys.USERHIT + gameid + "_" + user.getId(), 1);
        CardProduct product = (CardProduct) redisUtil.get(RedisKeys.TOKEN + gameid + "_" + token);

        //发送消息到中奖消息队列
        CardUserHit cardUserHit = new CardUserHit(null, gameid, user.getId(), product.getId(), new Date());


        // 将CardUserHit对象转换为JSON字符串
        String jsonString = objectMapper.writeValueAsString(cardUserHit);
        rabbitTemplate.convertAndSend(RabbitKeys.EXCHANGE_DIRECT,RabbitKeys.QUEUE_HIT, jsonString);

        return new ApiResult<>(1, "恭喜中奖", product);


    }

    @GetMapping("/info/{gameid}")
    @ApiOperation(value = "缓存信息")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "gameid", value = "活动id", example = "1", required = true)
    })
    public ApiResult info(@PathVariable int gameid) {

        Map map = new LinkedHashMap();
        map.put(RedisKeys.INFO + gameid, redisUtil.get(RedisKeys.INFO + gameid));

        List<Object> tokens = redisUtil.lrange(RedisKeys.TOKENS + gameid, 0, -1);
        Map tokenMap = new LinkedHashMap();
        for (Object token : tokens) {
            tokenMap.put(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(
                            new Date(Long.valueOf(token.toString()) / 1000)),
                    redisUtil.get(RedisKeys.TOKEN + gameid + "_" + token));
        }
        map.put(RedisKeys.TOKENS + gameid, tokenMap);

        map.put(RedisKeys.MAXGOAL + gameid, redisUtil.hmget(RedisKeys.MAXGOAL + gameid));
        map.put(RedisKeys.MAXENTER + gameid, redisUtil.hmget(RedisKeys.MAXENTER + gameid));
        map.put(RedisKeys.RANDOMRATE + gameid, redisUtil.hmget(RedisKeys.RANDOMRATE + gameid));
        return new ApiResult(1, "缓存信息", map);
    }
}
