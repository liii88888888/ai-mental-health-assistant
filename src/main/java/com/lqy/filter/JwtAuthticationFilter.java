package com.lqy.filter;

import com.lqy.DTO.response.UserLoginResponseDTO;
import com.lqy.common.ResultCode;
import com.lqy.config.SecurityConfig;
import com.lqy.enums.UserStatus;
import com.lqy.security.LoginUser;
import com.lqy.service.TokenBlacklistService;
import com.lqy.service.UserService;
import com.lqy.util.JwtTokenUtil;
import com.lqy.util.ResponseUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@Slf4j
public class JwtAuthticationFilter extends OncePerRequestFilter {
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    @Autowired
    private UserService userService;

    @Autowired
    private TokenBlacklistService tokenBlacklistService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
         String requestUri = request.getRequestURI();
         //检查是否公开接口
        return SecurityConfig.isPublicPath(requestUri);

    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestUri = request.getRequestURI();
        String method = request.getMethod();

        String token = JwtTokenUtil.extractTokenFromRequest(request);
        if (StringUtils.hasText(token)) {
            // 验证token,获取用户信息
            JwtTokenUtil.TokenVerificationResult validationResult;
            try {
                validationResult = JwtTokenUtil.validateToken(token);
            }
            catch (Exception e) {
                // ★ 这里必须兜住异常。
                //   verifyToken() 在"格式非法 / 签名不对 / 已过期"时是**抛异常**，不是返回一个"无效"结果。
                //   不拦下来的话，异常会穿透整个过滤器链，最后被 Spring Security 的
                //   ExceptionTranslationFilter 兜成「HTTP 403 + 空响应体」——
                //   前端既拿不到错误码也拿不到提示，只能白屏。
                //   结果就是下面那几个 TOKEN_INVALID/TOKEN_EXPIRED 分支全成了死代码。
                log.warn("token 校验失败：{}", e.getMessage());
                clearSecurityContext();
                ResponseUtil.writeError(response, ResultCode.TOKEN_INVALID);
                return;
            }

            if (validationResult != null && validationResult.isValid()){
                // 检查token是否在黑名单中
                if (tokenBlacklistService.isBlacklisted(token)){
                    clearSecurityContext();
                    ResponseUtil.writeError(response, ResultCode.TOKEN_BLOCKED);
                    return;
                }
                //查询用户信息，验证用户状态
                UserLoginResponseDTO.UserDetailResponseDTO user = userService.getUserById(validationResult.getUserId());
                if (user != null && user.getStatus().equals(UserStatus.NORMAL.getCode())){
                    //创建Spring Security认证对象
                    List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                            new SimpleGrantedAuthority("ROLE_" + validationResult.getRoleType())
                    );

                    // 创建LoginUser对象
                    LoginUser loginUser = new LoginUser(
                            validationResult.getUserId(),
                            validationResult.getUsername(),
                            validationResult.getRoleType()
                    );

                    // 创建UsernamePasswordAuthenticationToken对象
                    UsernamePasswordAuthenticationToken authcation = new UsernamePasswordAuthenticationToken(
                            loginUser,//整个登录用户对象作为主体
                            null,
                            authorities
                    );

                    // 设置认证信息到Spring Securtity上下文
                    SecurityContext context = SecurityContextHolder.getContext();
                    context.setAuthentication(authcation);
                    SecurityContextHolder.setContext(context);

                    //存进仓库，异步派发才可读
                    securityContextRepository.saveContext(context,request, response);


                    // 将token存储到请求属性中
                    request.setAttribute("jwtToken", token);
                }else{
                    clearSecurityContext();
                    ResponseUtil.writeError(response, ResultCode.TOKEN_ACCESS_FORBIDDEN);
                }

            }else{
                clearSecurityContext();
                ResponseUtil.writeError(response, ResultCode.TOKEN_INVALID);
            }

        }else {
            // 清理上下文
            clearSecurityContext();
            ResponseUtil.writeError(response, ResultCode.ACCESS_UNAUTHORIZED);
            return;
        }

        //继续过滤器链
        filterChain.doFilter(request, response);

    }

    // 清理Spring Security上下文
    private void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
}


