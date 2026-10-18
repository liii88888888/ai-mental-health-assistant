package com.lqy.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.lqy.util.JwtTokenUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Date;

@Service
public class TokenBlacklistService {

    // 所有黑名单 key 的公共前缀，做成常量，避免各处手写字符串写错
    public static final String KEY_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate stringRedisTemplate;

    public TokenBlacklistService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //加入黑名单
    public void addToBlackList(String token) {
        // token 已经过期（剩余时间 <= 0）就没必要进黑名单了，
        // 因为 Redis 的 SET EX 命令不接受 0 或负数，会直接报错
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        Date expiredAt = jwt.getExpiresAt();

        if (expiredAt == null) {
            return;
        }

        long remainingMillis = expiredAt.getTime() - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            return;
        }
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + tokenId(token), "1", remainingMillis);
    }

    //判断是否在黑名单中
    public boolean isBlacklisted(String token) {
        return stringRedisTemplate.hasKey(KEY_PREFIX + tokenId(token));
    }

    //token提取摘要作为黑名单key的标识符
    public String tokenId(String token) {
        return DigestUtil.sha256Hex(token);
    }
}