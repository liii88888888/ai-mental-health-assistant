package com.lqy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lqy.DTO.command.EmotionDiaryQueryDTO;
import com.lqy.DTO.command.EmotionDiarySaveCommandDTO;
import com.lqy.DTO.response.EmotionDiaryResponseDTO;
import com.lqy.entity.EmotionDiary;
import com.lqy.entity.User;
import com.lqy.enums.EmotionType;
import com.lqy.exception.BusinessException;
import com.lqy.mapper.EmotionDiaryMapper;
import com.lqy.mapper.UserMapper;
import com.lqy.service.convert.EmotionDiaryConvert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class EmotionDiaryService {
    /** 正文里的【HH:mm】时间戳，比对内容前要先剥掉 */
    private static final Pattern STAMP_PATTERN = Pattern.compile("【\\d{2}:\\d{2}】");

    @Autowired

    private EmotionDiaryMapper emotionDiaryMapper;

    @Autowired
    private UserMapper userMapper;

    //保存，今天的日记存在则更新，不存在则新建
    public EmotionDiaryResponseDTO save(EmotionDiarySaveCommandDTO dto, Long userId) {
        //先校验情绪是否为内置枚举，否则则抛出异常
        EmotionType.fromLabel(dto.getDominantEmotion());

        LocalDate diaryDate = dto.getDiaryDate() == null ? LocalDate.now() : dto.getDiaryDate();

        EmotionDiary existing = emotionDiaryMapper.selectOne(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getUserId , userId)
                        .eq(EmotionDiary::getDiaryDate , diaryDate)
        );

        if(existing != null) {
            existing.setMoodScore(dto.getMoodScore());
            existing.setDominantEmotion(dto.getDominantEmotion());
            existing.setEmotionTriggers(dto.getEmotionTriggers());
            existing.setDiaryContent(dto.getDiaryContent());
            existing.setSleepQuality(dto.getSleepQuality());
            existing.setStressLevel(dto.getStressLevel());
            existing.setUpdatedAt(LocalDateTime.now());
            emotionDiaryMapper.updateById(existing);

            return EmotionDiaryConvert.entityToResponse (existing);
        }

        EmotionDiary diary = EmotionDiaryConvert.commandToEntity(dto, userId, diaryDate);
        emotionDiaryMapper.insert(diary);
        return EmotionDiaryConvert.entityToResponse (diary);

    }

    /**
     * AI 对话写入：**追加语义**（和上面表单的 save() 是两条路，别混）。
     *
     * 为什么不能复用 save()：save() 是「覆盖」——前端表单会把当前全文一起带回来，
     * 用户看到什么就提交什么，覆盖是对的。但 AI 只知道"用户这次说了什么"，
     * 用覆盖语义就会把当天早先写的内容冲掉。详见 tools/fc-overwrite-probe.js 的实测。
     *
     * 这里的几个约定：
     *  · 内容：模型给的是**这次的片段**，服务端负责拼到已有内容后面（带【HH:mm】时间戳）
     *  · 触发因素：同样是追加 —— 一天里可能有不同的触发原因，都留着比只留最后一个有用
     *  · 分数：取最新一次（代表"截至目前"的整体心情）
     *  · 睡眠/压力：只有用户明确提到才写，没提到保持原值不动
     *  · 正文或触发因素一变，之前那份 AI 分析就作废，一起清空
     */
    public EmotionDiaryResponseDTO appendOrCreate(EmotionDiarySaveCommandDTO dto, Long userId) {
        EmotionType.fromLabel(dto.getDominantEmotion());
        checkMoodScore(dto.getMoodScore());

        LocalDate diaryDate = dto.getDiaryDate() == null ? LocalDate.now() : dto.getDiaryDate();

        EmotionDiary existing = emotionDiaryMapper.selectOne(
                new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getUserId , userId)
                        .eq(EmotionDiary::getDiaryDate , diaryDate)
        );

        // 今天还没有日记 —— 新建
        if (existing == null) {
            EmotionDiary diary = EmotionDiaryConvert.commandToEntity(dto, userId, diaryDate);
            diary.setDiaryContent(stamp(dto.getDiaryContent()));
            if (StringUtils.hasText(dto.getEmotionTriggers())) {
                // 跟正文保持一致：第一笔也带时间戳，否则新建和追加两条路的格式会不一样
                diary.setEmotionTriggers(stamp(dto.getEmotionTriggers()));
            }
            emotionDiaryMapper.insert(diary);
            return EmotionDiaryConvert.entityToResponse(diary);
        }

        // 今天已有 —— 追加
        String merged = mergeContent(existing.getDiaryContent(), dto.getDiaryContent());
        boolean contentChanged = !Objects.equals(merged, existing.getDiaryContent());

        // 触发因素也走追加，复用同一套"拼接 + 去重"逻辑
        String mergedTriggers = null;
        boolean triggersChanged = false;
        if (StringUtils.hasText(dto.getEmotionTriggers())) {
            mergedTriggers = mergeContent(existing.getEmotionTriggers(), dto.getEmotionTriggers());
            triggersChanged = !Objects.equals(mergedTriggers, existing.getEmotionTriggers());
        }

        // 用 UpdateWrapper 而不是 updateById：updateById 默认会跳过 null 字段，
        // 那样就没法把 ai_emotion_analysis 主动置空了。
        LambdaUpdateWrapper<EmotionDiary> update = new LambdaUpdateWrapper<EmotionDiary>()
                .eq(EmotionDiary::getId, existing.getId())
                .set(EmotionDiary::getDiaryContent, merged)
                .set(EmotionDiary::getMoodScore, dto.getMoodScore())            // 分数取最新
                .set(EmotionDiary::getDominantEmotion, dto.getDominantEmotion())
                .set(EmotionDiary::getUpdatedAt, LocalDateTime.now());

        // 用户说了才写，没说就原样保留
        if (mergedTriggers != null) {
            update.set(EmotionDiary::getEmotionTriggers, mergedTriggers);
        }
        if (dto.getSleepQuality() != null) {
            update.set(EmotionDiary::getSleepQuality, dto.getSleepQuality());
        }
        if (dto.getStressLevel() != null) {
            update.set(EmotionDiary::getStressLevel, dto.getStressLevel());
        }

        // 正文或触发因素变了，旧的 AI 分析就对不上了（分析就是喂这两样），作废
        if (contentChanged || triggersChanged) {
            update.set(EmotionDiary::getAiEmotionAnalysis, null)
                  .set(EmotionDiary::getAiAnalysisUpdatedAt, null);
        }

        emotionDiaryMapper.update(null, update);

        return EmotionDiaryConvert.entityToResponse(emotionDiaryMapper.selectById(existing.getId()));
    }

    /**
     * 把"这次说的"接到"已有的"后面。
     *
     * 光靠提示词要求模型"只传片段"是靠不住的 —— 实测里模型有时候会把全文带回来。
     * 所以服务端两个方向都兜：不管模型给的是片段还是全文，都不会拼出重复内容。
     */
    private String mergeContent(String existingText, String incomingRaw) {
        String incoming = incomingRaw == null ? "" : incomingRaw.trim();

        if (!StringUtils.hasText(existingText)) {
            return stamp(incoming);
        }
        if (!StringUtils.hasText(incoming)) {
            return existingText;
        }

        // 比对之前必须先把【HH:mm】剥掉 —— 否则存进去的时间戳会让 contains 永远匹配不上，
        // 同一段话被重复追加（这个坑是靠 tools/fc-overwrite-probe.js 的 dup 场景测出来的）
        String existingPlain = STAMP_PATTERN.matcher(existingText).replaceAll("").trim();

        // 这次说的已经在日记里了（模型重复调用，或这次只传了已有内容的一部分）
        if (!existingPlain.isEmpty() && existingPlain.contains(incoming)) {
            return existingText;
        }
        // 模型把已有全文 + 新内容一起传回来了，直接用，别再拼一遍
        if (!existingPlain.isEmpty() && incoming.contains(existingPlain)) {
            return incoming;
        }
        return existingText + "\n" + stamp(incoming);
    }

    /** 给一段正文打上【HH:mm】时间戳（24 小时制） */
    private String stamp(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return text;
        }
        return "【" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) + "】" + text;
    }

    /**
     * 查某个日期区间的日记（按日期正序）。
     *
     * AI 那两个查询工具的取数入口。放在 Service 而不是工具类里，有两个理由：
     *  ① 工具层是"适配层"，职责是把模型的话翻译成业务调用，不该直接写 SQL 条件；
     *  ② 放在这里能被单测直接覆盖，不用为了测一个日期范围去起整个 AI 链路。
     */
    public List<EmotionDiary> listRange(Long userId, LocalDate start, LocalDate end) {
        return emotionDiaryMapper.selectList(new LambdaQueryWrapper<EmotionDiary>()
                .eq(EmotionDiary::getUserId, userId)              // ← 只查自己的，隔离在这里收口
                .between(EmotionDiary::getDiaryDate, start, end)
                .orderByAsc(EmotionDiary::getDiaryDate));
    }

    /** 今天的日期。AI 工具算相对时间要用它，避免各处自己 LocalDate.now() */
    public LocalDate today() {
        return LocalDate.now();
    }

    /**
     * 评分范围兜底。
     *
     * DTO 上的 @Min/@Max 只在 @RequestBody 那条路上生效（表单接口），
     * AI 工具是手工 new 出来的 DTO，不经过校验器 —— 所以这里再拦一道。
     */
    private void checkMoodScore(Integer moodScore) {
        if (moodScore == null || moodScore < 1 || moodScore > 10) {
            throw new BusinessException("情绪评分必须在 1 到 10 之间");
        }
    }

    //分页查询
    public IPage<EmotionDiaryResponseDTO> page(EmotionDiaryQueryDTO query) {
        LambdaQueryWrapper<EmotionDiary> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(query.getUserId() != null , EmotionDiary::getUserId , query.getUserId());

        if(StringUtils.hasText(query.getMoodScoreRange())) {
            String[] parts = query.getMoodScoreRange().split("-");
            if(parts.length == 2) {
                wrapper.between(EmotionDiary::getMoodScore , Integer.parseInt(parts[0]) , Integer.parseInt(parts[1]));
            }
        }

        wrapper.orderByDesc(EmotionDiary::getDiaryDate);

        Page<EmotionDiary> page = new Page<>(query.getCurrent() , query.getSize());
        Page<EmotionDiary> result = emotionDiaryMapper.selectPage(page , wrapper);

        // 批量取出这一页涉及的用户，避免 N+1
        Set<Long> userIds = result.getRecords().stream()
                .map(EmotionDiary::getUserId)
                .collect(Collectors.toSet());

        Map<Long, User> userMap = userIds.isEmpty() ? Collections.emptyMap()
                : userMapper.selectBatchIds(userIds).stream()
                  .collect(Collectors.toMap(User::getId , u -> u));

        return result.convert(d ->EmotionDiaryConvert.entityToResponse(d , userMap.get(d.getUserId())));
    }

    //用户删除自己写的日记
    public void deleteMyDiary(Long diaryId, Long currentUserId) {
        checkOwned(diaryId, currentUserId);
        emotionDiaryMapper.deleteById(diaryId);
    }

    //管理员删任意日记
    public void deleteByAdmin(Long diaryId, Integer roleType) {
        if (roleType == null || roleType != 2) {          // 2 = 管理员
            throw new BusinessException("无此操作权限");
        }
        if (emotionDiaryMapper.selectById(diaryId) == null) {
            throw new BusinessException("日记不存在");
        }
        emotionDiaryMapper.deleteById(diaryId);
    }

    private void checkOwned(Long diaryId, Long currentUserId) {
        getOwnedDiary(diaryId, currentUserId);
    }

    /**
     * 查出日记并校验归属，返回日记本身。
     *
     * ★ 归属校验只写一处、所有入口共用 —— 越权漏洞几乎都是"某个入口忘了校验"造成的。
     *   现在删除、AI 分析都走这个方法，少一个入口就少一条漏掉的可能。
     */
    public EmotionDiary getOwnedDiary(Long diaryId, Long currentUserId) {
        EmotionDiary diary = emotionDiaryMapper.selectById(diaryId);
        if (diary == null) {
            throw new BusinessException("日记不存在");
        }
        if (!diary.getUserId().equals(currentUserId)) {
            throw new BusinessException("无权操作这篇日记");
        }
        return diary;
    }




}
