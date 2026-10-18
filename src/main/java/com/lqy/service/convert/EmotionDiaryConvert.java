package com.lqy.service.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lqy.DTO.command.EmotionDiarySaveCommandDTO;
import com.lqy.DTO.response.EmotionAnalysisResult;
import com.lqy.DTO.response.EmotionDiaryResponseDTO;
import com.lqy.entity.EmotionDiary;
import com.lqy.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
public class EmotionDiaryConvert {

    /** 解析 AI 分析结果用 —— record 只能用 Jackson 解，hutool 解不了 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    //提交入参 → 实体。userId 由调用方从登录态传入，前端管不着
    public static EmotionDiary commandToEntity(EmotionDiarySaveCommandDTO dto, Long userId, LocalDate diaryDate) {
        LocalDateTime now = LocalDateTime.now();
        return EmotionDiary.builder()
                .userId(userId)
                .diaryDate(dto.getDiaryDate() != null ? dto.getDiaryDate() : LocalDate.now())
                .moodScore(dto.getMoodScore())
                .dominantEmotion(dto.getDominantEmotion())
                .emotionTriggers(dto.getEmotionTriggers())
                .diaryContent(dto.getDiaryContent())
                .sleepQuality(dto.getSleepQuality())
                .stressLevel(dto.getStressLevel())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    // 实体 → 响应, 不包含用户信息,用于普通用户看自己的日记
    public static EmotionDiaryResponseDTO entityToResponse(EmotionDiary diary) {
        return entityToResponse(diary, null);
    }

    // 实体 → 响应, 包含用户信息,用于后台列表
    public static EmotionDiaryResponseDTO entityToResponse(EmotionDiary diary,User user) {
        return EmotionDiaryResponseDTO.builder()
                .id(diary.getId())
                .userId(diary.getUserId())
                .username(user != null ? user.getUsername() : null)
                .nickname(user != null ? user.getNickname() : null)
                .diaryDate(diary.getDiaryDate())
                .moodScore(diary.getMoodScore())
                .dominantEmotion(diary.getDominantEmotion())
                .emotionTriggers(diary.getEmotionTriggers())
                .diaryContent(diary.getDiaryContent())
                .sleepQuality(diary.getSleepQuality())
                .stressLevel(diary.getStressLevel())
                .createdAt(diary.getCreatedAt())
                .updatedAt(diary.getUpdatedAt())
                .aiAnalysis(parseAnalysis(diary.getAiEmotionAnalysis()))
                .build();
    }

    /**
     * 把数据库里的 ai_emotion_analysis（JSON 字符串）解析成对象。
     * 没分析过返回 null；解析失败也返回 null（脏数据不该让整个列表崩掉）。
     */
    private static EmotionAnalysisResult parseAnalysis(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, EmotionAnalysisResult.class);
        }
        catch (Exception e) {
            log.error("解析日记的 AI 分析结果失败，原文 = {}", json, e);
            return null;
        }
    }



}
