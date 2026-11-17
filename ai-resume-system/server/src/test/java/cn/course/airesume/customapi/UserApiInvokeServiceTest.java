package cn.course.airesume.customapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自定义 API 调用器单元测试（v2.0）：使用本机 HttpServer 模拟目标接口，
 * 覆盖查询参数拼接、请求头与请求体、重试、超时、不可达、响应截断与敏感头掩码。
 */
class UserApiInvokeServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer server;
    private static int port;
    private static final AtomicInteger hits = new AtomicInteger();
    private static volatile int failTimes = 0;
    private static volatile long delayMs = 0;

    private final UserApiInvokeService invoker = new UserApiInvokeService();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("method", exchange.getRequestMethod());
            payload.put("query", exchange.getRequestURI().getRawQuery());
            payload.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            payload.put("auth", exchange.getRequestHeaders().getFirst("Authorization"));
            payload.put("custom", exchange.getRequestHeaders().getFirst("X-Trace"));
            respond(exchange, 200, MAPPER.writeValueAsString(payload), "application/json");
        });
        server.createContext("/fail", exchange -> {
            int n = hits.incrementAndGet();
            boolean fail = n <= failTimes;
            respond(exchange, fail ? 500 : 200, fail ? "boom" : "ok", "text/plain");
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late", "text/plain");
        });
        server.createContext("/big", exchange ->
                respond(exchange, 200, "a".repeat(100_000), "text/plain"));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void reset() {
        hits.set(0);
        failTimes = 0;
        delayMs = 0;
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    @DisplayName("GET：查询参数按类型拼接，请求头正常发送，Authorization 回显掩码")
    void getBuildsQueryAndMasksSensitiveHeader() throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("q", "简历");
        query.put("page", "2");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Trace", "trace-001");
        headers.put("Authorization", "Bearer secret-token-12345678");

        Map<String, Object> result = invoker.execute(new UserApiInvokeService.Request(
                "GET", url("/echo"), query, headers, null, 5000, 0));

        assertEquals(true, result.get("ok"));
        assertEquals(200, result.get("status"));
        assertEquals(1, result.get("attempts"));
        assertTrue((Long) result.get("durationMs") >= 0);
        assertTrue(String.valueOf(result.get("url")).contains("page=2"));
        assertTrue(String.valueOf(result.get("url")).contains("q=%E7%AE%80%E5%8E%86"));

        @SuppressWarnings("unchecked")
        Map<String, String> sent = (Map<String, String>) result.get("requestHeaders");
        assertEquals("Bear****5678", sent.get("Authorization"));
        assertFalse(String.valueOf(sent.get("Authorization")).contains("secret-token"));

        @SuppressWarnings("unchecked")
        Map<String, String> respHeaders = (Map<String, String>) result.get("responseHeaders");
        assertEquals("application/json", respHeaders.get("content-type"));
        assertTrue(String.valueOf(result.get("body")).contains("trace-001"));
    }

    @Test
    @DisplayName("POST：请求体原样发送，目标可读取到 JSON 内容")
    void postSendsBody() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=UTF-8");
        Map<String, Object> result = invoker.execute(new UserApiInvokeService.Request(
                "POST", url("/echo"), Map.of(), headers, "{\"name\":\"林知远\"}", 5000, 0));

        assertEquals(true, result.get("ok"));
        assertEquals(200, result.get("status"));
        assertTrue(String.valueOf(result.get("body")).contains("林知远"));
        assertTrue(String.valueOf(result.get("requestBody")).contains("林知远"));
    }

    @Test
    @DisplayName("重试：首次 500、第二次 200 时返回成功且 attempts=2")
    void retryOnServerError() {
        failTimes = 1;
        Map<String, Object> result = invoker.execute(new UserApiInvokeService.Request(
                "GET", url("/fail"), Map.of(), Map.of(), null, 5000, 1));

        assertEquals(true, result.get("ok"));
        assertEquals(200, result.get("status"));
        assertEquals(2, result.get("attempts"));
        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("不重试：retries=0 时 5xx 直接返回失败结果而不抛异常")
    void serverErrorWithoutRetry() {
        failTimes = 5;
        Map<String, Object> result = invoker.execute(new UserApiInvokeService.Request(
                "GET", url("/fail"), Map.of(), Map.of(), null, 5000, 0));

        assertEquals(false, result.get("ok"));
        assertEquals(500, result.get("status"));
        assertEquals(1, result.get("attempts"));
        assertEquals("boom", result.get("body"));
    }

    @Test
    @DisplayName("超时：目标响应超过 timeoutMs 时返回中文超时提示")
    void timeoutReportsMessage() {
        delayMs = 700;
        Map<String, Object> result = invoker.execute(new UserApiInvokeService.Request(
                "GET", url("/slow"), Map.of(), Map.of(), null, 200, 0));

        assertEquals(false, result.get("ok"));
        assertEquals(0, result.get("status"));
        assertTrue(String.valueOf(result.get("message")).contains("超时"));
    }

    @Test
    @DisplayName("不可达：连接被拒绝时返回失败与可读原因")
    void unreachableHostReportsFailure() {
        Map<String, Object> result = invoker.execute(new UserApiInvokeService.Request(
                "GET", "http://127.0.0.1:9/health", Map.of(), Map.of(), null, 2000, 0));

        assertEquals(false, result.get("ok"));
        assertEquals(0, result.get("status"));
        assertNotNull(result.get("message"));
        assertFalse(String.valueOf(result.get("message")).isBlank());
    }

    @Test
    @DisplayName("截断：超过 64KB 的响应只保留前 64KB 并标记 bodyTruncated")
    void bigResponseIsTruncated() {
        Map<String, Object> result = invoker.execute(new UserApiInvokeService.Request(
                "GET", url("/big"), Map.of(), Map.of(), null, 5000, 0));

        assertEquals(true, result.get("ok"));
        assertEquals(true, result.get("bodyTruncated"));
        assertTrue(String.valueOf(result.get("body")).length() <= 64 * 1024);
        assertEquals(100_000L, result.get("bodyBytes"));
    }
}
