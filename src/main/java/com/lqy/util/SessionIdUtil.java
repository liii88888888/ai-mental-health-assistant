package com.lqy.util;

import com.lqy.exception.BusinessException;

/**
 * 会话ID 的格式适配。
 *
 * 原版前端会把会话ID当成字符串传来传去，而且习惯带上 "session_" 前缀
 * （例如 "session_22"）。这个类负责把外部的字符串格式，翻译成干净的 Long。
 */
public final class SessionIdUtil {

    private static final String PREFIX = "session_";

    private SessionIdUtil() {
    }

    /**
     * 把前端传来的 sessionId 解析成 Long。
     * 兼容带前缀（"session_22"）和不带前缀（"22"）两种写法。
     */
    public static Long parse(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException("会话ID不能为空");
        }
        String raw = sessionId.startsWith(PREFIX) ? sessionId.substring(PREFIX.length()) : sessionId;
        try {
            return Long.valueOf(raw);
        }
        catch (NumberFormatException e) {
            throw new BusinessException("会话ID格式不正确：" + sessionId);
        }
    }

    /** 装配成前端习惯的格式 */
    public static String format(Long sessionId) {
        return PREFIX + sessionId;
    }
}
