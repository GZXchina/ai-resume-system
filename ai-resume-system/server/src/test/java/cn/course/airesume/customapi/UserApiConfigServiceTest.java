package cn.course.airesume.customapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.course.airesume.analysis.AiKeyCipher;
import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 自定义 API 配置服务单元测试（v2.0）：覆盖字段校验、分组/标签规整、超时与重试边界、
 * 同名与数量上限、归属校验、复制命名与调用参数组装。
 */
class UserApiConfigServiceTest {

    private UserApiConfigDao dao;
    private UserApiInvokeService invoker;
    private UserApiConfigService service;
    private AiKeyCipher cipher;

    private static final LoginUser STUDENT = new LoginUser(1L, "linzhiyuan", "林知远", "STUDENT", "ACTIVE");

    @BeforeEach
    void setUp() {
        dao = mock(UserApiConfigDao.class);
        invoker = mock(UserApiInvokeService.class);
        cipher = new AiKeyCipher("unit-test-secret-key");
        service = new UserApiConfigService(dao, invoker, new ObjectMapper(), cipher);
        AuthContext.set(STUDENT);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    /* ---------------- 辅助 ---------------- */

    private static Map<String, Object> payload(String name, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("method", "get");
        m.put("url", url);
        m.put("group", "测试组");
        m.put("tags", "简历,测试，简历");
        m.put("headers", List.of(Map.of("name", "X-Trace", "value", "t-1")));
        m.put("params", List.of(Map.of("name", "page", "type", "number", "defaultValue", "1")));
        m.put("bodyType", "json");
        m.put("body", "{\"a\":1}");
        m.put("timeoutMs", 999999);
        m.put("retries", 9);
        return m;
    }

    private static UserApiConfigDao.Row row(long id, long userId, String name) {
        return new UserApiConfigDao.Row(id, userId, name, "GET", "https://api.example.com/v1",
                "[]", "[]", "none", "", "默认分组", "", 15000, 0, true,
                "2026-11-15 10:00", "2026-11-15 10:00");
    }

    private void stubInsert(long newId) {
        when(dao.insert(eq(1L), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), eq(true)))
                .thenReturn(newId);
    }

    /* ---------------- 创建与规整 ---------------- */

    @Test
    @DisplayName("创建：方法转大写、标签去重、超时与重试被限制在边界内")
    void createNormalizesConfig() {
        when(dao.countByUser(1L)).thenReturn(0);
        when(dao.countByName(1L, "配置A")).thenReturn(0);
        stubInsert(10L);
        when(dao.findById(10L)).thenReturn(Optional.of(new UserApiConfigDao.Row(
                10L, 1L, "配置A", "GET", "https://api.example.com/v1",
                "[{\"name\":\"X-Trace\",\"value\":\"t-1\",\"enabled\":true}]",
                "[{\"name\":\"page\",\"type\":\"number\",\"enabled\":true}]",
                "json", "{\"a\":1}", "测试组", "简历,测试", 60000, 3, true,
                "2026-11-15 10:00", "2026-11-15 10:00")));

        Map<String, Object> out = service.save(payload("配置A", "https://api.example.com/v1"));

        assertEquals("10", out.get("id"));
        assertEquals("GET", out.get("method"));
        assertEquals(List.of("简历", "测试"), out.get("tags"));
        assertEquals(60000, out.get("timeoutMs"), "超时应被限制到 60000");
        assertEquals(3, out.get("retries"), "重试应被限制到 3");

        verify(dao).insert(eq(1L), eq("配置A"), eq("GET"), eq("https://api.example.com/v1"),
                anyString(), anyString(), eq("json"), eq("{\"a\":1}"), eq("测试组"), eq("简历,测试"),
                eq(60000), eq(3), eq(true));
    }

    @Test
    @DisplayName("创建：名称为空被拒绝")
    void blankNameRejected() {
        assertThrows(BizException.class, () -> service.save(payload("  ", "https://api.example.com/v1")));
        verify(dao, never()).insert(anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("创建：请求方法不在白名单被拒绝")
    void invalidMethodRejected() {
        Map<String, Object> p = payload("配置B", "https://api.example.com/v1");
        p.put("method", "FETCH");
        assertThrows(BizException.class, () -> service.save(p));
    }

    @Test
    @DisplayName("创建：地址缺少协议或主机、包含空白均被拒绝")
    void invalidUrlRejected() {
        assertThrows(BizException.class, () -> service.save(payload("配置C", "api.example.com/v1")));
        assertThrows(BizException.class, () -> service.save(payload("配置C", "ftp://api.example.com")));
        assertThrows(BizException.class, () -> service.save(payload("配置C", "https://api.example.com/a b")));
    }

    @Test
    @DisplayName("创建：请求头名称非法或试图覆盖系统头被拒绝")
    void invalidHeadersRejected() {
        Map<String, Object> badName = payload("配置D", "https://api.example.com/v1");
        badName.put("headers", List.of(Map.of("name", "Bad Header", "value", "1")));
        assertThrows(BizException.class, () -> service.save(badName));

        Map<String, Object> hostHeader = payload("配置D", "https://api.example.com/v1");
        hostHeader.put("headers", List.of(Map.of("name", "Host", "value", "evil.example.com")));
        assertThrows(BizException.class, () -> service.save(hostHeader));
    }

    @Test
    @DisplayName("创建：参数类型不合法或 number 参数值非数字被拒绝")
    void invalidParamsRejected() {
        Map<String, Object> badType = payload("配置E", "https://api.example.com/v1");
        badType.put("params", List.of(Map.of("name", "x", "type", "date")));
        assertThrows(BizException.class, () -> service.save(badType));

        Map<String, Object> badNumber = payload("配置E", "https://api.example.com/v1");
        badNumber.put("params", List.of(Map.of("name", "page", "type", "number", "value", "abc")));
        assertThrows(BizException.class, () -> service.save(badNumber));
    }

    @Test
    @DisplayName("创建：请求体声明为 JSON 但不是合法 JSON 时被拒绝")
    void invalidJsonBodyRejected() {
        Map<String, Object> p = payload("配置F", "https://api.example.com/v1");
        p.put("body", "{name:}");
        assertThrows(BizException.class, () -> service.save(p));
    }

    @Test
    @DisplayName("创建：同名配置与数量上限均被拒绝")
    void duplicateNameAndLimitRejected() {
        when(dao.countByUser(1L)).thenReturn(0);
        when(dao.countByName(1L, "配置A")).thenReturn(1);
        assertThrows(BizException.class, () -> service.save(payload("配置A", "https://api.example.com/v1")));

        when(dao.countByUser(1L)).thenReturn(UserApiConfigService.MAX_PER_USER);
        BizException e = assertThrows(BizException.class,
                () -> service.save(payload("配置G", "https://api.example.com/v1")));
        assertTrue(e.getMessage().contains("上限"));
    }

    /* ---------------- 更新、复制与删除 ---------------- */

    @Test
    @DisplayName("更新：非本人配置被拒绝")
    void updateOtherUserConfigRejected() {
        when(dao.findById(5L)).thenReturn(Optional.of(row(5L, 2L, "别人的配置")));
        assertThrows(BizException.class, () -> service.save(withId(payload("别人的配置", "https://api.example.com/v1"), 5L)));
        verify(dao, never()).update(anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("更新：本人配置正常写入，空 id 之外字段不影响归属")
    void updateOwnConfig() {
        when(dao.findById(7L)).thenReturn(Optional.of(row(7L, 1L, "旧名称")));
        when(dao.countByName(1L, "新名称")).thenReturn(0);
        when(dao.update(eq(7L), eq(1L), eq("新名称"), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1);
        when(dao.findById(7L)).thenReturn(Optional.of(row(7L, 1L, "新名称")));

        Map<String, Object> out = service.save(withId(payload("新名称", "https://api.example.com/v1"), 7L));
        assertEquals("7", out.get("id"));
        assertEquals("新名称", out.get("name"));
    }

    @Test
    @DisplayName("复制：名称自动追加「副本」，超过上限时拒绝")
    void duplicateConfig() {
        when(dao.findById(3L)).thenReturn(Optional.of(row(3L, 1L, "配置A")));
        when(dao.countByUser(1L)).thenReturn(1);
        when(dao.countByName(1L, "配置A 副本")).thenReturn(0);
        stubInsert(11L);
        when(dao.findById(11L)).thenReturn(Optional.of(row(11L, 1L, "配置A 副本")));

        Map<String, Object> copy = service.duplicate(3L);
        assertEquals("配置A 副本", copy.get("name"));

        when(dao.countByUser(1L)).thenReturn(UserApiConfigService.MAX_PER_USER);
        assertThrows(BizException.class, () -> service.duplicate(3L));
    }

    @Test
    @DisplayName("删除：非本人配置被拒绝，本人配置正常删除")
    void removeRequiresOwnership() {
        when(dao.findById(9L)).thenReturn(Optional.of(row(9L, 2L, "别人的配置")));
        assertThrows(BizException.class, () -> service.remove(9L));

        when(dao.findById(8L)).thenReturn(Optional.of(row(8L, 1L, "我的配置")));
        when(dao.delete(8L, 1L)).thenReturn(1);
        service.remove(8L);
        verify(dao).delete(8L, 1L);
    }

    /* ---------------- 测试与调用 ---------------- */

    @Test
    @DisplayName("测试：默认值参与拼接，自动补 Content-Type 与 Accept")
    void testBuildsRequestFromDraft() {
        when(invoker.execute(any())).thenReturn(Map.of("ok", true));

        Map<String, Object> p = payload("临时测试", "https://api.example.com/v1/items");
        p.put("params", List.of(Map.of("name", "page", "type", "number", "defaultValue", "2")));
        service.test(p);

        ArgumentCaptor<UserApiInvokeService.Request> captor =
                ArgumentCaptor.forClass(UserApiInvokeService.Request.class);
        verify(invoker).execute(captor.capture());
        UserApiInvokeService.Request req = captor.getValue();
        assertEquals("GET", req.method());
        assertEquals("https://api.example.com/v1/items", req.url());
        assertEquals("2", req.queryParams().get("page"));
        assertEquals("{\"a\":1}", req.body());
        assertTrue(req.headers().get("Content-Type").startsWith("application/json"));
        assertTrue(req.headers().containsKey("Accept"));
        assertEquals("t-1", req.headers().get("X-Trace"));
    }

    @Test
    @DisplayName("调用：已停用的配置被拒绝，启用配置按保存内容调用")
    void invokeChecksEnabledAndBuildsRequest() {
        when(dao.findById(4L)).thenReturn(Optional.of(new UserApiConfigDao.Row(
                4L, 1L, "已停用", "POST", "https://api.example.com/v1",
                "[]", "[]", "none", "", "默认分组", "", 15000, 0, false,
                "2026-11-15 10:00", "2026-11-15 10:00")));
        assertThrows(BizException.class, () -> service.invoke(4L, Map.of()));

        when(dao.findById(6L)).thenReturn(Optional.of(new UserApiConfigDao.Row(
                6L, 1L, "已启用", "POST", "https://api.example.com/v1",
                "[]", "[{\"name\":\"page\",\"type\":\"number\",\"defaultValue\":\"1\",\"enabled\":true}]",
                "none", "", "默认分组", "", 15000, 1, true,
                "2026-11-15 10:00", "2026-11-15 10:00")));
        when(invoker.execute(any())).thenReturn(Map.of("ok", true));

        service.invoke(6L, Map.of("params", Map.of("page", "3")));

        ArgumentCaptor<UserApiInvokeService.Request> captor =
                ArgumentCaptor.forClass(UserApiInvokeService.Request.class);
        verify(invoker).execute(captor.capture());
        assertEquals("3", captor.getValue().queryParams().get("page"));
        assertEquals(1, captor.getValue().retries());
    }


    /* ---------------- 请求头加密（v2.1） ---------------- */

    private static Map<String, Object> headerPayload(String name, String url) {
        Map<String, Object> p = payload(name, url);
        p.put("headers", List.of(
                Map.of("name", "Authorization", "value", "Bearer super-secret-123"),
                Map.of("name", "X-Trace", "value", "trace-1")));
        return p;
    }

    @Test
    @DisplayName("加密：请求头值以 enc:v1: 密文落库，明文不出现在 SQL 参数中")
    void headersEncryptedOnCreate() {
        when(dao.countByUser(1L)).thenReturn(0);
        when(dao.countByName(1L, "加密配置")).thenReturn(0);
        stubInsert(11L);
        String stored = "[{\"name\":\"Authorization\",\"value\":\"" + cipher.encrypt("Bearer super-secret-123")
                + "\",\"enabled\":true}]";
        when(dao.findById(11L)).thenReturn(Optional.of(new UserApiConfigDao.Row(
                11L, 1L, "加密配置", "GET", "https://api.example.com/v1", stored, "[]",
                "none", "", "测试组", "简历,测试", 15000, 0, true,
                "2026-11-16 10:00", "2026-11-16 10:00")));

        service.save(headerPayload("加密配置", "https://api.example.com/v1"));

        ArgumentCaptor<String> headers = ArgumentCaptor.forClass(String.class);
        verify(dao).insert(eq(1L), eq("加密配置"), eq("GET"), eq("https://api.example.com/v1"),
                headers.capture(), anyString(), eq("json"), eq("{\"a\":1}"), eq("测试组"), eq("简历,测试"),
                anyInt(), anyInt(), eq(true));
        assertTrue(headers.getValue().contains("enc:v1:"), "请求头值应加密落库");
        assertTrue(headers.getValue().contains("X-Trace"), "请求头名称保持明文以便展示");
        assertFalse(headers.getValue().contains("super-secret-123"), "明文不得写入数据库");
    }

    @Test
    @DisplayName("回显：敏感头只返回掩码，普通头返回明文")
    void sensitiveHeaderMaskedOnRead() {
        String stored = "[{\"name\":\"Authorization\",\"value\":\"" + cipher.encrypt("Bearer abcdefghijkl")
                + "\",\"enabled\":true},{\"name\":\"X-Trace\",\"value\":\"trace-9\",\"enabled\":true}]";
        when(dao.findById(2L)).thenReturn(Optional.of(new UserApiConfigDao.Row(
                2L, 1L, "回显配置", "GET", "https://api.example.com/v1", stored, "[]",
                "none", "", "默认分组", "", 15000, 0, true,
                "2026-11-16 10:00", "2026-11-16 10:00")));

        Map<String, Object> out = service.get(2L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headers = (List<Map<String, Object>>) out.get("headers");
        Map<String, Object> auth = headers.get(0);
        assertEquals("Bear****ijkl", auth.get("value"));
        assertEquals(true, auth.get("masked"));
        assertFalse(String.valueOf(auth.get("value")).contains("abcdefghijkl"));
        assertEquals("trace-9", headers.get(1).get("value"));
        assertEquals(false, headers.get(1).get("masked"));
    }

    @Test
    @DisplayName("沿用：keep=true 时解密原密文重新加密，明文不丢失")
    void keepReusesStoredHeaderValue() throws Exception {
        String stored = "[{\"name\":\"Authorization\",\"value\":\"" + cipher.encrypt("Bearer keep-me-please")
                + "\",\"enabled\":true}]";
        when(dao.findById(3L)).thenReturn(Optional.of(new UserApiConfigDao.Row(
                3L, 1L, "改配置", "GET", "https://api.example.com/v1", stored, "[]",
                "none", "", "默认分组", "", 15000, 0, true,
                "2026-11-16 10:00", "2026-11-16 10:00")));
        when(dao.update(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(1);

        Map<String, Object> p = payload("改配置", "https://api.example.com/v1");
        p.put("headers", List.of(Map.of("name", "Authorization", "value", "", "keep", true)));
        service.save(withId(p, 3L));

        ArgumentCaptor<String> headers = ArgumentCaptor.forClass(String.class);
        verify(dao).update(eq(3L), eq(1L), eq("改配置"), eq("GET"), eq("https://api.example.com/v1"),
                headers.capture(), anyString(), eq("json"), eq("{\"a\":1}"), eq("测试组"), eq("简历,测试"),
                eq(60000), eq(3), eq(true));
        assertTrue(headers.getValue().contains("enc:v1:"));
        assertFalse(headers.getValue().contains("keep-me-please"), "重写后仍是密文");
        String storedValue = new ObjectMapper().readTree(headers.getValue()).get(0).get("value").asText();
        assertEquals("Bearer keep-me-please", cipher.decrypt(storedValue));
    }

    @Test
    @DisplayName("沿用：原配置没有该请求头时要求重新填写")
    void keepWithoutStoredValueRejected() {
        when(dao.findById(5L)).thenReturn(Optional.of(row(5L, 1L, "空配置")));
        Map<String, Object> p = payload("空配置", "https://api.example.com/v1");
        p.put("headers", List.of(Map.of("name", "Authorization", "value", "", "keep", true)));
        assertThrows(BizException.class, () -> service.save(withId(p, 5L)));
    }

    @Test
    @DisplayName("兼容：历史明文请求头可正常读取并调用")
    void legacyPlainHeaderStillWorks() {
        when(dao.findById(7L)).thenReturn(Optional.of(new UserApiConfigDao.Row(
                7L, 1L, "历史配置", "GET", "https://api.example.com/v1",
                "[{\"name\":\"X-Trace\",\"value\":\"legacy-1\",\"enabled\":true}]", "[]",
                "none", "", "默认分组", "", 15000, 0, true,
                "2026-11-16 10:00", "2026-11-16 10:00")));
        when(invoker.execute(any())).thenReturn(Map.of("ok", true));

        service.invoke(7L, Map.of());

        ArgumentCaptor<UserApiInvokeService.Request> captor =
                ArgumentCaptor.forClass(UserApiInvokeService.Request.class);
        verify(invoker).execute(captor.capture());
        assertEquals("legacy-1", captor.getValue().headers().get("X-Trace"));
    }

    @Test
    @DisplayName("迁移：启动时把历史明文请求头加密写回")
    void legacyHeadersMigratedOnStartup() {
        when(dao.listAll()).thenReturn(List.of(new UserApiConfigDao.Row(
                9L, 1L, "待迁移", "GET", "https://api.example.com/v1",
                "[{\"name\":\"Authorization\",\"value\":\"plain-secret\",\"enabled\":true}]", "[]",
                "none", "", "默认分组", "", 15000, 0, true,
                "2026-11-16 10:00", "2026-11-16 10:00")));
        when(dao.updateHeaders(eq(9L), anyString())).thenReturn(1);

        service.encryptLegacyHeaders();

        ArgumentCaptor<String> headers = ArgumentCaptor.forClass(String.class);
        verify(dao).updateHeaders(eq(9L), headers.capture());
        assertTrue(headers.getValue().contains("enc:v1:"));
        assertFalse(headers.getValue().contains("plain-secret"));
    }

    private static Map<String, Object> withId(Map<String, Object> payload, long id) {
        payload.put("id", id);
        return payload;
    }
}
