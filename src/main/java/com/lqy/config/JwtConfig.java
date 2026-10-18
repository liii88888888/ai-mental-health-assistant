package com.lqy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    /**
     * 签名密钥。
     *
     * ★ 这里**故意不写默认值** —— 以前硬编码过一段可猜的短语当密钥，
     *   一旦代码公开，任何人都能用它伪造 token。密钥必须从配置来，
     *   配置里再通过环境变量注入。
     */
    private String secret;

    /** token 有效期（毫秒），由 application.yml 提供，默认 24 小时 */
    private Long expireTime = 24 * 60 * 60 * 1000L;

    private Long refreshTokenExpireTime = 7 * 24 * 60 * 60 * 1000L;
}
