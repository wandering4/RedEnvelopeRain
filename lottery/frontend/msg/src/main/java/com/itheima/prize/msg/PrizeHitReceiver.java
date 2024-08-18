package com.itheima.prize.msg;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itheima.prize.commons.config.RabbitKeys;
import com.itheima.prize.commons.db.entity.CardUserHit;
import com.itheima.prize.commons.db.mapper.CardUserHitMapper;
import com.itheima.prize.commons.db.service.CardUserHitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = RabbitKeys.QUEUE_HIT)
public class PrizeHitReceiver {
    private final static Logger logger = LoggerFactory.getLogger(PrizeHitReceiver.class);

    @Autowired
    private CardUserHitService hitService;

    @RabbitHandler
    public void processMessage(String message) throws JsonProcessingException {
        logger.info("user hit : message={}", message);
        // 创建ObjectMapper实例
        ObjectMapper objectMapper = new ObjectMapper();

        // 将JSON字符串转换为CardUserHit对象
        CardUserHit cardUserHit = objectMapper.readValue(message, CardUserHit.class);
        hitService.save(cardUserHit);
    }
}