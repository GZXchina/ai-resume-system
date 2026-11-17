package cn.course.airesume.analysis;

import cn.course.airesume.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analyses")
public class AnalysisController {
    private final AnalysisService analysisService;
    private final InterviewService interviewService;

    public AnalysisController(AnalysisService analysisService, InterviewService interviewService) {
        this.analysisService = analysisService;
        this.interviewService = interviewService;
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(analysisService.create(body));
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(value = "status", required = false) String status,
                               @RequestParam(value = "q", required = false) String q) {
        return ApiResponse.ok(analysisService.list(status, q));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable String id) {
        return ApiResponse.ok(analysisService.get(id));
    }

    @PostMapping("/{id}/feedback")
    public ApiResponse<?> feedback(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(analysisService.feedback(id, body));
    }

    @PostMapping("/{id}/versions")
    public ApiResponse<?> createVersion(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(analysisService.createVersion(id, body));
    }

    /** 模拟面试题生成（FR-INTERVIEW-01），仅本人成功的分析任务可用。 */
    @PostMapping("/{id}/interview")
    public ApiResponse<?> interview(@PathVariable String id) {
        return ApiResponse.ok(interviewService.generate(id));
    }
}
