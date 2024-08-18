package com.itheima.prize.msg;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.prize.commons.config.RabbitKeys;
import com.itheima.prize.commons.db.entity.CardUserGame;
import com.itheima.prize.commons.db.entity.CardUserHit;
import com.itheima.prize.commons.db.mapper.CardUserGameMapper;
import com.itheima.prize.commons.db.service.CardUserGameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = RabbitKeys.QUEUE_PLAY)
public class PrizeGameReceiver {

    private final static Logger logger = LoggerFactory.getLogger(PrizeGameReceiver.class);

    @Autowired
    private CardUserGameService cardUserGameService;

    @RabbitHandler
    public void processMessage(String message) throws JsonProcessingException {
        logger.info("user play : msg={}" , message);
        // 创建ObjectMapper实例
        ObjectMapper objectMapper = new ObjectMapper();

        // 将JSON字符串转换为CardUserHit对象
        CardUserGame cardUserGame = objectMapper.readValue(message, CardUserGame.class);
        cardUserGameService.save(cardUserGame);

    }

}
