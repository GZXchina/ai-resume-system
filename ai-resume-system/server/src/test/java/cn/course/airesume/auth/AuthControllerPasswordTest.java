package cn.course.airesume.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.course.airesume.common.ApiResponse;
import cn.course.airesume.common.BizException;
import cn.course.airesume.user.UserDao;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 修改密码：原密码校验、强度校验、改密后吊销旧凭证。 */
class AuthControllerPasswordTest {

    private UserDao userDao;
    private TokenService tokenService;
    private AuthController controller;
    private final LoginUser me = new LoginUser(1L, "linzhiyuan", "林知远", "STUDENT", "ACTIVE");
    private String oldHash;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        tokenService = mock(TokenService.class);
        controller = new AuthController(userDao, tokenService);
        AuthContext.set(me);
        oldHash = Passwords.hash("old-pass-1");
        when(userDao.passwordHashOf(me.account())).thenReturn(oldHash);
        when(userDao.updatePassword(org.mockito.ArgumentMatchers.eq(1L), anyString())).thenReturn(1);
        when(tokenService.issue(1L)).thenReturn("new-token");
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private Map<String, String> body(String oldPwd, String newPwd) {
        return Map.of("oldPassword", oldPwd, "newPassword", newPwd);
    }

    @Test
    @DisplayName("原密码错误时拒绝修改")
    void rejectsWrongOldPassword() {
        BizException e = assertThrows(BizException.class,
                () -> controller.changePassword(body("wrong-pass", "new-pass-1")));
        assertTrue(e.getMessage().contains("原密码不正确"), e.getMessage());
        verify(userDao, never()).updatePassword(org.mockito.ArgumentMatchers.eq(1L), anyString());
    }

    @Test
    @DisplayName("新密码过短或过长时拒绝")
    void rejectsWeakNewPassword() {
        assertThrows(BizException.class, () -> controller.changePassword(body("old-pass-1", "12345")));
        assertThrows(BizException.class,
                () -> controller.changePassword(body("old-pass-1", "x".repeat(65))));
        verify(tokenService, never()).revokeByUser(1L);
    }

    @Test
    @DisplayName("新旧密码相同时拒绝")
    void rejectsSamePassword() {
        BizException e = assertThrows(BizException.class,
                () -> controller.changePassword(body("old-pass-1", "old-pass-1")));
        assertTrue(e.getMessage().contains("不能与原密码相同"), e.getMessage());
    }

    @Test
    @DisplayName("修改成功：写入新哈希、吊销旧凭证、返回新 token")
    void changesPasswordAndRotatesToken() {
        ApiResponse<?> res = controller.changePassword(body("old-pass-1", "brand-new-pass"));

        assertEquals(0, res.getCode());
        assertNotNull(res.getData());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) res.getData();
        assertEquals("new-token", data.get("token"));
        verify(tokenService).revokeByUser(1L);
        // 新哈希必须能校验通过，且与原哈希不同（PBKDF2 每次随机盐）
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(userDao).updatePassword(org.mockito.ArgumentMatchers.eq(1L), captor.capture());
        assertTrue(Passwords.verify("brand-new-pass", captor.getValue()), "新哈希应能校验通过");
        assertTrue(!captor.getValue().equals(oldHash), "新旧哈希不应相同");
    }

    @Test
    @DisplayName("未登录时不允许改密码")
    void requiresLogin() {
        AuthContext.clear();
        assertThrows(BizException.class, () -> controller.changePassword(body("old-pass-1", "new-pass-1")));
    }
}
