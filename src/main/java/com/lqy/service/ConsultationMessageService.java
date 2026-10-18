package com.lqy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lqy.entity.ConsultationMessage;
import com.lqy.entity.ConsultationSession;
import com.lqy.enums.SenderType;
import com.lqy.mapper.ConsultationMessageMapper;
import com.lqy.mapper.ConsultationSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class ConsultationMessageService {

    /** 列表页"最后一条消息预览"最多存多少字 */
    private static final int PREVIEW_LENGTH = 200;

    /**
     * 工具执行痕迹的标记。
     *
     * ★ 为什么需要它：
     *   对话历史里原本只有模型的最终回复，工具调用的过程完全没留痕。
     *   模型看到的历史于是变成"用户要求记录 → 我回一句已经记好了"，
     *   它从这段历史里学到的示范是：**记录成功根本不用调用工具。**
     *   连续几轮之后它就开始只动嘴不动手 —— 回一句"已经帮你记好啦"，数据库里什么都没有。
     *
     *   所以每轮真正调用过工具时，就把工具返回的结果附在这条回复后面存进历史，
     *   让模型能看见"我说记好了，是因为我确实调了工具、并且拿到了返回"。
     *
     *   这段文字**只喂给模型**，展示给用户之前会被 stripToolTrace 剥掉。
     */
    public static final String TOOL_TRACE_PREFIX = "〔本轮实际执行了工具：";

    @Autowired
    private ConsultationMessageMapper messageMapper;

    @Autowired
    private ConsultationSessionMapper sessionMapper;

    /** 存一条用户消息 */
    @Transactional
    public void saveUserMessage(Long sessionId, String content) {
        doSave(sessionId, SenderType.USER, content, null);
    }

    /** 存一条 AI 回复 */
    @Transactional
    public void saveAiMessage(Long sessionId, String content, String model) {
        doSave(sessionId, SenderType.AI, content, model);
    }

    /** 取某个会话最近 N 条消息（正序返回，可以直接拼 prompt） */
    public List<ConsultationMessage> getRecentMessages(Long sessionId, int limit) {
        LambdaQueryWrapper<ConsultationMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConsultationMessage::getSessionId, sessionId)
               .orderByDesc(ConsultationMessage::getId)
               .last("LIMIT " + limit);

        List<ConsultationMessage> list = messageMapper.selectList(wrapper);
        Collections.reverse(list);          // 查出来是"从新到旧"，反转成"从旧到新"
        return list;
    }

    /** 取某个会话的全部消息（详情页用，正序） */
    public List<ConsultationMessage> listAll(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConsultationMessage::getSessionId, sessionId)
               .orderByAsc(ConsultationMessage::getId);
        List<ConsultationMessage> list = messageMapper.selectList(wrapper);
        // 详情页是给用户看的，把工具痕迹剥掉
        list.forEach(m -> m.setContent(stripToolTrace(m.getContent())));
        return list;
    }

    /** 给内容附上工具执行痕迹（只在存"给模型看"的那份时用） */
    public static String withToolTrace(String content, String toolTrace) {
        if (!StringUtils.hasText(toolTrace)) {
            return content;
        }
        return content + "\n\n" + TOOL_TRACE_PREFIX + toolTrace.trim() + "〕";
    }

    /** 剥掉工具执行痕迹 —— 展示给用户之前必须调用 */
    public static String stripToolTrace(String content) {
        if (content == null) {
            return null;
        }
        int i = content.indexOf(TOOL_TRACE_PREFIX);
        return i < 0 ? content : content.substring(0, i).stripTrailing();
    }

    // ==================== 私有 ====================

    /**
     * 存消息 + 同步更新会话的冗余字段。
     * 两步必须在同一个事务里 —— 否则会出现"消息存进去了、会话的计数没更新"的脏数据。
     */
    private void doSave(Long sessionId, SenderType senderType, String content, String model) {
        LocalDateTime now = LocalDateTime.now();

        // ① 插入消息
        ConsultationMessage message = ConsultationMessage.builder()
                .sessionId(sessionId)
                .senderType(senderType.getCode())
                .messageType(1)
                .content(content)
                .aiModel(model)
                .createdAt(now)
                .build();
        messageMapper.insert(message);

        // ② 更新会话的冗余字段
        sessionMapper.update(null, new LambdaUpdateWrapper<ConsultationSession>()
                .eq(ConsultationSession::getId, sessionId)
                .set(ConsultationSession::getLastMessageContent, preview(content))
                .set(ConsultationSession::getLastMessageTime, now)
                .setSql("message_count = message_count + 1"));      // ← 原子自增
    }

    /** 截成列表页要的预览长度 */
    private String preview(String content) {
        if (content == null) {
            return null;
        }
        // 列表页也是给用户看的，先把工具痕迹剥掉再截断
        content = stripToolTrace(content);
        return content.length() <= PREVIEW_LENGTH
                ? content
                : content.substring(0, PREVIEW_LENGTH);
    }

    /** 存一轮完整对话（用户 + AI），一个事务 */
    @Transactional
    public void saveRound(Long sessionId, String userContent, String aiContent, String model) {
        doSave(sessionId, SenderType.USER, userContent, null);
        doSave(sessionId, SenderType.AI, aiContent, model);
    }
}
