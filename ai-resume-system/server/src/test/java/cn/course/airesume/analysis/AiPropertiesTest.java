package cn.course.airesume.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 配置解析单元测试（v1.9）：运行期覆盖优先、回落配置文件、可用性判断与厂商推断。
 */
class AiPropertiesTest {

    @Test
    @DisplayName("未设置运行期覆盖时回落配置文件")
    void fallsBackToConfiguredValues() {
        AiProperties p = new AiProperties();
        p.setEnabled(true);
        p.setEndpoint("https://api.deepseek.com/v1");
        p.setApiKey("sk-config-key-123456");
        p.setModel("deepseek-chat");

        assertEquals("https://api.deepseek.com/v1", p.resolveEndpoint());
        assertEquals("sk-config-key-123456", p.resolveApiKey());
        assertEquals("deepseek-chat", p.resolveModel());
        assertEquals("deepseek", p.resolveVendor());
        assertTrue(p.isUsable());
    }

    @Test
    @DisplayName("运行期覆盖优先于配置文件（管理端保存后即时生效）")
    void runtimeOverrideWins() {
        AiProperties p = new AiProperties();
        p.setEnabled(true);
        p.setEndpoint("https://api.deepseek.com/v1");
        p.setApiKey("sk-config-key-123456");
        p.setModel("deepseek-chat");

        p.setRuntimeVendor("custom");
        p.setRuntimeEndpoint("http://10.50.79.225:7864/v1");
        p.setRuntimeApiKey("wbk-runtime-key");
        p.setRuntimeModel("cn:deepseek-v4.1-flash");

        assertEquals("custom", p.resolveVendor());
        assertEquals("http://10.50.79.225:7864/v1", p.resolveEndpoint());
        assertEquals("wbk-runtime-key", p.resolveApiKey());
        assertEquals("cn:deepseek-v4.1-flash", p.resolveModel());
    }

    @Test
    @DisplayName("空白运行期值视为未设置，继续回落配置文件")
    void blankRuntimeValueFallsBack() {
        AiProperties p = new AiProperties();
        p.setEndpoint("https://api.moonshot.cn/v1");
        p.setApiKey("sk-config-key-123456");
        p.setModel("moonshot-v1-8k");
        p.setRuntimeEndpoint("  ");
        p.setRuntimeVendor("");
        p.setRuntimeModel(null);

        assertEquals("https://api.moonshot.cn/v1", p.resolveEndpoint());
        assertEquals("moonshot", p.resolveVendor());
        assertEquals("moonshot-v1-8k", p.resolveModel());
    }

    @Test
    @DisplayName("未启用或缺密钥时不可用，走规则模式")
    void notUsableWithoutKey() {
        AiProperties p = new AiProperties();
        p.setEndpoint("https://api.deepseek.com/v1");
        p.setApiKey("");
        assertFalse(p.isUsable());

        p.setEnabled(true);
        assertFalse(p.isUsable());

        p.setRuntimeApiKey("sk-runtime-key-123456");
        assertTrue(p.isUsable());
    }

    @Test
    @DisplayName("未显式设置厂商时按地址反查，未知地址落到 custom")
    void inferVendorFromEndpoint() {
        AiProperties p = new AiProperties();
        p.setEndpoint("https://api.openai.com/v1");
        assertEquals("openai", p.resolveVendor());

        p.setEndpoint("http://localhost:7864/v1");
        assertEquals("custom", p.resolveVendor());
    }
}
