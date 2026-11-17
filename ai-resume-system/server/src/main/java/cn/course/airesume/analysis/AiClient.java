package cn.course.airesume.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * AI 客户端：调用 OpenAI 兼容的 chat/completions 接口（默认 DeepSeek）。
 * - 超时阈值 30 秒（BR-ANALYSIS-03），超时抛异常由上层置任务为失败；
 * - 返回内容必须是约定 JSON 结构，否则抛异常（BR-ANALYSIS-05）；
 * - 密钥只从环境变量/配置读取，不写入代码与前端；
 * - v1.7 起同时承担扫描件 OCR 的图片识别（多模态输入）。
 */
@Component
public class AiClient {
    private static final String SYSTEM_PROMPT =
            "你是简历优化助手。只输出 JSON，不要输出任何其他文字。JSON 结构："
                    + "{\"score\":0-100整数,\"missing\":[\"缺失关键词\"],"
                    + "\"suggestions\":[{\"module\":\"模块名\",\"level\":\"high|medium|low\",\"text\":\"建议内容\"}]}";

    private static final String OCR_PROMPT =
            "提取图片中的全部文字，按阅读顺序原样输出。保持原有换行与标点；"
                    + "不要解释、不要总结、不要翻译、不要补全看不清的内容。若图片中没有文字，输出空字符串。";

    /** 单次调用最多尝试次数（含首次），仅对 429/5xx 生效。 */
    private static final int MAX_ATTEMPTS = 2;

    private final AiProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public AiClient(AiProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                // 部分 OpenAI 兼容网关（含本机内网网关）不支持 h2c 升级，强制 HTTP/1.1
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record AiResult(int score, List<String> missing, List<Map<String, String>> suggestions) {}

    /** 执行分析；任何网络/超时/格式问题都抛出 AiException。 */
    public AiResult analyze(String resumeText, String jd, List<String> keywords) throws AiException {
        requireEndpoint();
        Map<String, Object> body = Map.of(
                "model", props.resolveModel(),
                "temperature", 0.3,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", buildPrompt(resumeText, jd, keywords))));
        String content = postChat(body);
        try {
            return parseResult(content);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("AI 返回内容格式不符合约定，本次结果已丢弃，可重试。");
        }
    }

    /**
     * 图片 OCR（多模态输入）：返回模型提取的纯文本。
     * model 为空时使用当前分析模型，因此扫描件识别默认复用同一网关。
     *
     * @param imageBytes 图片原始字节（PNG 或 JPEG）
     * @param mime       实际媒体类型，必须是 {@code image/png} 或 {@code image/jpeg}；
     *                   早期版本硬编码为 image/png，导致 JPG 简历被网关拒绝，这里改为按实参传入
     * @param model      多模态模型名，空则回落到分析模型
     */
    public String ocrImage(byte[] imageBytes, String mime, String model) throws AiException {
        requireEndpoint();
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String usedModel = (model == null || model.isBlank()) ? props.resolveModel() : model;
        String mediaType = (mime == null || mime.isBlank()) ? "image/png" : mime;
        Map<String, Object> body = Map.of(
                "model", usedModel,
                "temperature", 0,
                // 一页简历的纯文本约 1~2k tokens，显式给足上限，避免网关默认值过低导致截断
                "max_tokens", 4000,
                "messages", List.of(Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", OCR_PROMPT),
                        Map.of("type", "image_url",
                                "image_url", Map.of("url", "data:" + mediaType + ";base64," + b64))))));
        String text = postChat(body);
        return text == null ? "" : text.trim();
    }

    private void requireEndpoint() throws AiException {
        if (props.resolveEndpoint() == null || props.resolveEndpoint().isBlank()) {
            throw new AiException("未配置 AI 服务地址（AI_ENDPOINT）", true);
        }
    }

    /** 发送 chat/completions 请求并取出 message.content（已去掉 markdown 代码围栏）。 */
    private String postChat(Map<String, Object> body) throws AiException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(props.resolveEndpoint().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.resolveApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();

        String responseBody = null;
        // 仅对限流（429）与服务端错误（5xx）做一次短重试；超时不重试，避免超过 BR-ANALYSIS-03 的 30 秒阈值
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = res.statusCode();
                if (status / 100 == 2) {
                    responseBody = res.body();
                    break;
                }
                String detail = res.body() == null ? "" : res.body().replaceAll("\\s+", " ").trim();
                if (detail.length() > 300) detail = detail.substring(0, 300);
                AiException error = new AiException("AI 服务返回 HTTP " + status
                        + (detail.isEmpty() ? "" : "：" + detail));
                if (!isRetryableStatus(status) || attempt >= MAX_ATTEMPTS) throw error;
                backoff(attempt);
            } catch (java.net.http.HttpConnectTimeoutException e) {
                throw new AiException("无法连接 AI 服务（连接超时）", true);
            } catch (java.net.http.HttpTimeoutException e) {
                throw new AiException("AI 服务响应超时（超过 " + props.getTimeoutSeconds() + " 秒）");
            } catch (java.net.ConnectException | java.net.UnknownHostException | java.net.NoRouteToHostException e) {
                // 服务未启动、域名不存在、无法路由：属于「AI 不可用」，上层可降级为规则模式（BR-ANALYSIS-04）
                throw new AiException("AI 服务不可用：" + e.getMessage(), true);
            } catch (AiException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiException("AI 服务调用被中断，请重试。");
            } catch (Exception e) {
                throw new AiException("AI 服务调用失败：" + e.getMessage());
            }
        }
        if (responseBody == null) throw new AiException("AI 服务无响应，请稍后重试。");

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            // 兼容模型把内容包在 ```json ``` 中的情况
            return content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```", "").trim();
        } catch (Exception e) {
            throw new AiException("AI 返回内容格式不符合约定，本次结果已丢弃，可重试。");
        }
    }

    /** 限流与服务端错误可重试；4xx（密钥无效、参数错误）重试无意义。 */
    private static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    private static void backoff(int attempt) throws InterruptedException {
        Thread.sleep(300L * attempt);
    }

    private AiResult parseResult(String content) throws Exception {
        JsonNode node = objectMapper.readTree(content);
        if (!node.has("score") || !node.has("suggestions")) {
            throw new AiException("AI 返回内容格式不符合约定，本次结果已丢弃，可重试。");
        }
        int score = Math.max(0, Math.min(100, node.path("score").asInt()));
        List<String> missing = new ArrayList<>();
        node.path("missing").forEach(n -> missing.add(n.asText()));
        List<Map<String, String>> suggestions = new ArrayList<>();
        for (JsonNode s : node.path("suggestions")) {
            String level = s.path("level").asText("medium");
            if (!List.of("high", "medium", "low").contains(level)) level = "medium";
            Map<String, String> m = new LinkedHashMap<>();
            m.put("module", s.path("module").asText("综合"));
            m.put("level", level);
            m.put("text", s.path("text").asText(""));
            if (!m.get("text").isBlank()) suggestions.add(m);
        }
        if (suggestions.isEmpty()) {
            throw new AiException("AI 返回内容格式不符合约定，本次结果已丢弃，可重试。");
        }
        return new AiResult(score, missing, suggestions);
    }

    private String buildPrompt(String resumeText, String jd, List<String> keywords) {
        return "请分析以下简历与岗位 JD 的匹配程度。\n\n【岗位 JD】\n" + jd
                + "\n\n【JD 关键词】\n" + String.join("、", keywords)
                + "\n\n【简历内容】\n" + resumeText
                + "\n\n要求：score 为匹配度（0-100）；missing 列出 JD 关键词中简历未覆盖的项；"
                + "suggestions 按模块给出 2-5 条可执行修改建议。";
    }

    private String writeJson(Object o) throws AiException {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new AiException("请求序列化失败");
        }
    }

    public static class AiException extends Exception {
        /** true 表示服务不可达（未启动/域名错误），上层可降级规则模式；false 表示超时、HTTP 错误或格式异常，按失败处理。 */
        private final boolean unavailable;

        public AiException(String message) { this(message, false); }

        public AiException(String message, boolean unavailable) {
            super(message);
            this.unavailable = unavailable;
        }

        public boolean isUnavailable() { return unavailable; }
    }
}
