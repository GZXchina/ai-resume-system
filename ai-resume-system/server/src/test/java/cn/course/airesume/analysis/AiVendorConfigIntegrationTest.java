package cn.course.airesume.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.course.airesume.common.BizException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * 厂商配置集成测试（v1.9）：真实 Spring 上下文 + MySQL，验证落库、加密、厂商切换、
 * 自定义配置、异常场景与历史数据迁移。
 *
 * <p>测试开始前备份 sys_config 全部 ai.* 配置，结束后原样恢复，
 * 不影响正在运行的实例与历史配置。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiVendorConfigIntegrationTest {

    private static final String KEY_VENDOR = "ai.vendor";
    private static final String KEY_ENDPOINT = "ai.endpoint";
    private static final String KEY_API_KEY = "ai.api-key-enc";

    @Autowired
    private AiSettingsService service;
    @Autowired
    private AiProperties props;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiKeyCipher cipher;

    private final Map<String, String> backup = new LinkedHashMap<>();

    @BeforeAll
    void snapshotAndReset() {
        backup.clear();
        for (Map<String, Object> row : jdbc.queryForList("SELECT config_key, config_value FROM sys_config")) {
            backup.put(String.valueOf(row.get("config_key")), String.valueOf(row.get("config_value")));
        }
        deleteAiConfig();
        props.setRuntimeVendor(null);
        props.setRuntimeEndpoint(null);
        props.setRuntimeApiKey(null);
        props.setRuntimeModel(null);
    }

    @AfterAll
    void restore() {
        deleteAiConfig();
        for (Map.Entry<String, String> e : backup.entrySet()) {
            if (!e.getKey().startsWith("ai.")) continue;
            jdbc.update("INSERT INTO sys_config (config_key, config_value, updated_at) VALUES (?, ?, ?)",
                    e.getKey(), e.getValue(), new Timestamp(System.currentTimeMillis()));
        }
        service.init();
    }

    private void deleteAiConfig() {
        jdbc.update("DELETE FROM sys_config WHERE config_key LIKE 'ai.%'");
    }

    private String configValue(String key) {
        List<String> values = jdbc.queryForList(
                "SELECT config_value FROM sys_config WHERE config_key = ?", String.class, key);
        return values.isEmpty() ? null : values.get(0);
    }

    @Test
    @Order(1)
    @DisplayName("厂商清单：11 个预置厂商且不含密钥字段")
    void vendorsAreExposed() {
        List<Map<String, Object>> list = service.vendors();
        assertEquals(11, list.size());
        assertTrue(list.stream().anyMatch(v -> "deepseek".equals(v.get("id"))));
        assertTrue(list.stream().anyMatch(v -> "azure-openai".equals(v.get("id"))));
        assertTrue(list.stream().anyMatch(v -> "custom".equals(v.get("id"))));
        list.forEach(v -> {
            assertFalse(v.containsKey("apiKey"));
            assertFalse(v.containsKey("apiKeyMasked"));
            assertNotNull(v.get("keyPattern"));
        });
    }

    @Test
    @Order(2)
    @DisplayName("厂商切换：自动加载默认地址与默认模型，密钥保持不变")
    void switchVendorLoadsDefaults() {
        Map<String, Object> first = service.updateSettings(Map.of(
                "vendor", "deepseek", "apiKey", "sk-integration-key-0001", "skipTest", true));
        assertEquals("https://api.deepseek.com/v1", first.get("endpoint"));
        assertEquals("deepseek-chat", first.get("model"));
        assertEquals(true, first.get("apiKeyConfigured"));

        Map<String, Object> second = service.updateSettings(Map.of("vendor", "qwen", "skipTest", true));
        assertEquals("qwen", second.get("vendor"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", second.get("endpoint"));
        assertEquals("qwen-plus", second.get("model"));
        assertEquals(true, second.get("apiKeyConfigured"), "切换厂商不应清空已保存密钥");

        // 运行期立即生效
        assertEquals("qwen", props.getRuntimeVendor());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", props.getRuntimeEndpoint());
    }

    @Test
    @Order(3)
    @DisplayName("自定义网关：地址与模型可覆盖，密钥以密文落库且接口只返回掩码")
    void customEndpointEncryptedAtRest() {
        String plainKey = "wbk_IntegrationTestKey_0123456789";
        Map<String, Object> out = service.updateSettings(Map.of(
                "vendor", "custom",
                "endpoint", "http://10.50.79.225:7864/v1",
                "model", "cn:deepseek-v4.1-flash",
                "apiKey", plainKey,
                "skipTest", true));

        assertEquals("http://10.50.79.225:7864/v1", out.get("endpoint"));
        assertEquals("cn:deepseek-v4.1-flash", out.get("model"));
        assertEquals("custom", out.get("vendor"));
        assertEquals(true, out.get("requiresCustomEndpoint"));

        String stored = configValue(KEY_API_KEY);
        assertNotNull(stored);
        assertTrue(stored.startsWith("enc:v1:"), "密钥必须以 enc:v1: 密文格式存储");
        assertNotEquals(plainKey, stored);
        assertFalse(stored.contains(plainKey));
        assertEquals(plainKey, cipher.decrypt(stored));

        Map<String, Object> s = service.settings();
        assertEquals("db", s.get("apiKeySource"));
        assertEquals(AiKeyCipher.mask(plainKey), s.get("apiKeyMasked"));
        assertFalse(String.valueOf(s.get("apiKeyMasked")).contains("IntegrationTestKey"));
    }

    @Test
    @Order(4)
    @DisplayName("异常场景：非法厂商 / 非法地址 / 非法密钥均被拒绝，且原配置不被破坏")
    void invalidConfigRejectedWithoutBreakingState() {
        String endpointBefore = configValue(KEY_ENDPOINT);
        String vendorBefore = configValue(KEY_VENDOR);

        assertThrows(BizException.class, () -> service.updateSettings(Map.of(
                "vendor", "not-exists", "skipTest", true)));
        assertThrows(BizException.class, () -> service.updateSettings(Map.of(
                "vendor", "custom", "endpoint", "api.deepseek.com", "skipTest", true)));
        assertThrows(BizException.class, () -> service.updateSettings(Map.of(
                "vendor", "deepseek", "endpoint", "https://api.deepseek.com/v1", "skipTest", true,
                "apiKey", "bad key with space")));

        assertEquals(endpointBefore, configValue(KEY_ENDPOINT));
        assertEquals(vendorBefore, configValue(KEY_VENDOR));
    }

    @Test
    @Order(5)
    @DisplayName("连通性测试：不可达地址返回 ok=false 而不是抛异常")
    void unreachableEndpointReportsFailure() {
        Map<String, Object> r = service.testConnection(Map.of(
                "vendor", "custom",
                "endpoint", "http://127.0.0.1:9/v1",
                "apiKey", "sk-connectivity-test-0001"));
        assertEquals(false, r.get("ok"));
        assertNotNull(r.get("message"));
        assertEquals("http://127.0.0.1:9/v1", r.get("endpoint"));
    }

    @Test
    @Order(6)
    @DisplayName("历史数据兼容：只有 ai.model 时按地址反查厂商并自动补写 ai.vendor")
    void legacyConfigMigratesVendor() {
        deleteAiConfig();
        // 模拟全新启动：清空运行期覆盖，使 init() 走「按地址反查厂商」的迁移分支
        props.setRuntimeVendor(null);
        props.setRuntimeEndpoint(null);
        props.setRuntimeApiKey(null);
        props.setRuntimeModel(null);
        jdbc.update("INSERT INTO sys_config (config_key, config_value, updated_at) VALUES (?, ?, NOW())",
                KEY_ENDPOINT, "https://api.moonshot.cn/v1");
        jdbc.update("INSERT INTO sys_config (config_key, config_value, updated_at) VALUES (?, ?, NOW())",
                "ai.model", "moonshot-v1-8k");

        service.init();

        assertEquals("moonshot", props.getRuntimeVendor());
        assertEquals("moonshot", configValue(KEY_VENDOR), "启动时应自动补写 ai.vendor");
        assertEquals("moonshot-v1-8k", service.settings().get("model"));
    }

    @Test
    @Order(7)
    @DisplayName("旧接口兼容：只更新模型时厂商 / 地址 / 密钥保持原值")
    void legacyModelUpdateKeepsVendorConfig() {
        String endpointBefore = configValue(KEY_ENDPOINT);

        Map<String, Object> out = service.updateModel("cn:legacy-model");
        assertEquals("cn:legacy-model", out.get("model"));
        assertEquals(endpointBefore, configValue(KEY_ENDPOINT));
        assertEquals("moonshot", configValue(KEY_VENDOR));
    }
}
