package cn.course.airesume.resume;

import cn.course.airesume.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeService resumeService;
    private final ResumeImportService resumeImportService;

    public ResumeController(ResumeService resumeService, ResumeImportService resumeImportService) {
        this.resumeService = resumeService;
        this.resumeImportService = resumeImportService;
    }

    /** 简历文件导入（FR-RESUME-04）：解析为草稿，不直接入库。 */
    @PostMapping("/import")
    public ApiResponse<?> importFile(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(resumeImportService.parse(file));
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(value = "q", required = false) String q,
                               @RequestParam(value = "page", defaultValue = "0") int page,
                               @RequestParam(value = "size", defaultValue = "0") int size) {
        return ApiResponse.ok(resumeService.list(q, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable long id) {
        return ApiResponse.ok(resumeService.get(id));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(resumeService.save(payload));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return ApiResponse.ok(resumeService.save(payload));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<?> versions(@PathVariable long id) {
        return ApiResponse.ok(resumeService.listVersions(id));
    }

    @GetMapping("/{id}/versions/compare")
    public ApiResponse<?> compare(@PathVariable long id,
                                  @RequestParam int a, @RequestParam int b) {
        return ApiResponse.ok(resumeService.compare(id, a, b));
    }
}
