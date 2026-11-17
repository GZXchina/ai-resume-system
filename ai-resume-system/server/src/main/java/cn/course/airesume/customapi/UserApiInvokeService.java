package cn.course.airesume.customapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自定义 API 的真实调用器（v2.0）。
 *
 * <p>由服务端代发请求，避免浏览器跨域（CORS）限制，同时统一处理超时、重试与响应截断：
 * <ul>
 *   <li>超时：连接 5 秒、读取按配置 {@code timeoutMs}（1~60 秒）；</li>
 *   <li>重试：仅对网络异常、429、5xx 重试，最多 3 次，间隔递增；4xx 不重试；</li>
 *   <li>截断：响应体最多保留 64KB，超出标记 {@code bodyTruncated=true}；</li>
 *   <li>脱敏：请求/响应中的 Authorization、api-key、Cookie 等只回显掩码。</li>
 * </ul>
 * 网络类失败不抛异常，统一返回 {@code ok=false} 与中文原因，便于页面直接展示。
 */
@Service
public class UserApiInvokeService {
    private static final Logger log = LoggerFactory.getLogger(UserApiInvokeService.class);

    /** HttpClient 不允许手工设置的请求头，统一在配置校验阶段拒绝。 */
    static final Set<String> UNSAFE_HEADERS = Set.of(
            "host", "content-length", "connection", "expect", "upgrade", "transfer-encoding");

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie");

    /** 敏感请求头：存储加密、接口只回显掩码。 */
    static boolean isSensitiveHeader(String name) {
        return name != null && SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** 统计响应大小时最多读取的字节数，避免异常大响应拖垮线程。 */
    private static final int MAX_COUNT_BYTES = 1_000_000;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Request(String method, String url, Map<String, String> queryParams,
                          Map<String, String> headers, String body,
                          int timeoutMs, int retries) {
    }

    public Map<String, Object> execute(Request req) {
        String finalUrl = buildUrl(req.url(), req.queryParams());
        Map<String, String> sentHeaders = new LinkedHashMap<>(req.headers());
        long start = System.nanoTime();
        int attempts = 0;
        int status = 0;
        String body = "";
        boolean truncated = false;
        long bytes = 0;
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        String failure = null;

        for (int i = 0; i <= req.retries(); i++) {
            attempts++;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(finalUrl))
                        .timeout(Duration.ofMillis(req.timeoutMs()))
                        .method(req.method(), req.body() == null || req.body().isEmpty()
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(req.body(), StandardCharsets.UTF_8));
                sentHeaders.forEach(builder::header);
                HttpResponse<InputStream> resp = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                status = resp.statusCode();
                responseHeaders = flatten(resp.headers().map());
                Text read = readLimited(resp.body(), MAX_RESPONSE_BYTES);
                body = read.text();
                truncated = read.truncated();
                bytes = read.bytes();
                if (shouldRetry(status) && i < req.retries()) {
                    sleep(200L * (i + 1));
                    continue;
                }
                break;
            } catch (HttpTimeoutException e) {
                failure = "请求超时：超过 " + req.timeoutMs() + " 毫秒未得到响应";
                status = 0;
                if (i < req.retries()) {
                    sleep(200L * (i + 1));
                    continue;
                }
                break;
            } catch (IOException e) {
                failure = describe(e);
                status = 0;
                if (i < req.retries()) {
                    sleep(200L * (i + 1));
                    continue;
                }
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = "请求被中断";
                status = 0;
                break;
            } catch (IllegalArgumentException e) {
                failure = "请求地址或请求头不合法：" + e.getMessage();
                status = 0;
                break;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", failure == null && status >= 200 && status < 400);
        out.put("method", req.method());
        out.put("url", finalUrl);
        out.put("status", status);
        out.put("statusText", statusText(status));
        out.put("durationMs", (System.nanoTime() - start) / 1_000_000);
        out.put("attempts", attempts);
        out.put("requestHeaders", maskHeaders(sentHeaders));
        out.put("requestBody", truncate(req.body(), 2000));
        out.put("responseHeaders", maskHeaders(responseHeaders));
        out.put("body", body);
        out.put("bodyTruncated", truncated);
        out.put("bodyBytes", bytes);
        if (failure != null) out.put("message", failure);
        if (failure != null) {
            log.debug("自定义 API 调用失败 {} {}：{}", req.method(), finalUrl, failure);
        }
        return out;
    }

    private static boolean shouldRetry(int status) {
        return status == 429 || status >= 500;
    }

    private static String buildUrl(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) return url;
        String base = url;
        String fragment = "";
        int hash = base.indexOf('#');
        if (hash >= 0) {
            fragment = base.substring(hash);
            base = base.substring(0, hash);
        }
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((k, v) -> {
            if (k == null || k.isBlank()) return;
            joiner.add(encode(k) + "=" + encode(v == null ? "" : v));
        });
        if (joiner.length() == 0) return base + fragment;
        String sep = base.contains("?") ? (base.endsWith("?") || base.endsWith("&") ? "" : "&") : "?";
        return base + sep + joiner + fragment;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Text(String text, boolean truncated, long bytes) {
    }

    private static Text readLimited(InputStream in, int limit) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                int room = limit - out.size();
                if (room > 0) out.write(buffer, 0, Math.min(n, room));
                if (total >= MAX_COUNT_BYTES) break;
            }
            return new Text(out.toString(StandardCharsets.UTF_8), total > limit, total);
        }
    }

    private static Map<String, String> flatten(Map<String, java.util.List<String>> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k, v == null ? "" : String.join(", ", v)));
        return out;
    }

    private static Map<String, String> maskHeaders(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(k,
                SENSITIVE_HEADERS.contains(k.toLowerCase(Locale.ROOT)) ? mask(v) : v));
        return out;
    }

    static String mask(String value) {
        if (value == null || value.isBlank()) return "";
        String s = value.trim();
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…（已截断）";
    }

    private static String describe(IOException e) {
        if (e instanceof UnknownHostException) return "域名无法解析：" + e.getMessage();
        if (e instanceof ConnectException) return "无法连接到目标服务器：" + e.getMessage();
        if (e instanceof SSLException) return "HTTPS 证书校验失败：" + e.getMessage();
        return "请求失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 415 -> "Unsupported Media Type";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "";
        };
    }
}
