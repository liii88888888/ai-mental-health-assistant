package com.lqy.service;

import cn.hutool.json.JSONUtil;
import com.lqy.common.Result;
import com.lqy.entity.ChatMessage;
import com.lqy.service.tool.DiaryTools;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AI 对话服务。
 *
 * 输出的 SSE 协议对齐原版前端：
 *   正文帧   data:{"code":"200","message":"操作成功","data":{"content":"你"}}
 *   结束帧   event:done
 *   错误帧   data:{"code":"-1","message":"AI服务暂时不可用，请稍后再试"}
 *
 * 这里用 ChatClient（而不是裸的 ChatModel），因为它会自动处理 Function Calling 的
 * "模型要求调工具 → 执行工具 → 把结果回灌给模型 → 再生成回答"这个循环。
 */
@Slf4j
@Service
public class AIService {

    /**
     * 系统提示词模板（%s = 今天的日期）。
     *
     * ★ 为什么要把日期塞进来：模型自己**没有时钟**。
     *   用户说"这周"，模型得把它换算成 yyyy-MM-dd 才能调工具；
     *   不告诉它今天几号，它就会凭训练语料瞎猜一个日期范围，
     *   结果是查了个空区间、回答"你这周没有记录"——数据明明在库里。
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        你是一位温暖、耐心的心理健康陪伴助手。
        请用温和、不评判的语气回应，鼓励用户表达感受。

        今天是 %s。

        ★★ 最重要的一条规则，优先于下面所有内容 ★★
        用户在对话里让你记录时，**每一次都必须真的调用 createDiary 工具**。
        判断依据**只有一条**：这一轮我有没有真的调用工具。

        注意：历史消息里那些"已经帮你记好了""记下来啦"，是我**当时**的回复。
        它们**不代表**这次也可以这么说 —— 不要照着历史里自己的说话方式往下接，
        看起来像"又一次记录成功"的回答，如果这一轮没调工具，那就是在骗用户。

        **没有真的调用工具、或者工具返回失败时，绝对不能说"已经记录""已经记好""记下来啦"这类话。**
        那种情况下要如实说"这次没记上"。

        【正确示范 —— 每一轮记录都必须走完这三步】
          用户说：帮我记一下，今晚吃了大蛋糕，很开心
          第 1 步：你是先调用 createDiary 工具，参数大致是
                  content="今晚吃了大蛋糕，很开心"、moodScore="8"、dominantEmotion="开心"
          第 2 步：工具返回一串「已记录：日期=…，情绪评分=…，主要情绪=…，正文=…」
          第 3 步：你才回复用户，并且会自然带上第 2 步里的信息，例如
                  "已经帮你记好了，今天记下了「今晚吃了大蛋糕，很开心」😊"

        【自我检查】写回复之前先想一下：这一轮我拿到工具返回的那串「已记录：…」了吗？
          没拿到 → 就是没真的记录成功，**不能**说已经记好，要说"这次没记上"或者重新调用工具。

        你可以使用工具帮用户记录情绪日记、查询情绪状况。
        当用户表达了"想记录""帮我记一下"这类意图时，就调用记录工具；
        当用户问心情相关的问题时，按下面的规则选查询工具：
         · 用户给出了具体日期或明确范围（"10月1日""上周一到周五"）→ 用 queryEmotionTrend
         · 用户说的是模糊的相对时间（"最近""这几天""最近状态怎么样"）→ 用 queryRecentEmotion，直接传天数
        千万不要自己把"最近""这几天"换算成具体日期再传给 queryEmotionTrend。
        查询结果为空时，如实告诉用户那天没有记录，不要编造任何情绪内容。

        记录时的两条铁律：
        1. 日记正文只保留用户这次说的原话，不要自己编造，也不要重复带上日记里已经有的内容
           —— 工具会把它追加到今天的日记后面，你只管传新说的这部分。
        2. 只有用户明确提到的信息才填对应字段。没提到的（比如没提睡眠、没提压力）就留空，
           不要根据语气去推测，也不要反复追问，用户愿意说自然会补充。

        当用户说"今天""这周""最近"时，都是相对于上面那个日期。
        请先把它们换算成 yyyy-MM-dd 再传给工具，不要自己猜一个日期范围。

        重要：请全程使用简体中文回复。
        """;

    /** 记录进消息表 ai_model 字段的模型名 */
    private static final String MODEL_NAME = "deepseek-chat";

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private ConsultationSessionService sessionService;

    @Autowired
    private ConsultationMessageService messageService;

    @Autowired
    private EmotionDiaryService emotionDiaryService;

    /** ChatClient 建一次就够，工具是每次请求单独挂的 */
    private ChatClient chatClient;

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 一次流式对话。
     */
    public Flux<ServerSentEvent<String>> chat(Long sessionId, String userMessage, Long userId) {

        // ① 归属校验 —— 必须在推流之前！这时失败还能返回正常的错误响应
        sessionService.getOwnedSession(sessionId, userId);

        // ② 取上下文：Redis 命中直接用，未命中回源 MySQL 并回填
        List<ChatMessage> history = chatMemoryService.load(sessionId);

        // ③ 拼消息
        List<Message> messages = buildMessages(history, userMessage);

        // ④ 每个请求 new 一个工具实例，把当前用户注入进去（模型碰不到 userId）
        DiaryTools tools = new DiaryTools(emotionDiaryService, userId);

        // 攒这一轮的完整回答
        StringBuilder fullAnswer = new StringBuilder();

        return chatClient.prompt()
                .messages(messages)
                .tools(tools)                       // ← 挂上工具，模型可以自主决定调用
                .stream()
                .content()                          // ← Flux<String>

                // 先攒正文（必须在包装成帧之前）
                .doOnNext(fullAnswer::append)

                // 包装成前端要的帧
                .map(this::deltaEvent)

                // done 挂在"正常结束"这条路上
                .concatWith(Flux.just(doneEvent()))

                // 流正常结束，才把这一轮落库（MySQL + Redis）
                .doOnComplete(() -> saveRound(sessionId, userMessage, fullAnswer, tools))

                // 兜底放最外层
                .onErrorResume(error -> {
                    log.error("AI 调用失败", error);
                    return Flux.just(errorEvent("AI服务暂时不可用，请稍后再试"));
                });
    }

    /**
     * 把这一轮对话落库：先 MySQL（真相），再 Redis（缓存）。
     */
    private void saveRound(Long sessionId, String userMessage, StringBuilder fullAnswer, DiaryTools tools) {
        if (fullAnswer.isEmpty()) {
            return;
        }

        // ★ 存进历史的那一份要带上工具执行痕迹 —— 让模型下次能看见
        //   "我说记好了，是因为我确实调了工具、拿到了返回"。
        //   不带的话，历史里就只剩一句"已经帮你记好了"，模型照着学，几轮之后就不调工具了。
        //   MySQL 和 Redis 必须存同一份，否则缓存命中/未命中会看到两种历史。
        String answer = ConsultationMessageService.withToolTrace(
                fullAnswer.toString(), tools.getToolTrace());

        try {
            messageService.saveRound(sessionId, userMessage, answer, MODEL_NAME);
            chatMemoryService.append(sessionId, userMessage, answer);
        }
        catch (Exception e) {
            log.error("对话落库失败，sessionId = {}", sessionId, e);
        }
    }

    /**
     * 拼消息列表：系统提示词 + 历史消息 + 当前这一条
     */
    private List<Message> buildMessages(List<ChatMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>();

        messages.add(new SystemMessage(SYSTEM_PROMPT_TEMPLATE.formatted(today())));

        for (ChatMessage m : history) {
            if ("user".equals(m.getRole())) {
                messages.add(new UserMessage(m.getContent()));
            }
            else {
                messages.add(new AssistantMessage(m.getContent()));
            }
        }

        messages.add(new UserMessage(userMessage));

        return messages;
    }

    /** 今天的日期（带星期），给模型当"时钟"用 */
    private static String today() {
        LocalDate now = LocalDate.now();
        return now + "（" + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA) + "）";
    }


    // ==================== SSE 帧 ====================

    private ServerSentEvent<String> deltaEvent(String text) {
        return ServerSentEvent.<String>builder()
                .data(JSONUtil.toJsonStr(Result.ok(Map.of("content", text))))
                .build();
    }

    private ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.<String>builder()
                .event("done")
                .data(JSONUtil.toJsonStr(Result.ok()))
                .build();
    }

    private ServerSentEvent<String> errorEvent(String message) {
        return ServerSentEvent.<String>builder()
                .data(JSONUtil.toJsonStr(Result.error("-1", message, null)))
                .build();
    }
}
