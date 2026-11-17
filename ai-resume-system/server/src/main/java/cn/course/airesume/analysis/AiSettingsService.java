package cn.course.airesume.analysis;

import cn.course.airesume.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * AI 模型设置（v1.7 模型切换 / v1.9 厂商选择与密钥配置）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>配置存放在 sys_config：{@code ai.vendor} / {@code ai.endpoint} / {@code ai.api-key-enc} / {@code ai.model}；</li>
 *   <li>API-KEY 用 AES-GCM 加密后落库，读取时只在内存中解密，接口只返回掩码；</li>
 *   <li>未设置时回落到 application.yml，保持原有部署方式可用（历史数据零迁移）；</li>
 *   <li>保存前自动做连通性测试，失败可 force=true 强制保存；保存后写入运行期属性，下一次分析立即生效；</li>
 *   <li>模型清单由服务端代理网关 /models 获取，密钥不出后端；清单缓存 10 分钟。</li>
 * </ul>
 */
@Service
public class AiSettingsService {
    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

    private static final String KEY_MODEL = "ai.model";
    private static final String KEY_VENDOR = "ai.vendor";
    private static final String KEY_ENDPOINT = "ai.endpoint";
    private static final String KEY_API_KEY = "ai.api-key-enc";

    private static final String MODEL_PATTERN = "^[A-Za-z0-9][A-Za-z0-9:._/-]{1,79}$";
    private static final int MAX_ENDPOINT_LENGTH = 300;
    private static final int MAX_KEY_LENGTH = 200;
    private static final long CACHE_TTL_MILLIS = 10 * 60_000L;

    private static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS sys_config (
              config_key   VARCHAR(64)  NOT NULL,
              config_value VARCHAR(500) NOT NULL,
              updated_at   DATETIME     NOT NULL,
              PRIMARY KEY (config_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci""";

    private final JdbcTemplate jdbc;
    private final AiProperties props;
    private final ObjectMapper objectMapper;
    private final AiKeyCipher cipher;
    private final HttpClient http;

    private volatile List<Map<String, Object>> modelCache;
    private volatile long modelCacheAt;
    private volatile String cacheNote = "";

    public AiSettingsService(JdbcTemplate jdbc, AiProperties props, ObjectMapper objectMapper, AiKeyCipher cipher) {
        this.jdbc = jdbc;
        this.props = props;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    @PostConstruct
    void init() {
        try {
            jdbc.execute(CREATE_SQL);
        } catch (Exception e) {
            log.error("AI 设置表初始化失败，将使用配置文件：{}", e.getMessage());
            return;
        }
        try {
            String vendor = read(KEY_VENDOR);
            String endpoint = read(KEY_ENDPOINT);
            String encrypted = read(KEY_API_KEY);
            String model = read(KEY_MODEL);

            if (notBlank(vendor)) props.setRuntimeVendor(vendor.trim());
            if (notBlank(endpoint)) props.setRuntimeEndpoint(endpoint.trim());
            if (notBlank(model)) props.setRuntimeModel(model.trim());
            if (notBlank(encrypted)) {
                String plain = cipher.decrypt(encrypted);
                if (notBlank(plain)) {
                    props.setRuntimeApiKey(plain);
                } else {
                    log.warn("sys_config 中的 API-KEY 解密失败，已忽略并回落到环境变量密钥");
                }
            }

            // 历史数据迁移：早期版本只写了 ai.model，没有 ai.vendor。
            // 这里按生效地址反查预置厂商并补写，保证升级后配置页能正确回显厂商。
            if (!notBlank(props.getRuntimeVendor())) {
                String detected = AiVendor.detectByEndpoint(props.resolveEndpoint());
                props.setRuntimeVendor(detected);
                writeQuietly(KEY_VENDOR, detected);
            }

            log.info("AI 配置已加载：vendor={}, endpoint={}, model={}, keySource={}",
                    props.resolveVendor(), props.resolveEndpoint(), props.resolveModel(), keySource());
        } catch (Exception e) {
            log.error("读取管理端 AI 配置失败，将使用配置文件：{}", e.getMessage());
        }
    }

    /** 当前设置（不含明文密钥）。 */
    public Map<String, Object> settings() {
        AiVendor vendor = AiVendor.find(props.resolveVendor());
        String endpoint = props.resolveEndpoint();
        String effectiveKey = props.resolveApiKey();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", props.resolveModel());
        m.put("defaultModel", props.getModel());
        m.put("source", notBlank(props.getRuntimeModel()) ? "db" : "config");
        m.put("aiEnabled", props.isEnabled());
        m.put("aiUsable", props.isUsable());
        m.put("endpoint", endpoint == null ? "" : endpoint);

        m.put("vendor", props.resolveVendor());
        m.put("vendorName", vendor == null ? "自定义 / 私有网关" : vendor.getName());
        m.put("defaultEndpoint", vendor == null ? "" : vendor.getDefaultEndpoint());
        m.put("vendorDefaultModel", vendor == null ? "" : vendor.getDefaultModel());
        m.put("requiresCustomEndpoint", vendor == null || vendor.isRequiresCustomEndpoint());
        m.put("apiKeyConfigured", notBlank(effectiveKey));
        m.put("apiKeyMasked", AiKeyCipher.mask(effectiveKey));
        m.put("apiKeySource", keySource());
        return m;
    }

    /** 预置厂商清单（不含密钥），供配置页下拉使用。 */
    public List<Map<String, Object>> vendors() {
        return AiVendor.list();
    }

    /**
     * 保存厂商配置（v1.9）：厂商 + 接口地址 + API-KEY + 模型。
     *
     * <p>body 字段：vendor / endpoint / apiKey / model / clearApiKey / force / skipTest。
     * apiKey 留空表示保持原有密钥不变；clearApiKey=true 表示清除已存密钥并回落到环境变量。
     * 默认在保存前做一次连通性测试，force=true 可跳过（用于临时不可达但确认配置正确的场景）。
     */
    public Map<String, Object> updateSettings(Map<String, Object> body) {
        String vendorId = str(body.get("vendor"));
        AiVendor vendor = vendorId.isBlank()
                ? AiVendor.find(props.resolveVendor())
                : AiVendor.find(vendorId);
        if (vendor == null) {
            throw new BizException("未知的 AI 厂商：" + vendorId + "，请从预置列表中选择");
        }

        String endpoint = str(body.get("endpoint")).trim();
        if (endpoint.isEmpty()) endpoint = vendor.getDefaultEndpoint();
        if (endpoint.isEmpty()) {
            throw new BizException("请填写接口地址（" + vendor.getName() + " 没有内置默认地址）");
        }
        validateEndpoint(endpoint);

        String model = str(body.get("model")).trim();
        if (model.isEmpty()) model = vendor.getDefaultModel();
        if (!model.isEmpty() && !model.matches(MODEL_PATTERN)) {
            throw new BizException("模型 ID 格式不正确：仅允许字母、数字与 : . _ / - ，长度 2~80");
        }

        boolean clearKey = bool(body.get("clearApiKey"));
        String typedKey = str(body.get("apiKey")).trim();
        boolean keyChanged = false;
        String effectiveKey;
        if (clearKey) {
            keyChanged = true;
            effectiveKey = notBlank(props.getApiKey()) ? props.getApiKey() : "";
        } else if (!typedKey.isEmpty()) {
            validateApiKey(vendor, typedKey);
            keyChanged = true;
            effectiveKey = typedKey;
        } else {
            // 未填写：沿用已存密钥；若从未配置则用环境变量密钥
            effectiveKey = props.resolveApiKey();
        }

        Map<String, Object> test = null;
        if (!bool(body.get("force")) && !bool(body.get("skipTest"))) {
            test = probe(vendor, endpoint, effectiveKey);
            if (!Boolean.TRUE.equals(test.get("ok"))) {
                throw new BizException("连通性测试未通过：" + test.get("message")
                        + "。请核对接口地址与 API-KEY 后重试，或确认无误后选择「仍然保存」。");
            }
        }

        save(KEY_VENDOR, vendor.getId());
        save(KEY_ENDPOINT, endpoint);
        if (model.isEmpty()) {
            delete(KEY_MODEL);
        } else {
            save(KEY_MODEL, model);
        }
        if (keyChanged) {
            if (typedKey.isEmpty()) {
                delete(KEY_API_KEY);
            } else {
                save(KEY_API_KEY, cipher.encrypt(typedKey));
            }
        }

        props.setRuntimeVendor(vendor.getId());
        props.setRuntimeEndpoint(endpoint);
        props.setRuntimeModel(model.isEmpty() ? null : model);
        if (keyChanged) props.setRuntimeApiKey(typedKey.isEmpty() ? null : typedKey);

        invalidateModelCache();
        log.info("AI 厂商配置已更新：vendor={}, endpoint={}, model={}, keyChanged={}",
                vendor.getId(), endpoint, model, keyChanged);

        Map<String, Object> out = settings();
        if (test != null) out.put("test", test);
        return out;
    }

    /** 连通性测试（不落库）：使用入参，缺省时回落到当前生效配置。 */
    public Map<String, Object> testConnection(Map<String, Object> body) {
        String vendorId = str(body.get("vendor"));
        AiVendor vendor = vendorId.isBlank() ? AiVendor.find(props.resolveVendor()) : AiVendor.find(vendorId);
        if (vendor == null) {
            throw new BizException("未知的 AI 厂商：" + vendorId);
        }
        String endpoint = str(body.get("endpoint")).trim();
        if (endpoint.isEmpty()) {
            endpoint = notBlank(vendor.getDefaultEndpoint()) ? vendor.getDefaultEndpoint() : str(props.resolveEndpoint());
        }
        if (endpoint.isEmpty()) {
            throw new BizException("请先填写接口地址");
        }
        validateEndpoint(endpoint);

        String key = str(body.get("apiKey")).trim();
        if (!key.isEmpty()) {
            validateApiKey(vendor, key);
        } else {
            key = props.resolveApiKey();
        }
        return probe(vendor, endpoint, key);
    }

    /** 兼容旧接口：只切换模型，不影响厂商 / 地址 / 密钥。传空表示恢复配置文件默认值。 */
    public Map<String, Object> updateModel(String model) {
        String v = model == null ? "" : model.trim();
        if (v.isEmpty()) {
            delete(KEY_MODEL);
            props.setRuntimeModel(null);
            invalidateModelCache();
            log.info("已恢复默认模型：{}", props.resolveModel());
            return settings();
        }
        if (!v.matches(MODEL_PATTERN)) {
            throw new BizException("模型 ID 格式不正确：仅允许字母、数字与 : . _ / - ，长度 2~80");
        }
        save(KEY_MODEL, v);
        props.setRuntimeModel(v);
        invalidateModelCache();
        log.info("分析模型已切换为：{}", v);
        return settings();
    }

    /** 网关可用模型清单；缓存 10 分钟，refresh=true 强制刷新。 */
    public Map<String, Object> models(boolean refresh) {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> cached = modelCache;
        boolean fresh = cached != null && now - modelCacheAt < CACHE_TTL_MILLIS;
        if (fresh && !refresh) {
            return payload(cached, false, cacheNote);
        }
        try {
            List<Map<String, Object>> list = fetchModels();
            modelCache = list;
            modelCacheAt = now;
            cacheNote = "";
            return payload(list, false, "");
        } catch (BizException e) {
            if (cached != null) {
                // 网关临时不可用：返回上次清单，前端标注可能过期
                return payload(cached, true, e.getMessage());
            }
            throw e;
        }
    }

    /* ---------------- 内部实现 ---------------- */

    private void invalidateModelCache() {
        modelCache = null;
        modelCacheAt = 0;
        cacheNote = "";
    }

    private String keySource() {
        if (notBlank(props.getRuntimeApiKey())) return "db";
        if (notBlank(props.getApiKey())) return "config";
        return "none";
    }

    /** 合法接口地址：http/https + 主机名 + 无空白字符。 */
    private void validateEndpoint(String endpoint) {
        if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new BizException("接口地址过长（最多 " + MAX_ENDPOINT_LENGTH + " 个字符）");
        }
        if (endpoint.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException("接口地址不能包含空格或换行");
        }
        try {
            URI uri = new URI(endpoint);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new BizException("接口地址需以 http:// 或 https:// 开头");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BizException("接口地址缺少主机名，例如 https://api.openai.com/v1");
            }
        } catch (URISyntaxException e) {
            throw new BizException("接口地址格式不正确：" + e.getReason());
        }
    }

    /** 密钥格式校验：只做「看起来像」的检查，真实有效性由连通性测试保证。 */
    private void validateApiKey(AiVendor vendor, String apiKey) {
        if (apiKey.length() > MAX_KEY_LENGTH) {
            throw new BizException("API-KEY 过长（最多 " + MAX_KEY_LENGTH + " 个字符）");
        }
        if (apiKey.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException("API-KEY 不能包含空格或换行");
        }
        if (!vendor.matchesKey(apiKey)) {
            throw new BizException(vendor.getName() + " 的 API-KEY 格式不符：" + vendor.getKeyHint());
        }
    }

    /** 连通性探测：优先 GET {endpoint}/models；不支持模型列表的厂商只探测地址可达性。 */
    private Map<String, Object> probe(AiVendor vendor, String endpoint, String apiKey) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vendor", vendor.getId());
        r.put("vendorName", vendor.getName());
        r.put("endpoint", endpoint);
        if (!notBlank(apiKey)) {
            r.put("ok", false);
            r.put("latencyMs", 0);
            r.put("message", "未配置 API-KEY，无法完成连通性测试");
            return r;
        }
        boolean probeModels = vendor.isProbeModels();
        String url = endpoint.replaceAll("/+$", "") + (probeModels ? "/models" : "");
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            r.put("latencyMs", System.currentTimeMillis() - start);
            if (probeModels && code / 100 == 2) {
                int count = countModels(res.body());
                r.put("ok", true);
                r.put("modelCount", count);
                r.put("message", count > 0 ? "连接成功，读取到 " + count + " 个可用模型" : "连接成功（模型列表为空）");
            } else if (code >= 500) {
                r.put("ok", false);
                r.put("message", "接口返回 HTTP " + code + detail(res.body()));
            } else if (!probeModels) {
                r.put("ok", true);
                r.put("message", "地址可达（HTTP " + code + "）；该厂商不支持模型列表探测，已按可达处理");
            } else if (code == 404 || code == 405) {
                r.put("ok", true);
                r.put("message", "地址可达（HTTP " + code + "），但该地址不提供模型列表；如密钥有误请以实际分析结果为准");
            } else {
                r.put("ok", false);
                r.put("message", "接口返回 HTTP " + code + detail(res.body()) + "，请检查 API-KEY 是否有效");
            }
        } catch (HttpConnectTimeoutException | java.net.ConnectException
                 | java.net.UnknownHostException | java.net.NoRouteToHostException e) {
            r.put("ok", false);
            r.put("latencyMs", System.currentTimeMillis() - start);
            r.put("message", "无法连接 " + endpoint + "，请检查接口地址与网络");
        } catch (HttpTimeoutException e) {
            r.put("ok", false);
            r.put("latencyMs", System.currentTimeMillis() - start);
            r.put("message", "连接超时（超过 10 秒），服务可能不可达");
        } catch (Exception e) {
            r.put("ok", false);
            r.put("latencyMs", System.currentTimeMillis() - start);
            r.put("message", "连通性测试失败：" + e.getMessage());
        }
        return r;
    }

    private int countModels(String body) {
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            return data.isArray() ? data.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String detail(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "" : "：" + (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private Map<String, Object> payload(List<Map<String, Object>> list, boolean stale, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("models", list);
        m.put("count", list.size());
        m.put("stale", stale);
        m.put("note", note == null ? "" : note);
        m.put("current", props.resolveModel());
        return m;
    }

    private List<Map<String, Object>> fetchModels() {
        if (!props.isUsable()) {
            throw new BizException("AI 服务未启用或未配置密钥，无法读取模型列表");
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.resolveEndpoint().replaceAll("/+$", "") + "/models"))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + props.resolveApiKey())
                .GET()
                .build();
        String body;
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new BizException("AI 网关返回 HTTP " + res.statusCode() + "，无法读取模型列表");
            }
            body = res.body();
        } catch (BizException e) {
            throw e;
        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException
                 | java.net.UnknownHostException e) {
            throw new BizException("无法连接 AI 网关，请检查网络或服务地址");
        } catch (Exception e) {
            throw new BizException("读取模型列表失败：" + e.getMessage());
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) throw new BizException("AI 网关返回的模型列表格式不正确");
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode n : data) {
                String id = n.path("id").asText("");
                if (id.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("name", n.path("name").asText(id));
                m.put("credits", n.path("credits").asText(""));
                m.put("description", n.path("description").asText(""));
                m.put("supportsImages", n.path("supports_images").asBoolean(false));
                m.put("contextLength", n.path("context_length").asInt(0));
                m.put("isDefault", n.path("is_default").asBoolean(false));
                list.add(m);
            }
            if (list.isEmpty()) throw new BizException("AI 网关未返回任何模型");
            return list;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("解析模型列表失败：" + e.getMessage());
        }
    }

    /* ---------------- sys_config 读写 ---------------- */

    private String read(String key) {
        List<String> values = jdbc.queryForList(
                "SELECT config_value FROM sys_config WHERE config_key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    private void save(String key, String value) {
        jdbc.update("INSERT INTO sys_config (config_key, config_value, updated_at) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), updated_at = VALUES(updated_at)",
                key, value, new Timestamp(System.currentTimeMillis()));
    }

    private void delete(String key) {
        jdbc.update("DELETE FROM sys_config WHERE config_key = ?", key);
    }

    private void writeQuietly(String key, String value) {
        try {
            save(key, value);
        } catch (Exception e) {
            log.warn("写入配置 {} 失败：{}", key, e.getMessage());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o).trim());
    }
}
