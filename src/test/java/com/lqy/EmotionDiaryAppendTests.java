package com.lqy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lqy.DTO.command.EmotionDiarySaveCommandDTO;
import com.lqy.DTO.response.EmotionDiaryResponseDTO;
import com.lqy.entity.EmotionDiary;
import com.lqy.entity.User;
import com.lqy.mapper.EmotionDiaryMapper;
import com.lqy.mapper.UserMapper;
import com.lqy.service.EmotionDiaryService;
import com.lqy.service.tool.DiaryTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 情绪日记「AI 追加语义」+「查询工具」的确定性测试。
 *
 * ★ 为什么不走 AI 对话来测：
 *   模型的行为是概率性的（同一句话有时记、有时不记，有时传片段、有时传全文），
 *   拿它当测试输入只会得到 flaky 的用例。这里直接打 Service / Tool，
 *   验证的是**我们自己的逻辑**，不掺模型的运气。模型那侧由 tools/ 下的探针做端到端观测。
 *
 * ★ 为什么用专用测试账号：
 *   第一版这个测试用的是 USER_ID=10，并且在 @BeforeEach/@AfterEach 里清"当天"的日记 ——
 *   结果它把 demo 账号那天的真实日记删掉了。**测试绝不能碰生产数据**，
 *   所以现在它会自己建一个独立账号，只清自己的数据。
 */
@SpringBootTest
class EmotionDiaryAppendTests {

    private static final String TEST_USERNAME = "autotest_diary";
    private static final String TEST_EMAIL = "autotest_diary@test.local";

    /** 正文/触发因素里的【HH:mm】时间戳 */
    private static final String STAMP = "【\\d{2}:\\d{2}】";

    @Autowired
    private EmotionDiaryService diaryService;

    @Autowired
    private EmotionDiaryMapper diaryMapper;

    @Autowired
    private UserMapper userMapper;

    /** 本次测试用的独立账号，每个用例现取现用 */
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = ensureTestUser();
        cleanMyDiaries();
    }

    @AfterEach
    void tearDown() {
        cleanMyDiaries();
    }

    /** 拿到（或创建）专用测试账号，绝不复用别人的 userId */
    private Long ensureTestUser() {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, TEST_USERNAME));
        if (existing != null) {
            return existing.getId();
        }

        LocalDateTime now = LocalDateTime.now();
        User u = User.builder()
                .username(TEST_USERNAME)
                .email(TEST_EMAIL)
                .password("not-a-real-password-just-for-tests")
                .nickname("自动测试账号")
                .userType(1)
                .status(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        userMapper.insert(u);
        return u.getId();
    }

    /** 只清这个测试账号的日记 —— 它是专用的，全删也不会误伤任何人 */
    private void cleanMyDiaries() {
        diaryMapper.delete(new LambdaQueryWrapper<EmotionDiary>()
                .eq(EmotionDiary::getUserId, userId));
    }

    private EmotionDiarySaveCommandDTO dto(String content, int mood, String emotion,
                                           String triggers, Integer sleep, Integer stress) {
        return dto(null, content, mood, emotion, triggers, sleep, stress);
    }

    private EmotionDiarySaveCommandDTO dto(LocalDate date, String content, int mood, String emotion,
                                           String triggers, Integer sleep, Integer stress) {
        EmotionDiarySaveCommandDTO d = new EmotionDiarySaveCommandDTO();
        d.setDiaryDate(date);
        d.setDiaryContent(content);
        d.setMoodScore(mood);
        d.setDominantEmotion(emotion);
        d.setEmotionTriggers(triggers);
        d.setSleepQuality(sleep);
        d.setStressLevel(stress);
        return d;
    }

    // ==================== 追加语义 ====================

    @Test
    void 同一天记两笔_正文和触发因素都追加且都不丢() {
        // ① 早上第一笔
        EmotionDiaryResponseDTO morning = diaryService.appendOrCreate(
                dto("早上出门就下雨，上班还迟到了，很烦", 2, "悲伤", "迟到", 2, 5), userId);

        assertNotNull(morning.getId());
        assertEquals(1, countAll(), "同一天只应该有一条日记");
        assertTrue(morning.getDiaryContent().matches(STAMP + "早上出门就下雨，上班还迟到了，很烦"),
                "第一笔正文应该带上【HH:mm】时间戳，实际：" + morning.getDiaryContent());
        assertTrue(morning.getEmotionTriggers().matches(STAMP + "迟到"),
                "第一笔的触发因素也该带时间戳（和正文保持一致），实际：" + morning.getEmotionTriggers());
        assertEquals(2, morning.getSleepQuality());
        assertEquals(5, morning.getStressLevel());

        // ② 下午再记一笔
        EmotionDiaryResponseDTO afternoon = diaryService.appendOrCreate(
                dto("下午评审过了，还挺开心的", 9, "开心", "项目评审通过", null, null), userId);

        assertEquals(morning.getId(), afternoon.getId(), "还是同一条，不该新插");
        assertEquals(1, countAll());

        String content = afternoon.getDiaryContent();
        assertTrue(content.contains("早上出门就下雨，上班还迟到了，很烦"), "早上的正文不能被覆盖，实际：" + content);
        assertTrue(content.contains("下午评审过了，还挺开心的"), "下午的正文要接在后面，实际：" + content);
        assertTrue(content.indexOf("早上") < content.indexOf("下午"),
                "时间顺序：早上那段应该在前面，实际：" + content);

        String triggers = afternoon.getEmotionTriggers();
        assertTrue(triggers.contains("迟到"), "早上的触发因素不能丢，实际：" + triggers);
        assertTrue(triggers.contains("项目评审通过"), "下午的触发因素要追加进来，实际：" + triggers);

        assertEquals(9, afternoon.getMoodScore(), "分数取最新");
        assertEquals("开心", afternoon.getDominantEmotion());
        assertEquals(2, afternoon.getSleepQuality(), "没提到睡眠就该保留原值");
        assertEquals(5, afternoon.getStressLevel(), "没提到压力就该保留原值");
    }

    @Test
    void 内容一变_旧的AI分析就作废() {
        EmotionDiaryResponseDTO first = diaryService.appendOrCreate(
                dto("今天还行", 6, "平静", null, null, null), userId);

        EmotionDiary diary = diaryMapper.selectById(first.getId());
        diary.setAiEmotionAnalysis("{\"primaryEmotion\":\"平静\"}");
        diary.setAiAnalysisUpdatedAt(LocalDateTime.now());
        diaryMapper.updateById(diary);
        assertNotNull(diaryMapper.selectById(first.getId()).getAiEmotionAnalysis());

        diaryService.appendOrCreate(dto("但下午有点烦躁", 4, "焦虑", null, null, null), userId);

        EmotionDiary after = diaryMapper.selectById(first.getId());
        assertNull(after.getAiEmotionAnalysis(), "正文变了，旧分析必须作废");
        assertNull(after.getAiAnalysisUpdatedAt());
    }

    @Test
    void 重复提交同一段话_不会重复追加() {
        diaryService.appendOrCreate(dto("今天还行，就是有点累", 6, "疲惫", null, null, null), userId);
        String once = onlyDiary().getDiaryContent();

        diaryService.appendOrCreate(dto("今天还行，就是有点累", 6, "疲惫", null, null, null), userId);
        String twice = onlyDiary().getDiaryContent();

        assertEquals(once, twice, "同一段话不该被拼两遍。改前：" + once + " / 改后：" + twice);
    }

    @Test
    void 模型若把已有全文一起传回来_也不会拼出重复() {
        EmotionDiaryResponseDTO first = diaryService.appendOrCreate(
                dto("早上很糟", 3, "悲伤", null, null, null), userId);
        String existing = first.getDiaryContent();

        String plain = existing.replaceAll(STAMP, "");
        diaryService.appendOrCreate(
                dto(plain + "\n下午好多了", 8, "开心", null, null, null), userId);

        String merged = onlyDiary().getDiaryContent();
        assertEquals(1, countOccurrences(merged, "早上很糟"),
                "模型带全文回来时不该把旧内容再拼一遍，实际：" + merged);
        assertTrue(merged.contains("下午好多了"));
    }

    // ==================== 查询工具 ====================

    /**
     * 最近 N 天：包含今天，往前数 N-1 天。
     *
     * 这个用例守的是"把相对时间换算收回服务端"这件事 ——
     * 以前让模型自己把"最近"算成日期，算"上个月"就会错。
     */
    @Test
    void 最近N天的范围_包含今天且不含更早的记录() {
        LocalDate today = LocalDate.now();
        diaryService.appendOrCreate(dto(today,                "DIARY_T0", 6, "平静", null, null, null), userId);
        diaryService.appendOrCreate(dto(today.minusDays(1),   "DIARY_T1", 5, "平静", null, null, null), userId);
        diaryService.appendOrCreate(dto(today.minusDays(2),   "DIARY_T2", 4, "疲惫", null, null, null), userId);
        diaryService.appendOrCreate(dto(today.minusDays(5),   "DIARY_T5", 3, "悲伤", null, null, null), userId);

        DiaryTools tools = new DiaryTools(diaryService, userId);

        // 最近 3 天 = 今天 + 昨天 + 前天，不该带上 5 天前那条
        String last3 = tools.queryRecentEmotion("3");
        assertTrue(last3.contains("共 3 条记录"), "应该只查到 3 条，实际：" + last3);
        assertTrue(last3.contains("DIARY_T0") && last3.contains("DIARY_T1") && last3.contains("DIARY_T2"),
                "今天/昨天/前天的记录都该在，实际：" + last3);
        assertFalse(last3.contains("DIARY_T5"), "5 天前的记录不该出现在'最近3天'里，实际：" + last3);

        // 边界：1 天 = 只有今天
        String onlyToday = tools.queryRecentEmotion("1");
        assertTrue(onlyToday.contains("共 1 条记录"), "最近1天应该只有今天，实际：" + onlyToday);
        assertFalse(onlyToday.contains("DIARY_T1"), "最近1天不该包含昨天，实际：" + onlyToday);

        // 越界的天数不查库，直接告诉模型参数不对
        assertTrue(tools.queryRecentEmotion("0").contains("1 到 30"));
        assertTrue(tools.queryRecentEmotion("999").contains("1 到 30"));
        // 模型把"没填"传成字符串 "null" 时，不能炸，要当成参数不合法
        assertTrue(tools.queryRecentEmotion("null").contains("1 到 30"),
                "传 'null' 字符串时应该优雅提示，而不是抛异常");
        assertTrue(tools.queryRecentEmotion(null).contains("1 到 30"),
                "传真 null 时也应该优雅提示");
    }

    @Test
    void 指定日期范围查询_只返回区间内的记录() {
        LocalDate today = LocalDate.now();
        diaryService.appendOrCreate(dto(today,              "IN_RANGE",   6, "平静", null, null, null), userId);
        diaryService.appendOrCreate(dto(today.minusDays(1), "IN_RANGE_1", 5, "平静", null, null, null), userId);
        diaryService.appendOrCreate(dto(today.minusDays(9), "OUT_RANGE",  2, "悲伤", null, null, null), userId);

        DiaryTools tools = new DiaryTools(diaryService, userId);
        String out = tools.queryEmotionTrend(today.minusDays(3).toString(), today.toString());

        assertTrue(out.contains("IN_RANGE") && out.contains("IN_RANGE_1"), "区间内的都要在，实际：" + out);
        assertFalse(out.contains("OUT_RANGE"), "区间外的不该出现，实际：" + out);
    }

    @Test
    void 查询工具只返回当前用户的数据() {
        LocalDate today = LocalDate.now();
        diaryService.appendOrCreate(dto(today, "MINE", 6, "平静", null, null, null), userId);

        // 换个 userId 去查同一个日期
        DiaryTools other = new DiaryTools(diaryService, userId + 999_999L);
        String out = other.queryRecentEmotion("1");

        assertFalse(out.contains("MINE"), "不该查到别人的日记，实际：" + out);
        assertTrue(out.contains("没有任何情绪日记记录"), "对别人来说今天应该是空的，实际：" + out);
    }

    // ==================== 工具 ====================

    private EmotionDiary onlyDiary() {
        return diaryMapper.selectList(new LambdaQueryWrapper<EmotionDiary>()
                .eq(EmotionDiary::getUserId, userId)).get(0);
    }

    private long countAll() {
        return diaryMapper.selectCount(new LambdaQueryWrapper<EmotionDiary>()
                .eq(EmotionDiary::getUserId, userId));
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(target, idx)) >= 0) {
            count++;
            idx += target.length();
        }
        return count;
    }
}
