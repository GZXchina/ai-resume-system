package cn.course.airesume.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预置 AI 模型厂商注册表（v1.9）。
 *
 * <p>只保存「静态元数据」：默认接口地址、默认模型、API-KEY 格式、获取密钥指引。
 * 用户实际的厂商 / 地址 / 密钥 / 模型存放在 sys_config，运行期由 {@link AiProperties} 覆盖。
 *
 * <p>密钥正则仅用于「格式提示与前端实时校验」，不做真实有效性判断；
 * 真实有效性由保存时的连通性测试保证。
 */
public final class AiVendor {

    /** OpenAI 系厂商密钥：sk- 前缀 + 足够长度的随机串。 */
    private static final String SK_KEY_PATTERN = "^sk-[A-Za-z0-9_\\-]{16,200}$";
    /** 国产厂商常见的 UUID / AK-SK / 混合格式密钥。 */
    private static final String GENERIC_KEY_PATTERN = "^[A-Za-z0-9_\\-.:+=/]{8,200}$";

    private final String id;
    private final String name;
    private final String defaultEndpoint;
    private final String defaultModel;
    private final String keyPattern;
    private final String keyHint;
    private final String keyUrl;
    private final boolean openaiCompatible;
    /** 是否支持 GET {endpoint}/models 探测；不支持时退化为地址可达性探测。 */
    private final boolean probeModels;
    private final boolean requiresCustomEndpoint;
    private final String note;

    private AiVendor(String id, String name, String defaultEndpoint, String defaultModel,
                     String keyPattern, String keyHint, String keyUrl, boolean openaiCompatible,
                     boolean probeModels, boolean requiresCustomEndpoint, String note) {
        this.id = id;
        this.name = name;
        this.defaultEndpoint = defaultEndpoint;
        this.defaultModel = defaultModel;
        this.keyPattern = keyPattern;
        this.keyHint = keyHint;
        this.keyUrl = keyUrl;
        this.openaiCompatible = openaiCompatible;
        this.probeModels = probeModels;
        this.requiresCustomEndpoint = requiresCustomEndpoint;
        this.note = note;
    }

    private static final List<AiVendor> ALL = List.of(
            new AiVendor("openai", "OpenAI",
                    "https://api.openai.com/v1", "gpt-4o-mini",
                    SK_KEY_PATTERN, "以 sk- 开头，可在平台创建后仅显示一次",
                    "https://platform.openai.com/api-keys", true, true, false,
                    "官方接口，需可访问外网"),
            new AiVendor("anthropic", "Anthropic Claude",
                    "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest",
                    "^sk-ant-[A-Za-z0-9_\\-]{16,200}$", "以 sk-ant- 开头",
                    "https://console.anthropic.com/settings/keys", true, true, false,
                    "通过 Anthropic 的 OpenAI 兼容层调用"),
            new AiVendor("gemini", "Google Gemini",
                    "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash",
                    "^AIza[A-Za-z0-9_\\-]{20,}$", "以 AIza 开头的 39 位密钥",
                    "https://aistudio.google.com/app/apikey", true, true, false,
                    "使用 Gemini 的 OpenAI 兼容端点"),
            new AiVendor("azure-openai", "Azure OpenAI",
                    "", "",
                    "^[A-Za-z0-9_\\-]{16,200}$", "Azure 门户中「密钥和终结点」里的 Key",
                    "https://portal.azure.com", true, false, true,
                    "地址必须填到部署级：https://{资源名}.openai.azure.com/openai/deployments/{部署名}；模型名即部署名"),
            new AiVendor("deepseek", "DeepSeek 深度求索",
                    "https://api.deepseek.com/v1", "deepseek-chat",
                    SK_KEY_PATTERN, "以 sk- 开头",
                    "https://platform.deepseek.com/api_keys", true, true, false,
                    "国内直连，OpenAI 兼容"),
            new AiVendor("qwen", "阿里云通义千问",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
                    SK_KEY_PATTERN, "以 sk- 开头（DashScope API-KEY）",
                    "https://bailian.console.aliyun.com/?apiKey=1", true, true, false,
                    "百炼平台 OpenAI 兼容模式"),
            new AiVendor("zhipu", "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
                    "^[0-9a-fA-F]{16,32}\\.[A-Za-z0-9]{8,}$", "形如 id.secret 的两段式密钥",
                    "https://bigmodel.cn/usercenter/apikeys", true, true, false,
                    "国内直连，OpenAI 兼容"),
            new AiVendor("moonshot", "月之暗面 Kimi",
                    "https://api.moonshot.cn/v1", "moonshot-v1-8k",
                    SK_KEY_PATTERN, "以 sk- 开头",
                    "https://platform.moonshot.cn/console/api-keys", true, true, false,
                    "国内直连，OpenAI 兼容"),
            new AiVendor("doubao", "字节豆包（火山方舟）",
                    "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k",
                    GENERIC_KEY_PATTERN, "方舟控制台的 API-KEY（或 AK/SK 组合）",
                    "https://console.volcengine.com/ark", true, false, false,
                    "模型名需填方舟的接入点 ID（ep-xxxx）或模型 ID"),
            new AiVendor("baidu", "百度文心（千帆）",
                    "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-128k",
                    GENERIC_KEY_PATTERN, "千帆控制台创建的 API-KEY",
                    "https://console.bce.baidu.com/iam/#/iam/apikey/list", true, true, false,
                    "千帆 v2 OpenAI 兼容接口"),
            new AiVendor("custom", "自定义 / 私有网关",
                    "", "",
                    GENERIC_KEY_PATTERN, "按目标网关要求填写，8~200 位可见字符",
                    "", true, true, true,
                    "适用于自建网关、代理站或上表未覆盖的厂商；需自行填写接口地址")
    );

    private static final Map<String, AiVendor> BY_ID = new LinkedHashMap<>();

    static {
        for (AiVendor v : ALL) BY_ID.put(v.id, v);
    }

    /** 全部预置厂商（按展示顺序）。 */
    public static List<AiVendor> all() {
        return ALL;
    }

    /** 按 id 查找；未知返回 null。 */
    public static AiVendor find(String id) {
        return id == null ? null : BY_ID.get(id.trim().toLowerCase());
    }

    /** 依据接口地址反查厂商 id；匹配不到时返回 custom（历史配置迁移用）。 */
    public static String detectByEndpoint(String endpoint) {
        String normalized = normalize(endpoint);
        if (normalized.isEmpty()) return "custom";
        for (AiVendor v : ALL) {
            if (!v.defaultEndpoint.isEmpty() && normalize(v.defaultEndpoint).equals(normalized)) {
                return v.id;
            }
        }
        return "custom";
    }

    private static String normalize(String endpoint) {
        if (endpoint == null) return "";
        String s = endpoint.trim().toLowerCase();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 供管理端接口输出的厂商清单（不含任何密钥）。 */
    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiVendor v : ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.id);
            m.put("name", v.name);
            m.put("defaultEndpoint", v.defaultEndpoint);
            m.put("defaultModel", v.defaultModel);
            m.put("keyPattern", v.keyPattern);
            m.put("keyHint", v.keyHint);
            m.put("keyUrl", v.keyUrl);
            m.put("openaiCompatible", v.openaiCompatible);
            m.put("requiresCustomEndpoint", v.requiresCustomEndpoint);
            m.put("note", v.note);
            out.add(m);
        }
        return out;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDefaultEndpoint() { return defaultEndpoint; }
    public String getDefaultModel() { return defaultModel; }
    public String getKeyPattern() { return keyPattern; }
    public String getKeyHint() { return keyHint; }
    public String getKeyUrl() { return keyUrl; }
    public boolean isOpenaiCompatible() { return openaiCompatible; }
    public boolean isProbeModels() { return probeModels; }
    public boolean isRequiresCustomEndpoint() { return requiresCustomEndpoint; }
    public String getNote() { return note; }

    /** 密钥是否符合该厂商的格式。 */
    public boolean matchesKey(String apiKey) {
        return apiKey != null && apiKey.matches(keyPattern);
    }
}
