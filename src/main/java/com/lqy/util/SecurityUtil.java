package com.lqy.util;

import com.lqy.exception.BusinessException;
import com.lqy.security.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtil {

    private SecurityUtil() { }

    /** 取当前登录用户；没登录就抛业务异常 */
    public static LoginUser getLoginUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        throw new BusinessException("未登录");
    }

    /**
     * 要求当前用户是管理员（roleType = 2），否则抛业务异常。
     *
     * ★ 后台接口必须在入口就把这道门关上。
     *   之前 /admin/page 既没校验角色、又直接拿前端传的 userId 去查 ——
     *   任何登录用户都能翻别人的日记，这是个标准的越权（IDOR）。
     *   **权限校验要跟着"数据范围"走：这个接口能拿到别人的数据，就必须先证明你有这个资格。**
     */
    public static void requireAdmin() {
        LoginUser user = getLoginUser();
        if (user.getRoleType() == null || user.getRoleType() != 2) {
            throw new BusinessException("无此操作权限");
        }
    }
}
