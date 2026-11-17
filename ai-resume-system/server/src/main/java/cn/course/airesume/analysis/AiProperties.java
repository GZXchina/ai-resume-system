package cn.course.airesume.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private boolean enabled;
    private String endpoint;
    private String apiKey;
    private String model;
    private int timeoutSeconds = 30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * 配置完整可用：启用 + 地址 + 密钥齐全；缺一不可，否则调用必然失败，应直接走规则模式。
     * v1.9 起改用运行期解析结果，管理端保存后立即生效。
     */
    public boolean isUsable() {
        String ep = resolveEndpoint();
        String key = resolveApiKey();
        return enabled
                && ep != null && !ep.isBlank()
                && key != null && !key.isBlank();
    }
    /** 配置文件中的初始地址（不含管理端覆盖）。 */
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    /** 配置文件 / 环境变量中的初始密钥（不含管理端覆盖）。 */
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    /* ---------- 管理端运行期覆盖（v1.7 模型 / v1.9 厂商·地址·密钥） ---------- */

    /** 管理端运行期切换的模型；为空时回落到配置文件默认值。 */
    private volatile String runtimeModel;
    /** 管理端保存的厂商 id；为空时回落到配置文件推断。 */
    private volatile String runtimeVendor;
    /** 管理端保存的接口地址；为空时回落到配置文件。 */
    private volatile String runtimeEndpoint;
    /** 管理端保存并解密后的密钥（明文仅存在内存）；为空时回落到配置文件。 */
    private volatile String runtimeApiKey;

    /** 实际用于调用的模型：运行期设置优先，其次配置文件。 */
    public String resolveModel() {
        String m = runtimeModel;
        return (m == null || m.isBlank()) ? model : m;
    }

    /** 实际用于调用的接口地址：运行期设置优先，其次配置文件。 */
    public String resolveEndpoint() {
        String e = runtimeEndpoint;
        if (e != null && !e.isBlank()) return e;
        return endpoint;
    }

    /** 实际用于调用的密钥：运行期设置优先，其次配置文件 / 环境变量。 */
    public String resolveApiKey() {
        String k = runtimeApiKey;
        if (k != null && !k.isBlank()) return k;
        return apiKey;
    }

    /** 当前厂商 id；未显式设置时按接口地址反查，历史配置默认落到 custom。 */
    public String resolveVendor() {
        String v = runtimeVendor;
        if (v != null && !v.isBlank()) return v;
        return AiVendor.detectByEndpoint(resolveEndpoint());
    }

    public String getRuntimeModel() { return runtimeModel; }
    public void setRuntimeModel(String runtimeModel) { this.runtimeModel = runtimeModel; }
    public String getRuntimeVendor() { return runtimeVendor; }
    public void setRuntimeVendor(String runtimeVendor) { this.runtimeVendor = runtimeVendor; }
    public String getRuntimeEndpoint() { return runtimeEndpoint; }
    public void setRuntimeEndpoint(String runtimeEndpoint) { this.runtimeEndpoint = runtimeEndpoint; }
    public String getRuntimeApiKey() { return runtimeApiKey; }
    public void setRuntimeApiKey(String runtimeApiKey) { this.runtimeApiKey = runtimeApiKey; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    /* ---------- 扫描件 OCR（v1.7）：复用同一网关的多模态模型 ---------- */
    private boolean ocrEnabled = true;
    private String ocrModel = "global:deepseek-v4.1-flash";
    private int ocrMaxPages = 3;
    private int ocrDpi = 150;

    public boolean isOcrEnabled() { return ocrEnabled; }
    public void setOcrEnabled(boolean ocrEnabled) { this.ocrEnabled = ocrEnabled; }
    public String getOcrModel() { return ocrModel; }
    public void setOcrModel(String ocrModel) { this.ocrModel = ocrModel; }
    public int getOcrMaxPages() { return ocrMaxPages; }
    public void setOcrMaxPages(int ocrMaxPages) { this.ocrMaxPages = ocrMaxPages; }
    public int getOcrDpi() { return ocrDpi; }
    public void setOcrDpi(int ocrDpi) { this.ocrDpi = ocrDpi; }

    /** OCR 可用：开关打开 + 网关配置完整。 */
    public boolean isOcrUsable() {
        return ocrEnabled && isUsable();
    }
}
