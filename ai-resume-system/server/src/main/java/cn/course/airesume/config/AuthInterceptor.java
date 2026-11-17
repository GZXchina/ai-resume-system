package cn.course.airesume.config;

import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.auth.TokenService;
import cn.course.airesume.user.UserDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录与角色拦截器（BR-USER-01/03/04）：
 * - /api/** 受保护（除登录注册外），校验 X-Token；
 * - 账号被禁用 → 立即拒绝并注销凭证；
 * - /api/admin/** 仅管理员可访问。
 * 未登录/越权统一返回 HTTP 401/403 + {code:1,message}，前端据此跳转登录页或提示。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;
    private final UserDao userDao;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthInterceptor(TokenService tokenService, UserDao userDao) {
        this.tokenService = tokenService;
        this.userDao = userDao;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = req.getRequestURI();
        String token = req.getHeader("X-Token");
        Long userId = tokenService.resolve(token);
        if (userId == null) {
            return reject(res, 401, "登录状态已失效，请重新登录");
        }
        var userOpt = userDao.findById(userId);
        if (userOpt.isEmpty()) {
            return reject(res, 401, "登录状态已失效，请重新登录");
        }
        LoginUser user = userOpt.get();
        if (!"ACTIVE".equals(user.status())) {
            tokenService.revoke(token);
            return reject(res, 401, "账号已禁用，请联系管理员");
        }
        if (path.startsWith("/api/admin/") && !user.isAdmin()) {
            return reject(res, 403, "无权访问该功能");
        }
        AuthContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean reject(HttpServletResponse res, int status, String message) throws Exception {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write(objectMapper.writeValueAsString(
                Map.of("code", 1, "message", message)));
        return false;
    }
}
