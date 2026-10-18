package com.lqy.service.tool;

import com.lqy.DTO.command.EmotionDiarySaveCommandDTO;
import com.lqy.DTO.response.EmotionDiaryResponseDTO;
import com.lqy.entity.EmotionDiary;
import com.lqy.service.EmotionDiaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

/**
 * 给 AI 用的工具：情绪日记的「写入」和「查询」。
 *
 * ★ 最关键的设计：userId 是构造注入的，不是工具参数。
 *   因为工具的入参是**模型生成的**，属于不可信输入 —— 如果把 userId 做成参数，
 *   模型被诱导（或自己瞎猜）就可能写到别人的日记里。
 *   所以身份从服务端的登录态注入，模型看不见也碰不到。
 *
 * ★ 工具层的职责：把模型说的话翻译成业务调用，并清洗模型输出的脏值。
 *   它不直接碰 Mapper —— 取数在 Service，这样隔离和可测性都有出处。
 *
 * 每个请求 new 一个实例，所以天然线程安全。
 */
@Slf4j
public class DiaryTools {

    private final EmotionDiaryService diaryService;
    private final Long userId;

    /**
     * 本轮**实际执行成功**的工具痕迹。
     *
     * ★ 为什么要记这个：模型只能看到对话历史，而历史里只有它的最终回复 ——
     *   工具调用的过程（tool_call + 工具结果）压根没被保存。
     *   于是模型看到的历史是"用户要记录 → 我回一句已经记好了"，
     *   **它从历史里学到的示范是：记录成功不需要调用工具。**
     *   连续几轮之后它就开始只动嘴不动手，回一句"已经帮你记好啦"，数据库里什么都没有。
     *
     *   所以这里把执行痕迹带出去，交给 AIService 写进历史 —— 让模型能看见
     *   "我说记好了，是因为我确实调了工具"。
     */
    private final StringBuilder toolTrace = new StringBuilder();

    /** 本轮的工具执行痕迹；空字符串表示这一轮一个工具都没调成功 */
    public String getToolTrace() {
        return toolTrace.toString();
    }

    public DiaryTools(EmotionDiaryService diaryService, Long userId) {
        this.diaryService = diaryService;
        this.userId = userId;
    }

    /**
     * 写日记。
     *
     * 注意两件事：
     * ① 复用的是 EmotionDiaryService.save —— 它本身是 upsert（唯一索引兜底），
     *    所以模型重复调用也不会插出两条，这就是"幂等"。
     * ② 失败时**不抛异常**，而是返回一句错误描述给模型看 —— 模型可以据此修正参数重试；
     *    抛异常会把整轮对话打断。
     */
    @Tool(description = """
            当用户想记录今天的情绪或心情时调用（例如"帮我记一下""今天好累，记一笔"）。
            会把用户这次说的话**追加**到今天的日记后面，之前记过内容不会被覆盖，
            所以只传这次说的，不要把日记里已经有的内容重复带上。
            同一天只会有一条日记。
            """)
    public String createDiary(
            @ToolParam(description = "用户这次说的话，保留原话，不要自己编内容；不要重复带上日记里已有的内容")
            String content,
            @ToolParam(description = "情绪评分，1 到 10 之间的整数，10 表示心情最好，1 表示最差")
            String moodScore,
            @ToolParam(description = "主要情绪，必须从这几个里选一个：开心、平静、焦虑、悲伤、兴奋、疲惫、惊讶、困惑")
            String dominantEmotion,
            @ToolParam(description = "情绪触发因素；只在用户明确说了原因时才填，没提到就省略这个参数，不要填任何占位符")
            String emotionTriggers,
            @ToolParam(description = "睡眠质量，1 到 5 的整数；只在用户明确提到睡得好不好时才填，没提到就省略这个参数，不要推测")
            String sleepQuality,
            @ToolParam(description = "压力水平，1 到 5 的整数；只在用户明确提到压力大小时才填，没提到就省略这个参数，不要推测")
            String stressLevel) {
        try {
            Integer mood = parseIntOrNull(moodScore);
            if (mood == null || mood < 1 || mood > 10) {
                // 不抛异常，把问题交回给模型 —— 它可以问用户一句再重试
                return "还差一个情绪评分（1 到 10 分）。请先跟用户确认今天大概几分，再重新调用这个工具。";
            }

            EmotionDiarySaveCommandDTO dto = new EmotionDiarySaveCommandDTO();
            dto.setMoodScore(mood);
            dto.setDominantEmotion(blankToNull(dominantEmotion));
            dto.setDiaryContent(blankToNull(content));
            dto.setEmotionTriggers(blankToNull(emotionTriggers));
            dto.setSleepQuality(scoreOrNull(parseIntOrNull(sleepQuality), 1, 5));
            dto.setStressLevel(scoreOrNull(parseIntOrNull(stressLevel), 1, 5));

            EmotionDiaryResponseDTO saved = diaryService.appendOrCreate(dto, userId);
            log.info("[Tool] createDiary 成功 userId={} date={}", userId, saved.getDiaryDate());

            String result = "已记录：日期=" + saved.getDiaryDate()
                    + "，情绪评分=" + saved.getMoodScore()
                    + "，主要情绪=" + saved.getDominantEmotion()
                    + "，正文=" + saved.getDiaryContent();
            // 记下这一轮"确实调用了工具"的证据，会被写进对话历史。
            // ★ 只记"调了什么、成没成"，**不要把日记正文也塞进来** ——
            //   正文会越来越长，每轮历史都带一份完整快照，反而把模型搞晕。
            toolTrace.append("createDiary（写入成功，日期=")
                     .append(saved.getDiaryDate()).append("）");
            return result;
        }
        catch (Exception e) {
            log.error("[Tool] createDiary 失败", e);
            return "记录失败：" + e.getMessage() + "。请检查参数后重试。";
        }
    }

    /**
     * 把模型传来的"假空值"归一化成真 null。
     *
     * 坑在哪：提示词里只要写了"没提到就传 null"，模型就真的会把**字符串 "null"** 传进来，
     * 而不是省略参数或给一个 JSON null。String 类型的参数会原样落库，
     * 于是 emotion_triggers 里就躺了一个 'null' 四个字母。
     *
     * 这类"模型输出的脏值"必须在工具层洗掉，不能指望 Service 认识 LLM 的怪癖。
     */
    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.isEmpty()) {
            return null;
        }
        String lower = t.toLowerCase();
        if (lower.equals("null") || lower.equals("none") || lower.equals("undefined") || lower.equals("n/a")) {
            return null;
        }
        return t;
    }

    /**
     * 量表的越界值一律当作"没填"。
     *
     * 这是上面那个坑的第二种形态：Integer 参数塞不进字符串 "null"，
     * 模型就退而求其次传一个 0 来表示"我不确定"。0 会原样落库，
     * 睡眠质量 0 分是没有意义的。所以范围外的值直接丢掉。
     */
    private static Integer scoreOrNull(Integer v, int min, int max) {
        return (v == null || v < min || v > max) ? null : v;
    }

    /**
     * 把模型给的数字字符串解析成 Integer，解析不了就当"没填"。
     *
     * ★★ 为什么数值参数全都用 String 接收、不用 Integer ★★
     *
     * 因为 Spring AI 1.0.1 把参数转成 Java 类型时是这么写的：
     *
     * ```java
     * if (resolvedType == Integer.class) {
     *     BigDecimal bigDecimal = new BigDecimal(value.toString());
     *     return Integer.valueOf(bigDecimal.intValueExact());
     * }
     * ```
     *
     * 它**没有对脏值做任何防护**。模型只要把字符串 "null" 传进一个 Integer 参数
     * （它真的会这么干 —— 见 blankToNull 上面的注释），`new BigDecimal("null")` 就抛
     * NumberFormatException，**整个工具调用被直接打断**，工具一次都不会执行。
     *
     * 表现出来就是：模型嘴上说"已经帮你记好了"，其实数据库里什么都没发生 ——
     * 对用户来说这是最糟糕的失败方式。
     *
     * 所以我把所有数值参数都改成 String 接收：Spring AI 只会做 value.toString()，
     * 永远不会抛，脏值的清洗和范围校验全都由我们自己的代码掌控。
     *
     * **一句话：框架的隐式转换一旦不可控，就把它挪到自己能看见的地方。**
     */
    private static Integer parseIntOrNull(String v) {
        String t = blankToNull(v);
        if (t == null) {
            return null;
        }
        try {
            return new BigDecimal(t).intValueExact();
        }
        catch (Exception e) {
            // "abc" / "2.5" 这类解析不了的，一律当没填
            return null;
        }
    }

    /**
     * 查情绪趋势。
     *
     * 返回给模型的数据要**精简** —— 只给需要的字段，否则 token 会爆。
     */
    @Tool(description = """
            按**指定日期范围**查询情绪日记。只在用户说出了一个明确的日期或范围时用
            （例如"10月1日到10月5日""上个月"），参数必须是 yyyy-MM-dd 格式。
            如果用户说的是"最近""这几天""最近怎么样"这种模糊表达，改用 queryRecentEmotion，不要自己换算日期。
            只返回当前登录用户自己的数据。
            """)
    public String queryEmotionTrend(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        try {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            return formatRange(start, end);
        }
        catch (Exception e) {
            log.error("[Tool] queryEmotionTrend 失败", e);
            return "查询失败：" + e.getMessage() + "。请检查日期格式（yyyy-MM-dd）后重试。";
        }
    }

    /**
     * 查最近 N 天。
     *
     * ★ 为什么要有这个工具：`queryEmotionTrend` 要求模型自己把"最近""这几天"换算成日期。
     *   算"这周"它没问题，但算"上个月""上上周末"这种就开始出错 ——
     *   跟"模型没有时钟"是同一类问题：**相对时间的锚定不该交给一个没有时间概念的东西。**
     *
     *   所以这里把换算收回到服务端：模型只需要回答"多少天"这个它擅长的数字。
     */
    @Tool(description = """
            查询用户**最近 N 天**的情绪记录。
            当用户说"最近怎么样""这几天心情""近期状态"这类没说具体日期的话时，用这个工具，
            直接传天数，**不要自己换算成具体日期**。
            只返回当前登录用户自己的数据。
            """)
    public String queryRecentEmotion(
            @ToolParam(description = "最近多少天，1 到 30 之间的整数。'这两天'传 2，'最近几天'传 7，'最近半个月'传 15")
            String days) {
        try {
            Integer d = parseIntOrNull(days);
            if (d == null || d < 1 || d > 30) {
                return "天数需要在 1 到 30 之间，请重新确认一下用户想问多久。";
            }
            LocalDate end = diaryService.today();
            LocalDate start = end.minusDays(d - 1L);   // days=1 表示只有今天
            return formatRange(start, end);
        }
        catch (Exception e) {
            log.error("[Tool] queryRecentEmotion 失败", e);
            return "查询失败：" + e.getMessage() + "。";
        }
    }

    /** 两个查询工具共用的取数 + 排版逻辑 */
    private String formatRange(LocalDate start, LocalDate end) {
        List<EmotionDiary> list = diaryService.listRange(userId, start, end);

        if (list.isEmpty()) {
            return start + " 到 " + end + " 之间没有任何情绪日记记录。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 到 ").append(end).append(" 共 ").append(list.size()).append(" 条记录：\n");
        for (EmotionDiary d : list) {
            sb.append("- ").append(d.getDiaryDate())
              .append("  评分 ").append(d.getMoodScore()).append("/10")
              .append("  情绪 ").append(d.getDominantEmotion() == null ? "未填写" : d.getDominantEmotion())
              .append("  睡眠 ").append(d.getSleepQuality() == null ? "-" : d.getSleepQuality())
              .append("  压力 ").append(d.getStressLevel() == null ? "-" : d.getStressLevel());
            if (d.getDiaryContent() != null) {
                String brief = d.getDiaryContent().length() > 50
                        ? d.getDiaryContent().substring(0, 50) + "…"
                        : d.getDiaryContent();
                sb.append("  内容：").append(brief);
            }
            sb.append("\n");
        }
        log.info("[Tool] 查询情绪 userId={} {} ~ {} 条数={}", userId, start, end, list.size());
        return sb.toString();
    }
}
