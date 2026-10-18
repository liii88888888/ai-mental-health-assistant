package com.lqy.service;

import cn.hutool.json.JSONUtil;
import com.lqy.entity.ChatMessage;
import com.lqy.entity.ConsultationMessage;
import com.lqy.enums.SenderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class ChatMemoryService {

    /** 每个会话的缓存 key 前缀 */
    private static final String KEY_PREFIX = "chat:history:";

    /** 上下文窗口：最近 20 条 */
    private static final int MAX_MESSAGES = 20;

    /** 缓存存活时间：30 分钟不活跃就清掉 */
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final ListOperations<String, String> listOps;
    private final ConsultationMessageService messageService;

    public ChatMemoryService(StringRedisTemplate redis,
                             ConsultationMessageService messageService) {
        this.redis = redis;
        this.listOps = redis.opsForList();
        this.messageService = messageService;
    }

    /**
     * 取上下文：先查 Redis，未命中回源 MySQL 并回填缓存。
     * Redis 挂了也不影响功能（降级直接读 MySQL）—— 它是加速器，不是防线。
     */
    public List<ChatMessage> load(Long sessionId) {
        String key = buildKey(sessionId);

        // ① 先试缓存
        try {
            List<String> raw = listOps.range(key, -MAX_MESSAGES, -1);
            if (raw != null && !raw.isEmpty()) {
                return parse(raw);
            }
        } catch (Exception e) {
            log.warn("Redis 读取失败，降级查 MySQL：{}", e.getMessage());
        }

        // ② 未命中（首次 / 过期 / Redis 挂了）→ 回源 MySQL
        return loadFromDb(sessionId);
    }

    /**
     * 把这一轮的对话追加进缓存，并续期。
     * 注意：这个方法不抛异常 —— MySQL 里已经存了，缓存写失败不影响正确性。
     */
    public void append(Long sessionId, String userText, String assistantText) {
        String key = buildKey(sessionId);
        try {
            listOps.rightPushAll(key,
                    JSONUtil.toJsonStr(new ChatMessage("user", userText)),
                    JSONUtil.toJsonStr(new ChatMessage("assistant", assistantText)));
            listOps.trim(key, -MAX_MESSAGES, -1);       // 只留最近 20 条
            redis.expire(key, TTL);                     // 续期
        } catch (Exception e) {
            log.warn("写 Redis 失败（MySQL 已落库，不影响正确性）：{}", e.getMessage());
        }
    }

    /** 会话被删除时清掉缓存 */
    public void evict(Long sessionId) {
        try {
            redis.delete(buildKey(sessionId));
        } catch (Exception e) {
            log.warn("清理 Redis 缓存失败：{}", e.getMessage());
        }
    }

    // ==================== 私有 ====================

    /** 从 MySQL 取最近 N 条，顺便回填 Redis */
    private List<ChatMessage> loadFromDb(Long sessionId) {
        List<ConsultationMessage> rows = messageService.getRecentMessages(sessionId, MAX_MESSAGES);

        List<ChatMessage> result = new ArrayList<>(rows.size());
        for (ConsultationMessage row : rows) {
            result.add(new ChatMessage(roleOf(row.getSenderType()), row.getContent()));
        }

        // 回填缓存 —— 失败也不影响这次请求
        try {
            if (!result.isEmpty()) {
                String key = buildKey(sessionId);
                listOps.rightPushAll(key, result.stream().map(JSONUtil::toJsonStr).toList());
                redis.expire(key, TTL);
            }
        } catch (Exception e) {
            log.warn("回填 Redis 失败：{}", e.getMessage());
        }

        return result;
    }

    /** 反序列化缓存里的 JSON */
    private List<ChatMessage> parse(List<String> raw) {
        List<ChatMessage> messages = new ArrayList<>(raw.size());
        for (String json : raw) {
            messages.add(JSONUtil.toBean(json, ChatMessage.class));
        }
        return messages;
    }

    /** 把数据库的 sender_type 转成 Redis 里存的 role */
    private String roleOf(Integer senderType) {
        return SenderType.USER.getCode().equals(senderType) ? "user" : "assistant";
    }

    private String buildKey(Long sessionId) {
        return KEY_PREFIX + sessionId;
    }
}