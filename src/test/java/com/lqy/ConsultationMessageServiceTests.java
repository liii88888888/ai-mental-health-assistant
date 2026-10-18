package com.lqy;

import com.lqy.entity.ConsultationMessage;
import com.lqy.entity.ConsultationSession;
import com.lqy.mapper.ConsultationSessionMapper;
import com.lqy.service.ConsultationMessageService;
import com.lqy.service.ConsultationSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class ConsultationMessageServiceTests {

    @Autowired
    private ConsultationMessageService messageService;

    @Autowired
    private ConsultationSessionService sessionService;

    @Autowired
    private ConsultationSessionMapper sessionMapper;

    @Test
    void saveAndRead() {
        // startSession 传 null 开场白 = 只建会话、不写消息（等价于原来那个 create）
        ConsultationSession session = sessionService.startSession(10L, null, "测试会话");
        Long sid = session.getId();
        System.out.println("新建会话 id = " + sid);

        messageService.saveUserMessage(sid, "你好，我最近压力有点大");
        messageService.saveAiMessage(sid, "我在听，愿意多说说吗？", "deepseek-chat");

        List<ConsultationMessage> recent = messageService.getRecentMessages(sid, 20);
        System.out.println("取到消息条数 = " + recent.size());
        recent.forEach(m -> System.out.println("  [" + m.getSenderType() + "] " + m.getContent()));

        ConsultationSession updated = sessionMapper.selectById(sid);
        System.out.println("会话的消息数 = " + updated.getMessageCount());
        System.out.println("会话的预览 = " + updated.getLastMessageContent());
        System.out.println("最后消息时间 = " + updated.getLastMessageTime());
    }
}
