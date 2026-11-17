package cn.course.airesume.customapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 自定义 API 集成测试（v2.0）：真实 Spring 上下文 + MySQL + 本机 HttpServer，
 * 覆盖配置增删改查、分组/标签过滤、复制、归属隔离、真实调用与异常校验。
 *
 * <p>测试数据统一以 {@code IT-API-} 开头，开始与结束各清理一次，不影响演示数据。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserApiConfigIntegrationTest {

    private static final String PREFIX = "IT-API-";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private UserApiConfigService service;
    @Autowired
    private UserApiConfigDao dao;
    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer server;
    private int port;
    private long userId;
    private long otherUserId;
    private String createdId;

    @BeforeAll
    void setUp() throws Exception {
        userId = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE role='STUDENT' ORDER BY id LIMIT 1", Long.class);
        otherUserId = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE role='STUDENT' AND id<>? ORDER BY id LIMIT 1",
                Long.class, userId);
        cleanupData();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("method", exchange.getRequestMethod());
            payload.put("query", exchange.getRequestURI().getRawQuery());
            payload.put("authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            payload.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    void tearDown() {
        cleanupData();
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void login() {
        AuthContext.set(new LoginUser(userId, "it-student", "集成测试学生", "STUDENT", "ACTIVE"));
    }

    @AfterEach
    void logout() {
        AuthContext.clear();
    }

    private void cleanupData() {
        jdbc.update("DELETE FROM user_api_config WHERE name LIKE 'IT-API-%'");
    }

    private Map<String, Object> payload(String name, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("method", "POST");
        m.put("url", url);
        m.put("group", "IT-测试组");
        m.put("tags", "集成测试,接口");
        m.put("headers", List.of(Map.of("name", "X-Trace", "value", "it-001")));
        m.put("params", List.of(Map.of("name", "page", "type", "number", "defaultValue", "2")));
        m.put("bodyType", "json");
        m.put("body", "{\"hello\":\"世界\"}");
        m.put("timeoutMs", 5000);
        m.put("retries", 0);
        return m;
    }

    private String echoUrl() {
        return "http://127.0.0.1:" + port + "/echo";
    }

    @Test
    @Order(1)
    @DisplayName("创建：配置落库并返回完整结构")
    void createConfig() {
        Map<String, Object> out = service.save(payload(PREFIX + "甲", echoUrl()));

        createdId = String.valueOf(out.get("id"));
        assertNotNull(createdId);
        assertEquals(PREFIX + "甲", out.get("name"));
        assertEquals("POST", out.get("method"));
        assertEquals("IT-测试组", out.get("group"));
        assertEquals(List.of("集成测试", "接口"), out.get("tags"));
        assertEquals(1, ((List<?>) out.get("headers")).size());
        assertEquals(1, ((List<?>) out.get("params")).size());
        assertTrue(dao.findById(Long.parseLong(createdId)).isPresent());
        String stored = jdbc.queryForObject(
                "SELECT headers_json FROM user_api_config WHERE id=?", String.class,
                Long.parseLong(createdId));
        assertTrue(stored.contains("enc:v1:"), "请求头值应加密落库");
        assertFalse(stored.contains("it-001"), "明文不得写入数据库");
    }

    @Test
    @Order(10)
    @DisplayName("加密：Authorization 密文落库、回显掩码、调用时解密发送")
    void sensitiveHeaderEncrypted() {
        Map<String, Object> p = payload(PREFIX + "加密", echoUrl());
        p.put("headers", List.of(Map.of("name", "Authorization", "value", "Bearer it-secret-9999")));
        Map<String, Object> out = service.save(p);
        long id = Long.parseLong(String.valueOf(out.get("id")));

        String stored = jdbc.queryForObject(
                "SELECT headers_json FROM user_api_config WHERE id=?", String.class, id);
        assertTrue(stored.contains("enc:v1:"));
        assertFalse(stored.contains("it-secret-9999"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headers = (List<Map<String, Object>>) out.get("headers");
        assertEquals(true, headers.get(0).get("masked"));
        assertEquals("Bear****9999", headers.get(0).get("value"));

        Map<String, Object> result = service.invoke(id, Map.of());
        assertTrue(String.valueOf(result.get("body")).contains("Bearer it-secret-9999"),
                "调用时应解密并真实发送");
        service.remove(id);
    }

    @Test
    @Order(2)
    @DisplayName("查询：关键字、分组、标签过滤均命中同一条配置")
    void listWithFilters() {
        Map<String, Object> all = service.list(null, null, null);
        assertTrue(containsName(all, PREFIX + "甲"));

        assertTrue(containsName(service.list("甲", null, null), PREFIX + "甲"));
        assertTrue(containsName(service.list(null, "IT-测试组", null), PREFIX + "甲"));
        assertTrue(containsName(service.list(null, null, "集成测试"), PREFIX + "甲"));
        assertFalse(containsName(service.list(null, "不存在的分组", null), PREFIX + "甲"));

        assertTrue(((List<?>) all.get("groups")).contains("IT-测试组"));
        assertTrue(((List<?>) all.get("tags")).contains("集成测试"));
    }

    private boolean containsName(Map<String, Object> result, String name) {
        List<?> items = (List<?>) result.get("items");
        return items.stream().anyMatch(it -> name.equals(((Map<?, ?>) it).get("name")));
    }

    @Test
    @Order(3)
    @DisplayName("更新：修改名称、方法与分组后重新读取生效")
    void updateConfig() {
        Map<String, Object> p = payload(PREFIX + "乙", echoUrl());
        p.put("id", createdId);
        p.put("method", "PUT");
        p.put("group", "IT-修改后");
        Map<String, Object> out = service.save(p);

        assertEquals(PREFIX + "乙", out.get("name"));
        assertEquals("PUT", out.get("method"));
        assertEquals("IT-修改后", out.get("group"));
        assertEquals(createdId, String.valueOf(out.get("id")));
    }

    @Test
    @Order(4)
    @DisplayName("复制：自动生成「副本」名称，源配置保持不变")
    void duplicateConfig() {
        Map<String, Object> copy = service.duplicate(Long.parseLong(createdId));
        String copyName = String.valueOf(copy.get("name"));
        assertTrue(copyName.startsWith(PREFIX + "乙"));
        assertTrue(copyName.endsWith("副本"));
        assertFalse(createdId.equals(String.valueOf(copy.get("id"))));

        assertEquals(PREFIX + "乙", service.get(Long.parseLong(createdId)).get("name"));
    }

    @Test
    @Order(5)
    @DisplayName("归属：其他学生不可读取或调用该配置")
    void ownershipIsolated() {
        try {
            AuthContext.set(new LoginUser(otherUserId, "it-other", "其他学生", "STUDENT", "ACTIVE"));
            assertThrows(BizException.class, () -> service.get(Long.parseLong(createdId)));
            assertThrows(BizException.class, () -> service.invoke(Long.parseLong(createdId), Map.of()));
            assertFalse(containsName(service.list(null, null, null), PREFIX + "乙"));
        } finally {
            AuthContext.set(new LoginUser(userId, "it-student", "集成测试学生", "STUDENT", "ACTIVE"));
        }
    }

    @Test
    @Order(6)
    @DisplayName("调用：服务端代发真实请求，返回状态码、耗时与响应内容")
    void invokeSavedConfig() {
        Map<String, Object> result = service.invoke(Long.parseLong(createdId), Map.of());

        assertEquals(true, result.get("ok"));
        assertEquals(200, result.get("status"));
        assertEquals(1, result.get("attempts"));
        assertNotNull(result.get("durationMs"));
        assertTrue(String.valueOf(result.get("url")).contains("page=2"));
        assertTrue(String.valueOf(result.get("body")).contains("世界"));
        assertTrue(String.valueOf(result.get("body")).contains("PUT"));
    }

    @Test
    @Order(7)
    @DisplayName("测试：未保存的草稿也能直接发请求，且不会写入数据库")
    void testDraftWithoutPersisting() {
        String draftName = PREFIX + "草稿";
        Map<String, Object> result = service.test(payload(draftName, echoUrl()));

        assertEquals(true, result.get("ok"));
        assertEquals(200, result.get("status"));
        assertEquals(0, dao.countByName(userId, draftName));
    }

    @Test
    @Order(8)
    @DisplayName("异常：非法地址、非法参数类型、同名配置均被拒绝")
    void invalidPayloadsRejected() {
        assertThrows(BizException.class, () -> service.save(payload(PREFIX + "坏地址", "api.example.com")));

        Map<String, Object> badParam = payload(PREFIX + "坏参数", echoUrl());
        badParam.put("params", List.of(Map.of("name", "page", "type", "number", "value", "abc")));
        assertThrows(BizException.class, () -> service.save(badParam));

        assertThrows(BizException.class, () -> service.save(payload(PREFIX + "乙", echoUrl())));
    }

    @Test
    @Order(9)
    @DisplayName("删除：删除后详情不可读，列表不再包含")
    void deleteConfig() {
        service.remove(Long.parseLong(createdId));
        assertThrows(BizException.class, () -> service.get(Long.parseLong(createdId)));
        assertFalse(containsName(service.list(null, null, null), PREFIX + "乙"));
    }
}
