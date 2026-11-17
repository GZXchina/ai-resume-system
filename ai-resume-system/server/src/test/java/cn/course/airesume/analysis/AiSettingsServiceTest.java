package cn.course.airesume.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.course.airesume.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 厂商配置服务单元测试（v1.9）：校验规则、保存行为、密钥沿用 / 清除、旧接口兼容。
 * 使用 Mock 的 JdbcTemplate 与真实加密组件，不依赖数据库与外部网络（skipTest=true）。
 */
class AiSettingsServiceTest {

    private JdbcTemplate jdbc;
    private AiProperties props;
    private AiSettingsService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        props = new AiProperties();
        props.setEnabled(true);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 3 + 5);
        AiKeyCipher cipher = new AiKeyCipher(Base64.getEncoder().encodeToString(key));
        service = new AiSettingsService(jdbc, props, new ObjectMapper(), cipher);
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("未知厂商被拒绝")
    void unknownVendorRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.updateSettings(body("vendor", "unknown-ai", "skipTest", true)));
        assertTrue(e.getMessage().contains("未知的 AI 厂商"));
    }

    @Test
    @DisplayName("没有默认地址的厂商未填地址时被拒绝")
    void missingEndpointRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.updateSettings(body("vendor", "custom", "skipTest", true)));
        assertTrue(e.getMessage().contains("请填写接口地址"));
    }

    @Test
    @DisplayName("接口地址协议与格式实时校验")
    void invalidEndpointRejected() {
        BizException ftp = assertThrows(BizException.class, () -> service.updateSettings(body(
                "vendor", "deepseek", "endpoint", "ftp://api.deepseek.com/v1", "skipTest", true)));
        assertTrue(ftp.getMessage().contains("http"));

        BizException space = assertThrows(BizException.class, () -> service.updateSettings(body(
                "vendor", "deepseek", "endpoint", "https://api.deepseek.com/v 1", "skipTest", true)));
        assertTrue(space.getMessage().contains("空格"));

        BizException noHost = assertThrows(BizException.class, () -> service.updateSettings(body(
                "vendor", "deepseek", "endpoint", "https:///v1", "skipTest", true)));
        assertTrue(noHost.getMessage().contains("主机名"));
    }

    @Test
    @DisplayName("API-KEY 格式不符时拒绝保存")
    void invalidApiKeyRejected() {
        BizException e = assertThrows(BizException.class, () -> service.updateSettings(body(
                "vendor", "openai", "apiKey", "sk-short", "skipTest", true)));
        assertTrue(e.getMessage().contains("API-KEY 格式不符"));
        verify(jdbc, never()).update(contains("INSERT INTO sys_config"), any(Object[].class));
    }

    @Test
    @DisplayName("保存后运行期配置立即生效，密钥加密落库且接口只返回掩码")
    void saveAppliesImmediately() {
        Map<String, Object> out = service.updateSettings(body(
                "vendor", "deepseek",
                "apiKey", "sk-test-key-1234567890",
                "skipTest", true));

        assertEquals("deepseek", props.getRuntimeVendor());
        assertEquals("https://api.deepseek.com/v1", props.getRuntimeEndpoint());
        assertEquals("deepseek-chat", props.getRuntimeModel());
        assertEquals("sk-test-key-1234567890", props.getRuntimeApiKey());

        assertEquals("deepseek", out.get("vendor"));
        assertEquals("https://api.deepseek.com/v1", out.get("endpoint"));
        assertEquals("deepseek-chat", out.get("model"));
        assertEquals(true, out.get("apiKeyConfigured"));
        assertEquals("sk-****7890", out.get("apiKeyMasked"));
        assertEquals("db", out.get("apiKeySource"));
        assertFalse(String.valueOf(out.get("apiKeyMasked")).contains("test-key"));

        // 厂商 / 地址 / 模型 / 密钥共 4 次写入
        verify(jdbc, times(4)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("API-KEY 留空时沿用已配置密钥，不覆盖库中密文")
    void blankKeyKeepsExisting() {
        props.setApiKey("sk-config-key-1234567890");

        service.updateSettings(body("vendor", "qwen", "model", "qwen-plus", "skipTest", true));

        assertNull(props.getRuntimeApiKey());
        assertEquals("sk-config-key-1234567890", props.resolveApiKey());
        // 只写厂商 / 地址 / 模型
        verify(jdbc, times(3)).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("清除密钥后回落到配置文件 / 环境变量密钥")
    void clearKeyFallsBackToConfig() {
        props.setApiKey("sk-env-key-1234567890");
        props.setRuntimeApiKey("sk-runtime-key-0987654321");

        Map<String, Object> out = service.updateSettings(body(
                "vendor", "moonshot", "clearApiKey", true, "skipTest", true));

        verify(jdbc).update(contains("DELETE FROM sys_config"), any(Object[].class));
        assertNull(props.getRuntimeApiKey());
        assertEquals("sk-env-key-1234567890", props.resolveApiKey());
        assertEquals("config", out.get("apiKeySource"));
        assertEquals("sk-****7890", out.get("apiKeyMasked"));
    }

    @Test
    @DisplayName("自定义网关：支持任意自定义地址与模型覆盖")
    void customVendorOverride() {
        Map<String, Object> out = service.updateSettings(body(
                "vendor", "custom",
                "endpoint", "http://ai-gateway.example.com/v1",
                "model", "cn:deepseek-v4.1-flash",
                "apiKey", "wbk_TestOnlyFakeKey0000000000000000000000000",
                "skipTest", true));

        assertEquals("custom", out.get("vendor"));
        assertEquals("http://ai-gateway.example.com/v1", out.get("endpoint"));
        assertEquals("cn:deepseek-v4.1-flash", out.get("model"));
        assertEquals(true, out.get("requiresCustomEndpoint"));
        assertEquals("wbk****GZGs".length(), String.valueOf(out.get("apiKeyMasked")).length());
    }

    @Test
    @DisplayName("模型 ID 格式校验（新接口与旧接口一致）")
    void modelFormatValidated() {
        assertThrows(BizException.class, () -> service.updateSettings(body(
                "vendor", "deepseek", "model", "bad model!", "skipTest", true)));
        assertThrows(BizException.class, () -> service.updateModel("bad model!"));
    }

    @Test
    @DisplayName("旧接口 updateModel 仍可用：只改模型，不动厂商 / 地址 / 密钥")
    void legacyUpdateModelStillWorks() {
        props.setRuntimeVendor("custom");
        props.setRuntimeEndpoint("http://ai-gateway.example.com/v1");

        Map<String, Object> out = service.updateModel("cn:deepseek-v4.1-flash");
        assertEquals("cn:deepseek-v4.1-flash", out.get("model"));
        assertEquals("custom", props.getRuntimeVendor());
        assertEquals("http://ai-gateway.example.com/v1", props.getRuntimeEndpoint());
        verify(jdbc).update(contains("INSERT INTO sys_config"), any(Object[].class));

        service.updateModel("");
        assertNull(props.getRuntimeModel());
        verify(jdbc).update(contains("DELETE FROM sys_config"), any(Object[].class));
    }

    @Test
    @DisplayName("连通性测试：未填地址的厂商直接提示，发送非法密钥先做格式校验")
    void testConnectionValidation() {
        BizException noEndpoint = assertThrows(BizException.class,
                () -> service.testConnection(body("vendor", "azure-openai")));
        assertTrue(noEndpoint.getMessage().contains("请先填写接口地址"));

        BizException badKey = assertThrows(BizException.class,
                () -> service.testConnection(body("vendor", "openai", "apiKey", "bad key")));
        assertTrue(badKey.getMessage().contains("API-KEY"));

        BizException unknown = assertThrows(BizException.class,
                () -> service.testConnection(body("vendor", "nope")));
        assertTrue(unknown.getMessage().contains("未知的 AI 厂商"));
    }

    @Test
    @DisplayName("settings 在未配置时回落配置文件且不泄露密钥")
    void settingsFallbackWithoutSecret() {
        props.setEndpoint("https://api.openai.com/v1");
        props.setApiKey("sk-config-key-1234567890");
        props.setModel("gpt-4o-mini");

        Map<String, Object> s = service.settings();
        assertEquals("openai", s.get("vendor"));
        assertEquals("OpenAI", s.get("vendorName"));
        assertEquals("config", s.get("apiKeySource"));
        assertEquals("sk-****7890", s.get("apiKeyMasked"));
        assertFalse(s.containsKey("apiKey"));
        assertEquals(false, s.containsKey("apiKeyMasked") && String.valueOf(s.get("apiKeyMasked")).contains("config-key"));
    }
}
