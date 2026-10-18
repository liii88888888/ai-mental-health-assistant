package com.lqy.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.lqy.config.JwtConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Date;

@Component
public class JwtTokenUtil implements ApplicationContextAware {
    private static final String ISSUER = "ai-mental-health-assistant";
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        JwtTokenUtil.applicationContext = applicationContext;
    }

    private static JwtConfig getJwtConfig() {
            return applicationContext.getBean(JwtConfig.class);
    }

    //生成token
    public static String generateToken(Long userId, String username, Integer roleType) {
        try {
            //获取jwt配置
            JwtConfig jwtConfig = getJwtConfig();

            //生成签名算法
            Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());

            //配置过期时间
            Date expiration = new Date(System.currentTimeMillis() + jwtConfig.getExpireTime());

            //配置token
            String token = JWT.create()
                    .withClaim("userId", userId)
                    .withClaim("username", username)
                    .withClaim("roleType", roleType)
                    .withExpiresAt(expiration)
                    .withIssuedAt(new Date())
                    .withIssuer(ISSUER)
                    .sign(algorithm);

            return token;
        } catch (Exception e) {
            throw new RuntimeException("生成token失败", e);
        }
    }

    //提取token
    public static String extractTokenFromRequest(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String tokenHeader = request.getHeader("token");

        if (StringUtils.hasText(tokenHeader)) {
            return tokenHeader;
        }

        return null;
    }

    //获取当前token
    public static String getCurrentToken() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String token = (String) request.getAttribute("jwtToken");
            if (token != null) {
                return token;
            }

            // 备用方案： 从请求头直接获取
            String headerToken =  extractTokenFromRequest(request);
            return headerToken;
        }
        return null;
    }

    //验证token
    public static TokenVerificationResult validateToken(String token) {
        DecodedJWT jwt = verifyToken(token);
        Long userId = jwt.getClaim("userId").asLong();
        String username = jwt.getClaim("username").asString();

        Integer roleType = null;
        try{
            roleType = jwt.getClaim("roleType").asInt();
        }catch (Exception e){
            String roleTypeStr = jwt.getClaim("roleType").asString();
        }

        if (userId != null && StringUtils.hasText(username) && roleType != null) {
            return new TokenVerificationResult(userId, username, roleType, true);
        }
        return null;
    }

    //验证token的有效性
    public static DecodedJWT verifyToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new JWTVerificationException("Token不能为空");
        }

        //token解码
        JwtConfig jwtConfig = JwtTokenUtil.getJwtConfig();
        Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());
        JWTVerifier verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();

        return verifier.verify(token);
    }

    //token验证结果封装类
    public static class TokenVerificationResult{
        private final Long userId;
        private final String username;
        private final Integer roleType;
        private final boolean Valid;

        public TokenVerificationResult(Long userId, String username, Integer roleType, boolean Valid) {
            this.userId = userId;
            this.username = username;
            this.roleType = roleType;
            this.Valid = Valid;
        }

        public Long getUserId() { return userId; }
        public String getUsername() { return username; }
        public Integer getRoleType() { return roleType; }
        public boolean isValid() { return Valid; }
    }

}
