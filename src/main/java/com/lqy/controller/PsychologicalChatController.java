package com.lqy.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lqy.DTO.command.ConsultationSessionCreateDTO;
import com.lqy.DTO.command.ConsultationSessionQueryDTO;
import com.lqy.DTO.command.ConsultationStreamDTO;
import com.lqy.DTO.response.EmotionAnalysisResult;
import com.lqy.common.Result;
import com.lqy.entity.ConsultationMessage;
import com.lqy.entity.ConsultationSession;
import com.lqy.service.AIService;
import com.lqy.service.ChatMemoryService;
import com.lqy.service.ConsultationMessageService;
import com.lqy.service.ConsultationSessionService;
import com.lqy.util.SecurityUtil;
import com.lqy.util.SessionIdUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 心理咨询会话。
 *
 * 前端的会话ID 是带 "session_" 前缀的字符串，这一层负责把它翻译成干净的 Long，
 * 再交给 Service —— 协议格式不往业务层渗透。
 */
@RestController
@RequestMapping("/api/psychological-chat")
public class PsychologicalChatController {

    @Autowired
    private ConsultationSessionService sessionService;

    @Autowired
    private ConsultationMessageService messageService;

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private AIService aiService;

    /** 开始一次会话（同时存下用户的开场白） */
    @PostMapping("/session/start")
    public Result<Map<String, Object>> startSession(@Valid @RequestBody ConsultationSessionCreateDTO dto) {
        Long userId = SecurityUtil.getLoginUser().getUserId();
        ConsultationSession session = sessionService.startSession(
                userId, dto.getInitialMessage(), dto.getSessionTitle());

        return Result.ok(Map.of(
                "sessionId", SessionIdUtil.format(session.getId()),
                "status", "ACTIVE"));
    }

    /** 我的会话列表（分页） */
    @GetMapping("/sessions")
    public Result<IPage<ConsultationSession>> mySessions(ConsultationSessionQueryDTO query) {
        Long userId = SecurityUtil.getLoginUser().getUserId();
        return Result.ok(sessionService.listMySessions(userId, query.getCurrent(), query.getSize()));
    }

    /** 删除会话（消息会被外键级联删除，缓存也要顺手清掉） */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = SecurityUtil.getLoginUser().getUserId();
        Long sid = SessionIdUtil.parse(sessionId);

        sessionService.deleteMySession(sid, userId);
        chatMemoryService.evict(sid);
        return Result.ok();
    }

    /** 某个会话的全部消息（详情页） */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessage>> sessionMessages(@PathVariable String sessionId) {
        Long userId = SecurityUtil.getLoginUser().getUserId();
        Long sid = SessionIdUtil.parse(sessionId);

        sessionService.getOwnedSession(sid, userId);      // 归属校验
        return Result.ok(messageService.listAll(sid));
    }

    /** 某个会话的最后一次情绪分析（还没分析过则返回 null） */
    @GetMapping("/session/{sessionId}/emotion")
    public Result<EmotionAnalysisResult> sessionEmotion(@PathVariable String sessionId) {
        Long userId = SecurityUtil.getLoginUser().getUserId();
        return Result.ok(sessionService.getLastEmotion(SessionIdUtil.parse(sessionId), userId));
    }

    /** 流式对话（SSE） */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ConsultationStreamDTO dto) {
        Long userId = SecurityUtil.getLoginUser().getUserId();
        return aiService.chat(SessionIdUtil.parse(dto.getSessionId()), dto.getUserMessage(), userId);
    }
}
