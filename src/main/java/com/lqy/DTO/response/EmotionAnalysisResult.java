package com.lqy.DTO.response;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Builder;

import java.util.List;

@Builder
public record EmotionAnalysisResult(

        @JsonPropertyDescription("主要情绪，必须从这几个词里选一个，不得使用其他词汇：开心、平静、焦虑、悲伤、兴奋、疲惫、惊讶、困惑")
        String primaryEmotion,

        @JsonPropertyDescription("情绪强度，0 到 100 之间的整数。0 表示非常平静，100 表示情绪极其强烈")
        Integer emotionScore,

        @JsonPropertyDescription("这条日记反映的是否为负面情绪，true 表示负面")
        Boolean isNegative,

        @JsonPropertyDescription("风险等级，只能是 0、1、2、3 这四个整数之一：0=正常，1=需要关注，2=建议疏导，3=危机需要干预")
        Integer riskLevel,

        @JsonPropertyDescription("对风险等级的一句话说明；等级为 0 时填「无」")
        String riskDescription,

        @JsonPropertyDescription("日记中反映情绪的关键词，2 到 5 个")
        List<String> keywords,

        @JsonPropertyDescription("给用户的一句专业建议，温暖、具体、可执行，不超过 50 字")
        String suggestion,

        @JsonPropertyDescription("2 到 4 条可执行的改善建议，每条不超过 20 字")
        List<String> improvementSuggestions
) {
}