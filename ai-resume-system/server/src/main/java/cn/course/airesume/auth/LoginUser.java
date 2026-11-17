package cn.course.airesume.auth;

import java.util.Map;

/** 当前登录用户（由 AuthInterceptor 写入 AuthContext）。 */
public record LoginUser(long id, String account, String name, String role, String status) {
    public boolean isAdmin() { return "ADMIN".equals(role); }

    /** 前端约定的角色值：student / admin */
    public String roleForClient() { return isAdmin() ? "admin" : "student"; }

    public Map<String, Object> toClient(String token) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", String.valueOf(id));
        m.put("account", account);
        m.put("name", name);
        m.put("role", roleForClient());
        if (token != null) m.put("token", token);
        return m;
    }
}
