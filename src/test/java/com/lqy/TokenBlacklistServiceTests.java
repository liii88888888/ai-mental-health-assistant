package com.lqy;

import com.lqy.service.TokenBlacklistService;
import com.lqy.util.JwtTokenUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TokenBlacklistServiceTests {

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Test
    void blacklistThenCheck() {
        String token = JwtTokenUtil.generateToken(1L, "test-user", 1);

        System.out.println("token = " + token);

        System.out.println("拉黑前 isBlacklisted = " + tokenBlacklistService.isBlacklisted(token));

        tokenBlacklistService.addToBlackList(token);

        System.out.println("拉黑后 isBlacklisted = " + tokenBlacklistService.isBlacklisted(token));
    }
}