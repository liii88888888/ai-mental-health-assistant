package com.lqy;

import com.lqy.entity.ConsultationSession;
import com.lqy.mapper.ConsultationSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

@SpringBootTest
class ConsultationMapperTests {

    @Autowired
    private ConsultationSessionMapper sessionMapper;

    @Test
    void insertSession() {
        ConsultationSession session = ConsultationSession.builder()
                .userId(10L)
                .sessionTitle("测试会话")
                .startedAt(LocalDateTime.now())
                .lastEmotionAnalysis("{\"primaryEmotion\":\"开心\"}")
                .lastEmotionUpdatedAt(LocalDateTime.now())
                .build();

        sessionMapper.insert(session);
        System.out.println("插入后 id = " + session.getId());

        ConsultationSession loaded = sessionMapper.selectById(session.getId());
        System.out.println("查回来 = " + loaded);
    }
}