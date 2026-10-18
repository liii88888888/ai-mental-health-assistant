package com.lqy.config;

import com.lqy.filter.JwtAuthticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.AntPathMatcher;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private static final AntPathMatcher pathMatcher = new AntPathMatcher();
    private static final String[] PUBLIC_PATHS = {
                "/",
                "/index.html",
                "/favicon.ico",
                "/api/user/login",
                "/api/user/register",
                // 登出要放行：token 已经过期/失效的用户也应该能调登出来结束会话，
                // 具体逻辑在 UserService.logout 里读请求头自己处理
                "/api/user/logout"
    };

    //判断是否为公开路径
    public static boolean isPublicPath(String requestUri) {
        for (String publicPath : PUBLIC_PATHS) {
            // ★ 先比精确相等，再用 AntPathMatcher 做模式匹配。
            //   原因：AntPathMatcher 对"只有根"的路径（"/"）行为很反直觉 ——
            //   实测 match("/", "/") 返回 false，导致访问首页被 JWT 过滤器拦成 401。
            //   equals 兜一层，语义也更直白：白名单里写死的就是原样放行。
            if (publicPath.equals(requestUri) || pathMatcher.match(publicPath, requestUri)) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public JwtAuthticationFilter jwtAuthticationFilter() {
        return new JwtAuthticationFilter();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http

                // 禁用CSRF保护 （API服务通常不需要）
                .csrf(AbstractHttpConfigurer::disable)
                // 配置会话管理为无状态（JWT需要）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置请求的授权规则
                .authorizeHttpRequests(auth -> auth
                        // 公开的路径，无需登录即可访问
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 其他请求都需要认证
                        .anyRequest().authenticated()
                )
                // 添加JWT认证过滤器
                .addFilterBefore(jwtAuthticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
