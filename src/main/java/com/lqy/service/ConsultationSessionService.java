package com.lqy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lqy.DTO.response.EmotionAnalysisResult;
import com.lqy.entity.ConsultationSession;
import com.lqy.exception.BusinessException;
import com.lqy.mapper.ConsultationSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
public class ConsultationSessionService {
    @Autowired
    private ConsultationSessionMapper sessionMapper;

    @Autowired
    private ConsultationMessageService messageService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 开始一次会话：建会话 + 存下用户的第一条消息。
     * 两件事必须在一个事务里 —— 否则可能出现"会话建了但没有开场白"的空会话。
     */
    @Transactional
    public ConsultationSession startSession(Long userId, String initialMessage, String sessionTitle) {
        LocalDateTime now = LocalDateTime.now();
        ConsultationSession session = ConsultationSession.builder()
                .userId(userId)
                .sessionTitle(StringUtils.hasText(sessionTitle) ? sessionTitle
                        : "小暖助手 - " + now.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")))
                .startedAt(now)
                .messageCount(0)
                .build();
        sessionMapper.insert(session);

        if (StringUtils.hasText(initialMessage)) {
            messageService.saveUserMessage(session.getId(), initialMessage);
        }
        return session;
    }

    // 分页查询用户会话
    public IPage<ConsultationSession> listMySessions(Long userId, Long current, Long size) {
        LambdaQueryWrapper<ConsultationSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConsultationSession::getUserId, userId)
                .orderByDesc(ConsultationSession::getStartedAt);

        return sessionMapper.selectPage(new Page<>(current, size), wrapper);
    }

    // 删除用户会话
    public void deleteMySession(Long sessionId, Long currentUserId) {
        getOwnedSession(sessionId, currentUserId);   // 校验不过会自己抛异常
        sessionMapper.deleteById(sessionId);
    }

    //查会话并校验归属；不属于当前用户就抛异常
    public ConsultationSession getOwnedSession(Long sessionId, Long currentUserId) {
        ConsultationSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (!session.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权操作该会话");
        }
        return session;
    }

    /**
     * 取会话的最后一次情绪分析结果。
     * 数据库里存的是 JSON 字符串，这里解析成对象返回。
     *
     * 注意：还没分析过时，返回的是一个"字段齐全的空结果"，而不是 null。
     * 因为前端拿到之后是直接 currentEmotion.value = res 的，模板里又会读
     * currentEmotion.improvementSuggestions.length —— 给 null 会让整个页面渲染崩掉。
     * 这是"前端没做防御，后端兜底"的适配。
     */
    public EmotionAnalysisResult getLastEmotion(Long sessionId, Long currentUserId) {
        ConsultationSession session = getOwnedSession(sessionId, currentUserId);
        if (!StringUtils.hasText(session.getLastEmotionAnalysis())) {
            return emptyEmotion();
        }
        try {
            // 这里必须用 Jackson，不能用 hutool 的 JSONUtil.toBean
            // —— EmotionAnalysisResult 是 record，hutool 解不了。
            return objectMapper.readValue(session.getLastEmotionAnalysis(), EmotionAnalysisResult.class);
        }
        catch (JsonProcessingException e) {
            // 历史数据可能格式不对（字段改名、结构升级、脏数据……），
            // 解析失败绝不能把整个页面搞崩，兜底返回空结果。
            log.error("解析会话情绪分析失败，sessionId = {}，原文 = {}",
                    sessionId, session.getLastEmotionAnalysis(), e);
            return emptyEmotion();
        }
    }

    /** 字段齐全的空情绪结果（前端会直接读字段，不能给 null） */
    private EmotionAnalysisResult emptyEmotion() {
        return EmotionAnalysisResult.builder()
                .primaryEmotion("暂无")
                .emotionScore(0)
                .isNegative(false)
                .riskLevel(0)
                .riskDescription("这次会话还没有进行情绪分析")
                .keywords(List.of())
                .suggestion("")
                .improvementSuggestions(List.of())
                .build();
    }






}
