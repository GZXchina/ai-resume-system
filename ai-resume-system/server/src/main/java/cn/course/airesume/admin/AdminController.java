package cn.course.airesume.admin;

import cn.course.airesume.analysis.AiSettingsService;
import cn.course.airesume.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;
    private final AiSettingsService aiSettingsService;

    public AdminController(AdminService adminService, AiSettingsService aiSettingsService) {
        this.adminService = adminService;
        this.aiSettingsService = aiSettingsService;
    }

    @GetMapping("/users")
    public ApiResponse<?> users(@RequestParam(value = "q", required = false) String q) {
        return ApiResponse.ok(adminService.users(q));
    }

    @PatchMapping("/users/{id}/status")
    public ApiResponse<?> toggleUser(@PathVariable long id) {
        return ApiResponse.ok(adminService.toggleUser(id));
    }

    @GetMapping("/analyses")
    public ApiResponse<?> tasks(@RequestParam(value = "status", required = false) String status,
                                @RequestParam(value = "q", required = false) String q) {
        return ApiResponse.ok(adminService.tasks(status, q));
    }

    @GetMapping("/stats")
    public ApiResponse<?> stats() {
        return ApiResponse.ok(adminService.stats());
    }

    /** 质量与反馈统计（FR-STAT-02）：反馈有用率、方式分布、降级次数、Top 缺失关键词。 */
    @GetMapping("/quality")
    public ApiResponse<?> quality() {
        return ApiResponse.ok(adminService.quality());
    }

    /** AI 模型清单（FR-ADMIN-03）：服务端代理网关 /models，密钥不出后端。 */
    @GetMapping("/ai/models")
    public ApiResponse<?> aiModels(@RequestParam(value = "refresh", defaultValue = "false") boolean refresh) {
        return ApiResponse.ok(aiSettingsService.models(refresh));
    }

    /** 当前分析模型设置。 */
    @GetMapping("/ai/settings")
    public ApiResponse<?> aiSettings() {
        return ApiResponse.ok(aiSettingsService.settings());
    }

    /** 厂商清单（v1.9）：默认地址、默认模型、密钥格式与获取指引，供前端下拉与实时校验使用。 */
    @GetMapping("/ai/vendors")
    public ApiResponse<?> aiVendors() {
        return ApiResponse.ok(aiSettingsService.vendors());
    }

    /** 连通性测试（v1.9）：不落库，仅用提交的厂商/地址/密钥探测是否可用。 */
    @PostMapping("/ai/test")
    public ApiResponse<?> testAiSettings(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(aiSettingsService.testConnection(body));
    }

    /**
     * 保存 AI 配置。
     * 兼容两种请求：仅含 model 时沿用 v1.7 的「切换模型」语义；
     * 含 vendor/endpoint/apiKey/clearApiKey 任一字段时按 v1.9 厂商配置处理（校验 + 连通性测试 + 加密落库）。
     */
    @PutMapping("/ai/settings")
    public ApiResponse<?> saveAiSettings(@RequestBody Map<String, Object> body) {
        boolean vendorMode = body.containsKey("vendor") || body.containsKey("endpoint")
                || body.containsKey("apiKey") || body.containsKey("clearApiKey");
        if (vendorMode) return ApiResponse.ok(aiSettingsService.updateSettings(body));
        Object v = body.get("model");
        return ApiResponse.ok(aiSettingsService.updateModel(v == null ? "" : String.valueOf(v)));
    }
}
