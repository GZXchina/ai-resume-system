package cn.course.airesume.job;

import cn.course.airesume.common.ApiResponse;
import cn.course.airesume.common.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobService;
    private final JobMatchService jobMatchService;

    public JobController(JobService jobService, JobMatchService jobMatchService) {
        this.jobService = jobService;
        this.jobMatchService = jobMatchService;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(value = "q", required = false) String q) {
        return ApiResponse.ok(jobService.list(q));
    }

    /** 岗位匹配度排序（FR-JOB-02）：按本人简历与各岗位 JD 关键词覆盖率倒序。 */
    @GetMapping("/match")
    public ApiResponse<?> match(@RequestParam long resumeId,
                                @RequestParam(value = "q", required = false) String q) {
        return ApiResponse.ok(jobMatchService.match(resumeId, q));
    }

    /** 多简历对比（FR-JOB-03）：同一岗位下 2~3 份本人简历横向对比。 */
    @GetMapping("/{id}/resumes/compare")
    public ApiResponse<?> compareResumes(@PathVariable long id,
                                         @RequestParam(required = false) String resumeIds) {
        if (resumeIds == null || resumeIds.isBlank()) throw new BizException("请选择 2~3 份简历进行对比");
        List<Long> ids = new ArrayList<>();
        for (String part : resumeIds.split(",")) {
            if (part.isBlank()) continue;
            try {
                ids.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                throw new BizException("简历参数格式不正确");
            }
        }
        return ApiResponse.ok(jobMatchService.compare(id, ids));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable long id) {
        return ApiResponse.ok(jobService.get(id));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(jobService.save(payload));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable long id, @RequestBody Map<String, Object> payload) {
        payload.put("id", id);
        return ApiResponse.ok(jobService.save(payload));
    }
}
