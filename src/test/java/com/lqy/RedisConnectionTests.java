package com.lqy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RedisConnectionTests {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void redisIsReachable() {
        // 1. 存一个字符串
        stringRedisTemplate.opsForValue().set("test:hello", "hello-from-spring");

        // 2. 读回来
        String value = stringRedisTemplate.opsForValue().get("test:hello");
        System.out.println("从 Redis 读到的值 = " + value);
        assertEquals("hello-from-spring", value);

        // 3. 带过期时间地存 —— 这就是你将来写黑名单的写法
        stringRedisTemplate.opsForValue()
                .set("jwt:blacklist:demo", "1", Duration.ofSeconds(60));

        // 4. 看看它还剩多少秒
        Long ttl = stringRedisTemplate.getExpire("jwt:blacklist:demo");
        System.out.println("黑名单 key 剩余存活秒数 = " + ttl);
    }
}