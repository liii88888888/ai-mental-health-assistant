package com.lqy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class RedisSerializerTests {

    /** Spring Boot 自动装配的默认 RedisTemplate，泛型是 <Object, Object> */
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    /** 也是自动装配的，只处理字符串 */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void compareTemplates() {
        // 两个模板存同样的 key 名、同样的 value
        redisTemplate.opsForValue().set("demo:default-template", "hello");
        stringRedisTemplate.opsForValue().set("demo:string-template", "hello");

        System.out.println("两个模板都执行完了，去虚拟机的 redis-cli 里敲 keys * 看看");
    }
}