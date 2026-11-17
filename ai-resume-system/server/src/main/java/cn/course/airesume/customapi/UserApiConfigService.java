package cn.course.airesume.customapi;

import cn.course.airesume.analysis.AiKeyCipher;
import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 自定义 API 配置的校验、持久化与调用编排（v2.0）。
 *
 * <p>配置项归属当前登录学生，所有读写都做归属校验；请求头/参数/请求体在落库前统一
 * 规整为 JSON，调用时再转成真实 HTTP 请求交给 {@link UserApiInvokeService} 执行。
 *
 * <p>v2.1 起请求头值以 AES-256-GCM 密文（{@code enc:v1:} 前缀）落库：调用前解密，
 * 接口回显时敏感头（Authorization/Cookie/api-key 等）只给掩码，前端以 {@code keep=true}
 * 表示「沿用原值」，避免把掩码当成新值写回。
 */
@Service
public class UserApiConfigService {
    private static final Logger log = LoggerFactory.getLogger(UserApiConfigService.class);

    static final int MAX_PER_USER = 100;
    static final int MAX_HEADERS = 20;
    static final int MAX_PARAMS = 30;
    static final int MAX_TAGS = 10;
    static final int MAX_BODY_LENGTH = 20000;
    static final int DEFAULT_TIMEOUT_MS = 15000;
    static final int MIN_TIMEOUT_MS = 1000;
    static final int MAX_TIMEOUT_MS = 60000;
    static final int MAX_RETRIES = 3;

    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    static final Set<String> PARAM_TYPES = Set.of("string", "number", "boolean", "array", "object");
    static final Set<String> BODY_TYPES = Set.of("none", "json", "form", "text");

    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,40}");
    private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z0-9_.\\-\\[\\]]{1,60}");
    private static final TypeReference<List<Map<String, Object>>> ROW_LIST = new TypeReference<>() {
    };

    private final UserApiConfigDao dao;
    private final UserApiInvokeService invoker;
    private final ObjectMapper mapper;
    private final AiKeyCipher cipher;

    public UserApiConfigService(UserApiConfigDao dao, UserApiInvokeService invoker,
                                ObjectMapper mapper, AiKeyCipher cipher) {
        this.dao = dao;
        this.invoker = invoker;
        this.mapper = mapper;
        this.cipher = cipher;
    }

    /**
     * 启动时把历史明文请求头加密（幂等）：只处理非空且未带 {@code enc:v1:} 前缀的值，
     * 失败不影响启动，下一次保存该配置时会重新加密。
     */
    @PostConstruct
    void encryptLegacyHeaders() {
        try {
            int migrated = 0;
            for (UserApiConfigDao.Row row : dao.listAll()) {
                String json = row.headersJson();
                if (json == null || json.isBlank()) continue;
                List<Map<String, Object>> rows = parseJson(json);
                boolean dirty = rows.stream()
                        .anyMatch(h -> !str(h.get("value")).isEmpty() && !AiKeyCipher.isEncrypted(str(h.get("value"))));
                if (!dirty) continue;
                if (dao.updateHeaders(row.id(), secureHeadersJson(rows)) > 0) migrated++;
            }
            if (migrated > 0) log.info("自定义 API：已加密 {} 条历史明文请求头", migrated);
        } catch (Exception e) {
            log.warn("自定义 API 历史请求头加密失败（不影响启动）：{}", e.getMessage());
        }
    }

    /* ---------------- 查询 ---------------- */

    public Map<String, Object> list(String q, String group, String tag) {
        LoginUser me = AuthContext.require();
        List<Map<String, Object>> items = dao.listByUser(me.id(), q, group, tag).stream()
                .map(this::toClient).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", items.size());
        out.put("groups", dao.groups(me.id()));
        out.put("tags", collectTags(dao.tags(me.id())));
        out.put("limit", MAX_PER_USER);
        return out;
    }

    public Map<String, Object> get(long id) {
        return toClient(requireOwn(id, AuthContext.require()));
    }

    /* ---------------- 增删改 ---------------- */

    public Map<String, Object> save(Map<String, Object> payload) {
        LoginUser me = AuthContext.require();
        Long id = parseId(payload.get("id"));
        // 更新时先取原配置：敏感请求头回显为掩码，前端以 keep=true 表示沿用原值
        UserApiConfigDao.Row existing = id == null ? null : requireOwn(id, me);
        Config c = normalize(payload, existing);
        String headersJson = secureHeadersJson(c.headers());

        if (id == null) {
            if (dao.countByUser(me.id()) >= MAX_PER_USER) {
                throw new BizException("自定义 API 数量已达上限（" + MAX_PER_USER + " 个），请先删除不再使用的配置");
            }
            if (dao.countByName(me.id(), c.name()) > 0) {
                throw new BizException("已存在同名配置：" + c.name() + "，请换一个名称");
            }
            long newId = dao.insert(me.id(), c.name(), c.method(), c.url(), headersJson,
                    c.paramsJson(), c.bodyType(), c.body(), c.group(), c.tagsText(),
                    c.timeoutMs(), c.retries(), c.enabled());
            if (newId < 0) throw new BizException("保存失败，请稍后重试");
            return toClient(dao.findById(newId).orElseThrow(() -> new BizException("保存后读取失败")));
        }

        if (!existing.name().equals(c.name()) && dao.countByName(me.id(), c.name()) > 0) {
            throw new BizException("已存在同名配置：" + c.name() + "，请换一个名称");
        }
        int rows = dao.update(id, me.id(), c.name(), c.method(), c.url(), headersJson,
                c.paramsJson(), c.bodyType(), c.body(), c.group(), c.tagsText(),
                c.timeoutMs(), c.retries(), c.enabled());
        if (rows == 0) throw new BizException("配置不存在或无权修改");
        return toClient(dao.findById(id).orElseThrow(() -> new BizException("配置不存在")));
    }

    public void remove(long id) {
        LoginUser me = AuthContext.require();
        requireOwn(id, me);
        if (dao.delete(id, me.id()) == 0) throw new BizException("配置不存在或无权删除");
    }

    public Map<String, Object> duplicate(long id) {
        LoginUser me = AuthContext.require();
        UserApiConfigDao.Row src = requireOwn(id, me);
        if (dao.countByUser(me.id()) >= MAX_PER_USER) {
            throw new BizException("自定义 API 数量已达上限（" + MAX_PER_USER + " 个），请先删除不再使用的配置");
        }
        String name = uniqueCopyName(me.id(), src.name());
        // 复制时解密再重新加密，避免两条配置共用同一份密文
        long newId = dao.insert(me.id(), name, src.method(), src.url(),
                secureHeadersJson(parseJson(src.headersJson()).stream().map(this::decryptHeader).toList()),
                src.paramsJson(), src.bodyType(), src.bodyJson(), src.groupName(), src.tags(),
                src.timeoutMs(), src.retries(), src.enabled());
        if (newId < 0) throw new BizException("复制失败，请稍后重试");
        return toClient(dao.findById(newId).orElseThrow(() -> new BizException("复制后读取失败")));
    }

    /* ---------------- 测试与调用 ---------------- */

    /** 按页面当前填写的配置直接测试（不落库）；敏感头留空时沿用已保存的密文。 */
    public Map<String, Object> test(Map<String, Object> payload) {
        LoginUser me = AuthContext.require();
        Long id = parseId(payload.get("id"));
        UserApiConfigDao.Row existing = null;
        if (id != null) {
            try {
                existing = requireOwn(id, me);
            } catch (BizException ignored) {
                // 测试接口不因归属问题泄露他人配置：按新建配置处理
            }
        }
        Config c = normalize(payload, existing);
        return invoker.execute(buildRequest(c, null));
    }

    /** 调用已保存的配置，overrides 可覆盖参数、请求头与请求体。 */
    public Map<String, Object> invoke(long id, Map<String, Object> overrides) {
        LoginUser me = AuthContext.require();
        UserApiConfigDao.Row row = requireOwn(id, me);
        if (!row.enabled()) throw new BizException("该配置已停用，请先启用后再调用");
        Config c = fromRow(row);
        return invoker.execute(buildRequest(c, overrides));
    }

    /* ---------------- 规整与校验 ---------------- */

    /** 规整后的配置（落库与调用共用）。 */
    record Config(String name, String method, String url,
                  List<Map<String, Object>> headers, List<Map<String, Object>> params,
                  String bodyType, String body, String group, List<String> tags,
                  int timeoutMs, int retries, boolean enabled) {
        String paramsJson() { return json(params); }
        String tagsText() { return String.join(",", tags); }

        private String json(List<Map<String, Object>> value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new BizException("配置序列化失败");
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Config normalize(Map<String, Object> payload, UserApiConfigDao.Row existing) {
        String name = str(payload.get("name"));
        if (name.isEmpty()) throw new BizException("API 名称不能为空");
        if (name.length() > 60) throw new BizException("API 名称不能超过 60 字");

        String method = str(payload.get("method")).toUpperCase(Locale.ROOT);
        if (method.isEmpty()) method = "GET";
        if (!METHODS.contains(method)) {
            throw new BizException("请求方法不合法：" + method + "，可选 GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS");
        }

        String url = str(payload.get("url"));
        validateUrl(url);

        List<Map<String, Object>> headers = normalizeHeaders(payload.get("headers"), existing);
        List<Map<String, Object>> params = normalizeParams(payload.get("params"));

        String bodyType = str(payload.get("bodyType")).toLowerCase(Locale.ROOT);
        if (bodyType.isEmpty()) bodyType = "none";
        if (!BODY_TYPES.contains(bodyType)) {
            throw new BizException("请求体类型不合法：" + bodyType + "，可选 none/json/form/text");
        }
        String body = normalizeBody(bodyType, str(payload.get("body")));

        String group = str(payload.get("group"));
        if (group.isEmpty()) group = "默认分组";
        if (group.length() > 40) throw new BizException("分组名称不能超过 40 字");

        List<String> tags = normalizeTags(payload.get("tags"));

        int timeoutMs = clamp(intVal(payload.get("timeoutMs"), DEFAULT_TIMEOUT_MS),
                MIN_TIMEOUT_MS, MAX_TIMEOUT_MS);
        int retries = clamp(intVal(payload.get("retries"), 0), 0, MAX_RETRIES);
        boolean enabled = boolVal(payload.get("enabled"), true);

        return new Config(name, method, url, headers, params, bodyType, body, group, tags,
                timeoutMs, retries, enabled);
    }

    private static void validateUrl(String url) {
        if (url.isEmpty()) throw new BizException("接口地址不能为空");
        if (url.length() > 500) throw new BizException("接口地址不能超过 500 字");
        if (url.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException("接口地址不能包含空格，请检查是否有多余字符");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new BizException("接口地址格式不正确，例如 https://api.example.com/v1");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BizException("接口地址必须以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BizException("接口地址缺少主机名，例如 https://api.example.com/v1");
        }
    }

    private List<Map<String, Object>> normalizeHeaders(Object raw, UserApiConfigDao.Row existing) {
        List<Map<String, Object>> source = asList(raw);
        Map<String, String> stored = new LinkedHashMap<>();
        if (existing != null) {
            for (Map<String, Object> h : parseJson(existing.headersJson())) {
                stored.put(str(h.get("name")), cipher.decrypt(str(h.get("value"))));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : source) {
            String headerName = str(item.get("name"));
            String value = str(item.get("value"));
            boolean keep = boolVal(item.get("keep"), false);
            if (keep) {
                if (!stored.containsKey(headerName)) {
                    throw new BizException("请求头 " + (headerName.isEmpty() ? "" : headerName + " ")
                            + "的值需重新填写（原值仅保存过密文，无法回显）");
                }
                value = stored.get(headerName);
            }
            if (headerName.isEmpty() && value.isEmpty()) continue;
            if (headerName.isEmpty()) throw new BizException("请求头名称不能为空");
            if (!HEADER_NAME.matcher(headerName).matches()) {
                throw new BizException("请求头名称不合法：" + headerName + "（仅允许字母、数字与连字符）");
            }
            if (UserApiInvokeService.UNSAFE_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                throw new BizException("请求头 " + headerName + " 由系统管理，不能手工设置");
            }
            if (value.length() > 500) throw new BizException("请求头 " + headerName + " 的值不能超过 500 字");
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new BizException("请求头 " + headerName + " 的值不能包含换行");
            }
            if (out.size() >= MAX_HEADERS) throw new BizException("请求头最多 " + MAX_HEADERS + " 个");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", headerName);
            row.put("value", value);
            row.put("enabled", boolVal(item.get("enabled"), true));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> normalizeParams(Object raw) {
        List<Map<String, Object>> source = asList(raw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : source) {
            String paramName = str(item.get("name"));
            String value = str(item.get("value"));
            String defaultValue = str(item.get("defaultValue"));
            if (paramName.isEmpty() && value.isEmpty() && defaultValue.isEmpty()) continue;
            if (out.size() >= MAX_PARAMS) throw new BizException("请求参数最多 " + MAX_PARAMS + " 个");
            if (!PARAM_NAME.matcher(paramName).matches()) {
                throw new BizException("参数名不合法：" + paramName + "（仅允许字母、数字、下划线、点、中划线）");
            }
            String type = str(item.get("type")).toLowerCase(Locale.ROOT);
            if (type.isEmpty()) type = "string";
            if (!PARAM_TYPES.contains(type)) {
                throw new BizException("参数 " + paramName + " 的类型不合法：" + type + "，可选 string/number/boolean/array/object");
            }
            if (value.length() > 1000 || defaultValue.length() > 1000) {
                throw new BizException("参数 " + paramName + " 的值不能超过 1000 字");
            }
            String effective = value.isEmpty() ? defaultValue : value;
            coerce(paramName, type, effective);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", paramName);
            row.put("value", value);
            row.put("defaultValue", defaultValue);
            row.put("type", type);
            row.put("enabled", boolVal(item.get("enabled"), true));
            row.put("description", str(item.get("description")));
            out.add(row);
        }
        return out;
    }

    /** 按声明类型校验并返回参与拼接的字符串值。 */
    private String coerce(String paramName, String type, String value) {
        if (value == null || value.isEmpty()) return "";
        switch (type) {
            case "number" -> {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new BizException("参数 " + paramName + " 的类型为 number，值必须是数字");
                }
            }
            case "boolean" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new BizException("参数 " + paramName + " 的类型为 boolean，值只能是 true 或 false");
                }
            }
            case "array", "object" -> {
                try {
                    JsonNode node = mapper.readTree(value);
                    boolean ok = "array".equals(type) ? node.isArray() : node.isObject();
                    if (!ok) throw new BizException("参数 " + paramName + " 的类型为 " + type + "，值必须是合法 JSON " + type);
                } catch (BizException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BizException("参数 " + paramName + " 的值不是合法 JSON");
                }
            }
            default -> {
                // string：原样参与拼接
            }
        }
        return value;
    }

    private String normalizeBody(String bodyType, String body) {
        if ("none".equals(bodyType)) return "";
        if (body.length() > MAX_BODY_LENGTH) throw new BizException("请求体不能超过 " + MAX_BODY_LENGTH + " 字");
        if ("json".equals(bodyType) && !body.isBlank()) {
            try {
                mapper.readTree(body);
            } catch (Exception e) {
                throw new BizException("请求体不是合法 JSON，请检查引号与括号是否成对");
            }
        }
        if ("form".equals(bodyType) && !body.isBlank()) {
            StringBuilder sb = new StringBuilder();
            for (String line : body.split("\\r?\\n")) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) continue;
                int eq = text.indexOf('=');
                if (eq <= 0) throw new BizException("表单请求体每行需为 key=value 形式：" + text);
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(text.substring(0, eq).trim(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(text.substring(eq + 1).trim(), StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
        return body;
    }

    private List<String> normalizeTags(Object raw) {
        List<String> rawTags;
        if (raw instanceof List<?> list) {
            rawTags = list.stream().map(UserApiConfigService::str).toList();
        } else {
            String text = str(raw);
            rawTags = List.of(text.split("[,，]"));
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String t : rawTags) {
            String tag = t.trim();
            if (tag.isEmpty()) continue;
            if (tag.length() > 20) throw new BizException("标签不能超过 20 字：" + tag);
            unique.add(tag);
        }
        if (unique.size() > MAX_TAGS) throw new BizException("标签最多 " + MAX_TAGS + " 个");
        String joined = String.join(",", unique);
        if (joined.length() > 200) throw new BizException("标签总长度不能超过 200 字");
        return new ArrayList<>(unique);
    }

    /* ---------------- 调用参数组装 ---------------- */

    private UserApiInvokeService.Request buildRequest(Config c, Map<String, Object> overrides) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map<String, Object> h : c.headers()) {
            if (Boolean.TRUE.equals(h.get("enabled"))) {
                headers.put(str(h.get("name")), str(h.get("value")));
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (Map<String, Object> p : c.params()) {
            if (!Boolean.TRUE.equals(p.get("enabled"))) continue;
            String name = str(p.get("name"));
            String value = str(p.get("value"));
            if (value.isEmpty()) value = str(p.get("defaultValue"));
            String type = str(p.get("type"));
            if (type.isEmpty()) type = "string";
            params.put(name, coerce(name, type, value));
        }

        String body = "none".equals(c.bodyType()) ? null : c.body();

        if (overrides != null) {
            Object oh = overrides.get("headers");
            if (oh instanceof Map<?, ?> map) {
                map.forEach((k, v) -> {
                    String key = String.valueOf(k).trim();
                    if (!key.isEmpty()) headers.put(key, v == null ? "" : String.valueOf(v));
                });
            }
            Object op = overrides.get("params");
            if (op instanceof Map<?, ?> map) {
                map.forEach((k, v) -> {
                    String key = String.valueOf(k).trim();
                    if (!key.isEmpty()) params.put(key, v == null ? "" : String.valueOf(v));
                });
            }
            if (overrides.get("body") != null) {
                body = String.valueOf(overrides.get("body"));
            }
        }

        boolean hasContentType = headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Content-Type"));
        if (body != null && !hasContentType) {
            switch (c.bodyType()) {
                case "json" -> headers.put("Content-Type", "application/json; charset=UTF-8");
                case "form" -> headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                default -> headers.put("Content-Type", "text/plain; charset=UTF-8");
            }
        }
        boolean hasAccept = headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Accept"));
        if (!hasAccept) headers.put("Accept", "application/json, */*");

        return new UserApiInvokeService.Request(c.method(), c.url(), params, headers, body,
                c.timeoutMs(), c.retries());
    }

    private Config fromRow(UserApiConfigDao.Row row) {
        return new Config(row.name(), row.method(), row.url(),
                parseJson(row.headersJson()).stream().map(this::decryptHeader).toList(),
                parseJson(row.paramsJson()),
                row.bodyType(), row.bodyJson() == null ? "" : row.bodyJson(),
                row.groupName(), splitTags(row.tags()),
                row.timeoutMs(), row.retries(), row.enabled());
    }

    private List<Map<String, Object>> parseJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, ROW_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    /* ---------------- 请求头加解密 ---------------- */

    /** 落库前把请求头值加密：非空、未加密的值统一转成 {@code enc:v1:} 密文。 */
    private String secureHeadersJson(List<Map<String, Object>> headers) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> h : headers) {
            Map<String, Object> row = new LinkedHashMap<>(h);
            String value = str(h.get("value"));
            if (!value.isEmpty() && !AiKeyCipher.isEncrypted(value)) row.put("value", cipher.encrypt(value));
            out.add(row);
        }
        try {
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new BizException("配置序列化失败");
        }
    }

    private Map<String, Object> decryptHeader(Map<String, Object> h) {
        Map<String, Object> row = new LinkedHashMap<>(h);
        row.put("value", cipher.decrypt(str(h.get("value"))));
        return row;
    }

    /** 回显结构：敏感头只给掩码，其余头解密后原样返回，附带 masked/hasValue 供前端判断。 */
    private List<Map<String, Object>> displayHeaders(String headersJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> h : parseJson(headersJson)) {
            Map<String, Object> row = new LinkedHashMap<>();
            String name = str(h.get("name"));
            String plain = cipher.decrypt(str(h.get("value")));
            boolean sensitive = UserApiInvokeService.isSensitiveHeader(name);
            row.put("name", name);
            row.put("value", sensitive ? UserApiInvokeService.mask(plain) : plain);
            row.put("masked", sensitive && !plain.isEmpty());
            row.put("hasValue", !plain.isEmpty());
            row.put("enabled", boolVal(h.get("enabled"), true));
            out.add(row);
        }
        return out;
    }

    /* ---------------- 输出结构 ---------------- */

    Map<String, Object> toClient(UserApiConfigDao.Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(row.id()));
        m.put("name", row.name());
        m.put("method", row.method());
        m.put("url", row.url());
        m.put("headers", displayHeaders(row.headersJson()));
        m.put("params", parseJson(row.paramsJson()));
        m.put("bodyType", row.bodyType());
        m.put("body", row.bodyJson() == null ? "" : row.bodyJson());
        m.put("group", row.groupName());
        m.put("tags", splitTags(row.tags()));
        m.put("timeoutMs", row.timeoutMs());
        m.put("retries", row.retries());
        m.put("enabled", row.enabled());
        m.put("createdAt", row.createdAt());
        m.put("updatedAt", row.updatedAt());
        return m;
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : tags.split(",")) {
            String tag = t.trim();
            if (!tag.isEmpty()) out.add(tag);
        }
        return out;
    }

    private static List<String> collectTags(List<String> rawTags) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        rawTags.forEach(t -> set.addAll(splitTags(t)));
        return new ArrayList<>(set);
    }

    private String uniqueCopyName(long userId, String base) {
        String candidate = base + " 副本";
        if (candidate.length() > 60) candidate = base.substring(0, Math.min(base.length(), 56)) + " 副本";
        if (dao.countByName(userId, candidate) == 0) return candidate;
        for (int i = 2; i < 100; i++) {
            String suffix = " 副本(" + i + ")";
            String prefix = base.substring(0, Math.min(base.length(), 60 - suffix.length()));
            String name = prefix + suffix;
            if (dao.countByName(userId, name) == 0) return name;
        }
        throw new BizException("复制失败：同名配置过多，请先整理已有配置");
    }

    private UserApiConfigDao.Row requireOwn(long id, LoginUser me) {
        UserApiConfigDao.Row row = dao.findById(id)
                .orElseThrow(() -> new BizException("自定义 API 不存在"));
        if (row.userId() != me.id()) throw new BizException("无权访问该配置");
        return row;
    }

    private static List<Map<String, Object>> asList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> map) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((k, v) -> item.put(String.valueOf(k), v));
                    out.add(item);
                }
            }
            return out;
        }
        return List.of();
    }

    private static Long parseId(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new BizException("配置 ID 格式不正确");
        }
    }

    private static int intVal(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        String text = str(value);
        if (text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean boolVal(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        String text = str(value);
        if (text.isEmpty()) return fallback;
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
