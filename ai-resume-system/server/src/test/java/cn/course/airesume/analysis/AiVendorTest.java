package cn.course.airesume.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 厂商注册表单元测试（v1.9）：覆盖预置厂商清单、按 id 查找、按地址反查与密钥格式匹配。
 */
class AiVendorTest {

    @Test
    @DisplayName("预置厂商包含需求要求的主流国内外厂商")
    void containsRequiredVendors() {
        Set<String> ids = new HashSet<>();
        for (AiVendor v : AiVendor.all()) ids.add(v.getId());
        assertTrue(ids.containsAll(Set.of("openai", "anthropic", "gemini", "azure-openai",
                "deepseek", "qwen", "zhipu", "moonshot", "doubao", "baidu", "custom")));
        assertEquals(11, AiVendor.all().size());
    }

    @Test
    @DisplayName("每个预置厂商都有名称、密钥正则与说明")
    void everyVendorIsWellFormed() {
        for (AiVendor v : AiVendor.all()) {
            assertNotNull(v.getId());
            assertFalse(v.getId().isBlank());
            assertFalse(v.getName().isBlank(), v.getId() + " 缺少名称");
            assertFalse(v.getKeyPattern().isBlank(), v.getId() + " 缺少密钥正则");
            assertNotNull(v.getKeyUrl(), v.getId() + " keyUrl 不应为 null");
        }
    }

    @Test
    @DisplayName("除 Azure 与自定义网关外，预置厂商都有默认接口地址与默认模型")
    void defaultEndpointAndModel() {
        for (AiVendor v : AiVendor.all()) {
            if (v.isRequiresCustomEndpoint()) {
                assertTrue(v.getDefaultEndpoint().isEmpty(), v.getId() + " 不应有默认地址");
            } else {
                assertTrue(v.getDefaultEndpoint().startsWith("https://"), v.getId() + " 默认地址需为 https");
                assertFalse(v.getDefaultModel().isBlank(), v.getId() + " 缺少默认模型");
            }
        }
    }

    @Test
    @DisplayName("按 id 查找忽略大小写与首尾空格，未知返回 null")
    void findById() {
        assertEquals("openai", AiVendor.find(" Openai ").getId());
        assertEquals("azure-openai", AiVendor.find("AZURE-OPENAI").getId());
        assertNull(AiVendor.find("not-a-vendor"));
        assertNull(AiVendor.find(null));
    }

    @Test
    @DisplayName("按接口地址反查厂商，兼容尾斜杠与大小写，未知落到 custom")
    void detectByEndpoint() {
        assertEquals("deepseek", AiVendor.detectByEndpoint("https://api.deepseek.com/v1"));
        assertEquals("deepseek", AiVendor.detectByEndpoint("https://API.DeepSeek.com/v1/"));
        assertEquals("qwen", AiVendor.detectByEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals("custom", AiVendor.detectByEndpoint("http://10.50.79.225:7864/v1"));
        assertEquals("custom", AiVendor.detectByEndpoint(""));
        assertEquals("custom", AiVendor.detectByEndpoint(null));
    }

    @Test
    @DisplayName("密钥格式匹配：OpenAI 系 / Anthropic / 智谱两段式")
    void matchesKey() {
        AiVendor openai = AiVendor.find("openai");
        assertTrue(openai.matchesKey("sk-abcdefghijklmnop1234"));
        assertFalse(openai.matchesKey("wbk_TestOnlyFakeKey0000000000000000000000000"));
        assertFalse(openai.matchesKey("sk-short"));
        assertFalse(openai.matchesKey("sk-abcdefghijklmnop 1234"));
        assertFalse(openai.matchesKey(null));

        assertTrue(AiVendor.find("anthropic").matchesKey("sk-ant-abcdefghijklmnop1234"));
        assertFalse(AiVendor.find("anthropic").matchesKey("sk-abcdefghijklmnop1234"));

        AiVendor zhipu = AiVendor.find("zhipu");
        assertTrue(zhipu.matchesKey("0123456789abcdef0123456789abcdef.secret1234"));
        assertFalse(zhipu.matchesKey("plain-key-12345678"));
    }

    @Test
    @DisplayName("管理端清单不包含任何密钥字段")
    void listNeverExposesKey() {
        List<Map<String, Object>> list = AiVendor.list();
        assertEquals(11, list.size());
        for (Map<String, Object> m : list) {
            assertFalse(m.containsKey("apiKey"));
            assertFalse(m.containsKey("apiKeyMasked"));
            assertTrue(m.containsKey("keyPattern"));
            assertTrue(m.containsKey("keyUrl"));
            assertTrue(m.containsKey("requiresCustomEndpoint"));
        }
    }
}
