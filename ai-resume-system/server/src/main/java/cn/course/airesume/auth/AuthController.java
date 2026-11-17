package cn.course.airesume.auth;

import cn.course.airesume.common.ApiResponse;
import cn.course.airesume.common.BizException;
import cn.course.airesume.user.UserDao;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口：注册 / 登录 / 登出 / 当前用户（FR-USER-01、FR-USER-02）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserDao userDao;
    private final TokenService tokenService;

    public AuthController(UserDao userDao, TokenService tokenService) {
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    public ApiResponse<?> register(@RequestBody Map<String, String> body) {
        String account = trim(body.get("account"));
        String password = body.get("password");
        String name = trim(body.get("name"));

        if (name.isEmpty()) throw new BizException("昵称不能为空");
        if (name.length() > 50) throw new BizException("昵称不能超过 50 字");
        if (account.isEmpty()) throw new BizException("账号不能为空");
        if (!account.matches("[A-Za-z0-9]{4,20}")) throw new BizException("账号需为 4～20 位字母或数字");
        if (password == null || password.length() < 6) throw new BizException("密码至少 6 位");
        if (password.length() > 64) throw new BizException("密码不能超过 64 位");
        if (userDao.findByAccount(account).isPresent()) throw new BizException("账号已存在");

        long id = userDao.insert(account, Passwords.hash(password), name);
        if (id < 0) throw new BizException("注册失败，请稍后重试");
        return ApiResponse.ok(Map.of("account", account));
    }

    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody Map<String, String> body) {
        String account = trim(body.get("account"));
        String password = body.get("password") == null ? "" : body.get("password");

        var userOpt = userDao.findByAccount(account);
        String hash = userDao.passwordHashOf(account);
        // 账号不存在与密码错误统一提示，避免账号枚举（FR-USER-02 异常处理）
        if (userOpt.isEmpty() || hash == null || !Passwords.verify(password, hash)) {
            throw new BizException("账号或密码错误");
        }
        LoginUser user = userOpt.get();
        if (!"ACTIVE".equals(user.status())) {
            throw new BizException("账号已禁用，请联系管理员");
        }
        String token = tokenService.issue(user.id());
        return ApiResponse.ok(user.toClient(token));
    }

    @PostMapping("/logout")
    public ApiResponse<?> logout(@RequestHeader(value = "X-Token", required = false) String token) {
        tokenService.revoke(token);
        return ApiResponse.ok(Map.of());
    }

    @GetMapping("/me")
    public ApiResponse<?> me() {
        return ApiResponse.ok(AuthContext.require().toClient(null));
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
