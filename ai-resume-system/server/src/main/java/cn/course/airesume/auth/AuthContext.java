package cn.course.airesume.auth;

import cn.course.airesume.common.BizException;

/** 请求线程内的登录用户上下文。 */
public final class AuthContext {
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(LoginUser user) { HOLDER.set(user); }

    public static void clear() { HOLDER.remove(); }

    public static LoginUser get() { return HOLDER.get(); }

    /** 要求已登录，否则抛业务异常（前端收到后跳转登录页）。 */
    public static LoginUser require() {
        LoginUser u = HOLDER.get();
        if (u == null) throw new BizException("登录状态已失效，请重新登录");
        return u;
    }

    /** 要求管理员角色（BR-USER-03）。 */
    public static LoginUser requireAdmin() {
        LoginUser u = require();
        if (!u.isAdmin()) throw new BizException("无权访问该功能");
        return u;
    }
}
