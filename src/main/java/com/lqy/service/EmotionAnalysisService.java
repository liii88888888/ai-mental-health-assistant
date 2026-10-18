package com.lqy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqy.DTO.response.EmotionAnalysisResult;
import com.lqy.entity.EmotionDiary;
import com.lqy.exception.BusinessException;
import com.lqy.mapper.EmotionDiaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class EmotionAnalysisService {
    //日记分析提示词
    private static final String ANALYSIS_SYSTEM_PROMPT = """
            你是一位专业的心理健康分析师。用户会给你一段日记内容，请分析其中反映的情绪状态。
           
                    分析原则：
                    - 基于文字内容客观判断，既不夸大也不轻描淡写
                    - 如果文字里没有的信息，不要脑补
                    - 建议要具体、可执行，不要说空话（"要开心一点"这种毫无用处）
            
                    风险等级判定标准：
                    - 0 正常：情绪平稳，没有明显的负面信号
                    - 1 需要关注：有负面情绪但强度可控，属于日常波动
                    - 2 建议疏导：负面情绪明显且持续，已经影响到生活（睡眠、社交、学习）
                    - 3 危机：出现自我伤害、极端绝望、活着没意思等需要立即干预的信号
            
                    特别注意：判定风险等级时宁可保守（宁可标 2 也不要漏掉 3）。
            """;

    @Autowired
    private EmotionDiaryMapper emotionDiaryMapper;

    @Autowired
    private EmotionDiaryService emotionDiaryService;

    @Autowired
    private DeepSeekChatModel deepSeekChatModel;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 分析一篇日记的情绪。
     *
     * ★ currentUserId 必须传进来：这个接口会**修改**日记（把分析结果写回 ai_emotion_analysis），
     *   不校验归属的话，任何登录用户只要猜一个 id，就能触发并覆盖别人日记的分析结果。
     */
    public EmotionAnalysisResult analyze(Long diaryId, Long currentUserId) {
        // 1. 查日记 + 校验归属（越权就直接抛异常，连 AI 都不会调）
        EmotionDiary diary = emotionDiaryService.getOwnedDiary(diaryId, currentUserId);

        // 2. 把"目标结构"变成提示词的一部分
        BeanOutputConverter<EmotionAnalysisResult> converter =
                new BeanOutputConverter<>(EmotionAnalysisResult.class);

        // 3. 用户消息：只喂用户写的文字
        String userContent = """
            日记内容：
            %s

            情绪触发因素：
            %s
            """.formatted(
                diary.getDiaryContent() == null ? "（未填写）" : diary.getDiaryContent(),
                diary.getEmotionTriggers() == null ? "（未填写）" : diary.getEmotionTriggers());

        // 4. 组装 Prompt：提示词 + 格式说明 + JSON 模式
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(ANALYSIS_SYSTEM_PROMPT + "\n\n" + converter.getFormat()),
                        new UserMessage(userContent)
                ),
                DeepSeekChatOptions.builder()
                        .responseFormat(ResponseFormat.builder()
                                .type(ResponseFormat.Type.JSON_OBJECT)
                                .build())
                        .build()
        );

        // 5. 同步调用,分析不需要打字机效果，一次性拿完整结果
        String raw;
        try {
            ChatResponse response = deepSeekChatModel.call(prompt);
            raw = response.getResult().getOutput().getText();
        }
        catch (Exception e) {
            // 没配 API Key、key 失效、网络不通、模型限流……都会落到这里。
            // 直接抛给全局处理器的话，用户只会看到"系统繁忙"，完全不知道是配置问题。
            // 这里转成一句可操作的提示，真实原因留在服务端日志里。
            log.error("调用大模型做情绪分析失败，diaryId = {}", diaryId, e);
            throw new BusinessException("AI 分析暂时不可用，请检查 DeepSeek API Key 是否已正确配置");
        }

        // 6. 解析成对象
        EmotionAnalysisResult result = converter.convert(raw);

        // 7. 落库
        LocalDateTime now = LocalDateTime.now();
        // 必须用 Jackson —— EmotionAnalysisResult 是 record，hutool 序列化它会得到 {}
        try {
            diary.setAiEmotionAnalysis(objectMapper.writeValueAsString(result));
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException("AI 分析结果序列化失败", e);
        }
        diary.setAiAnalysisUpdatedAt(now);
        diary.setUpdatedAt(now);
        emotionDiaryMapper.updateById(diary);

        return result;
    }
}
