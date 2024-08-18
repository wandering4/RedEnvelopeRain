package com.itheima.prize.msg;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.itheima.prize.commons.config.RedisKeys;
import com.itheima.prize.commons.db.entity.*;
import com.itheima.prize.commons.db.service.CardGameProductService;
import com.itheima.prize.commons.db.service.CardGameRulesService;
import com.itheima.prize.commons.db.service.CardGameService;
import com.itheima.prize.commons.db.service.GameLoadService;
import com.itheima.prize.commons.utils.RedisUtil;
import org.apache.commons.lang3.time.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 活动信息预热，每隔1分钟执行一次
 * 查找未来1分钟内（含），要开始的活动
 */
@Component
public class GameTask {
    private final static Logger log = LoggerFactory.getLogger(GameTask.class);
    @Autowired
    private CardGameService gameService;
    @Autowired
    private CardGameProductService gameProductService;
    @Autowired
    private CardGameRulesService gameRulesService;
    @Autowired
    private GameLoadService gameLoadService;
    @Autowired
    private RedisUtil redisUtil;

    @Scheduled(cron = "0 * * * * ?")
    public void execute() {
        System.out.printf("scheduled!" + new Date());
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.MILLISECOND,0);
        Date now = calendar.getTime();
        //计算一分钟以后
        Date oneMinuteLater = DateUtils.addMinutes(now, 1);
        //查询出未来一分钟以内开启的活动
        QueryWrapper<CardGame> queryWrapper = new QueryWrapper<>();
        queryWrapper.ge("starttime", now).lt("starttime", oneMinuteLater);
        List<CardGame> list = gameService.list(queryWrapper);

        if(list.size() == 0){
            //没有查到要开始的活动
            log.info("game list scan : size = 0");
            return;
        }
        log.info("game list scan : size = {}",list.size());

        //信息预热
            for (CardGame game : list) {
            Integer id = game.getId();

            Map queryMap = new HashMap();
            queryMap.put("gameid", id);

            //活动基本信息
            game.setStatus(1);
            log.info("load game info:{},{},{},{}", id, game.getTitle(), game.getStarttime(), game.getEndtime());


            //奖品信息
            List<CardProductDto> products = gameLoadService.getByGameId(id);
            Map<Integer, CardProduct> productMap = new HashMap<>(products.size());
            products.forEach(p -> productMap.put(p.getId(), p));
            log.info("load product type:{}",productMap.size());

            //查询相关的活动策略：抽奖次数、中奖次数等
            List<CardGameRules> rules = gameRulesService.listByMap(queryMap);
            //奖品数量等配置信息
            List<CardGameProduct> gameProducts = gameProductService.listByMap(queryMap);
            log.info("load bind product:{}",gameProducts.size());

            //生成令牌桶
            long start = game.getStarttime().getTime();
            long end = game.getEndtime().getTime();
            long duration = end - start;
            long rnd = 0l;
            long token = 0l;
            long expire = (end - now.getTime()) / 1000;

            List<Long> tokenList = new ArrayList<>();
            Random random = new Random();


            for (CardGameProduct cgp : gameProducts) {
                Integer amount = cgp.getAmount();
                for (int j = 0; j < amount; j++) {
                    rnd = start + random.nextInt((int) duration);
                    token = rnd * 1000 + random.nextInt(999);
                    tokenList.add(token);
                    log.info("token -> game : {} -> {}", token / 1000, productMap.get(cgp.getProductid()).getName());
                    //创建对应商品缓存
                    redisUtil.set(RedisKeys.TOKEN + id + "_" + token, productMap.get(cgp.getProductid()), expire);
                }
            }
            Collections.sort(tokenList);
            log.info("load tokens: {}", tokenList);
            //放入redis
            redisUtil.rightPushAll(RedisKeys.TOKENS + id, tokenList);
            redisUtil.expire(RedisKeys.TOKENS + id, expire);

            redisUtil.set(RedisKeys.TOKEN + id + "_" + token, game,expire);

            gameService.updateBatchById(list);

            redisUtil.set(RedisKeys.INFO + id, game, -1);
            for (CardGameRules rule : rules) {
                log.info("load rules:level={},enter={},goal={},rate={}",
                        rule.getUserlevel(),rule.getEnterTimes(),rule.getGoalTimes(),rule.getRandomRate());
                redisUtil.hset(RedisKeys.MAXGOAL + id, rule.getUserlevel() + "", rule.getGoalTimes());
                redisUtil.hset(RedisKeys.MAXENTER + id, rule.getUserlevel() + "", rule.getEnterTimes());
                redisUtil.hset(RedisKeys.RANDOMRATE+id, rule.getUserlevel() + "", rule.getRandomRate());
            }
                redisUtil.expire(RedisKeys.MAXGOAL +id,expire);
                redisUtil.expire(RedisKeys.MAXENTER +id,expire);
                redisUtil.expire(RedisKeys.RANDOMRATE +id,expire);

        }
        
    }
}
