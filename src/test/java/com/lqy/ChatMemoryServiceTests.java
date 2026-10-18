package com.lqy;

import com.lqy.entity.ChatMessage;
import com.lqy.service.ChatMemoryService;
import com.lqy.service.ConsultationSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class ChatMemoryServiceTests {

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private ConsultationSessionService sessionService;

    @Test
    void cacheWriteAndFallbackToDb() {
        // 消息表有外键约束，先建一个真实会话
        Long sessionId = sessionService.startSession(10L, "测试开场白", "缓存测试会话").getId();
        System.out.println("测试会话 id = " + sessionId);

        // ① 往缓存里写两轮
        chatMemoryService.append(sessionId, "我叫小李", "你好，小李！");
        chatMemoryService.append(sessionId, "我叫什么？", "你叫小李。");

        // ② 读回来 —— 命中 Redis 缓存，应该有 4 条
        List<ChatMessage> cached = chatMemoryService.load(sessionId);
        System.out.println("读缓存条数 = " + cached.size());
        cached.forEach(m -> System.out.println("  [" + m.getRole() + "] " + m.getContent()));

        // ③ 清掉缓存再读 —— 这次会回源 MySQL
        chatMemoryService.evict(sessionId);
        List<ChatMessage> fromDb = chatMemoryService.load(sessionId);
        System.out.println("清缓存后回源 MySQL 的条数 = " + fromDb.size());
        fromDb.forEach(m -> System.out.println("  [" + m.getRole() + "] " + m.getContent()));
    }
}
