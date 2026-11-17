package cn.course.airesume.customapi;

import cn.course.airesume.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端自定义 API 配置与调用接口（v2.0）。
 *
 * <p>返回体沿用全局约定 {@code {code:0,data}} / {@code {code:1,message}}；
 * 调用类接口即使目标返回 4xx/5xx 也按 {@code code:0} 返回，由 data.ok / data.status 表达结果。
 */
@RestController
@RequestMapping("/api/user-apis")
public class UserApiController {
    private final UserApiConfigService service;

    public UserApiController(UserApiConfigService service) {
        this.service = service;
    }

    /** 列表：支持关键字、分组、标签过滤，同时返回可选分组与标签。 */
    @GetMapping
    public ApiResponse<?> list(@RequestParam(value = "q", required = false) String q,
                               @RequestParam(value = "group", required = false) String group,
                               @RequestParam(value = "tag", required = false) String tag) {
        return ApiResponse.ok(service.list(q, group, tag));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Map<String, Object> payload) {
        payload.remove("id");
        return ApiResponse.ok(service.save(payload));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return ApiResponse.ok(service.save(payload));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> remove(@PathVariable long id) {
        service.remove(id);
        return ApiResponse.ok(Map.of("id", String.valueOf(id)));
    }

    /** 复制配置项，名称自动追加「副本」并按需编号。 */
    @PostMapping("/{id}/duplicate")
    public ApiResponse<?> duplicate(@PathVariable long id) {
        return ApiResponse.ok(service.duplicate(id));
    }

    /** 测试当前表单内容（不保存）。 */
    @PostMapping("/test")
    public ApiResponse<?> test(@RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(service.test(payload));
    }

    /** 调用已保存配置；body 可传 params / headers / body 覆盖项。 */
    @PostMapping("/{id}/invoke")
    public ApiResponse<?> invoke(@PathVariable long id,
                                 @RequestBody(required = false) Map<String, Object> overrides) {
        return ApiResponse.ok(service.invoke(id, overrides));
    }
}
