package cn.course.airesume.analysis;

import cn.course.airesume.job.JobDao;
import cn.course.airesume.resume.ResumeTextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 分析任务异步执行器（独立 Bean，保证 @Async 经代理生效）。
 * AI 模式 30 秒超时判失败（BR-ANALYSIS-03）；规则模式本地计算（BR-ANALYSIS-04）；
 * demoFail 用于课堂演示失败与重试路径。
 */
@Component
public class AnalysisRunner {
    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final AnalysisDao analysisDao;
    private final ResumeTextService resumeTextService;
    private final JobDao jobDao;
    private final RuleEngine ruleEngine;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;

    public AnalysisRunner(AnalysisDao analysisDao, ResumeTextService resumeTextService, JobDao jobDao,
                          RuleEngine ruleEngine, AiClient aiClient, ObjectMapper objectMapper) {
        this.analysisDao = analysisDao;
        this.resumeTextService = resumeTextService;
        this.jobDao = jobDao;
        this.ruleEngine = ruleEngine;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
    }

    @Async
    public void run(String taskId) {
        long start = System.currentTimeMillis();
        AnalysisDao.TaskRow task = analysisDao.findById(taskId).orElse(null);
        if (task == null || !"RUNNING".equals(task.status())) return;
        try {
            if (task.demoFail()) {
                Thread.sleep(2000);
                analysisDao.markFailed(taskId, elapsed(start),
                        "AI 服务响应超时（演示模拟），本次分析未生成结果。");
                return;
            }
            String resumeText = resumeTextService.build(task.resumeId());
            JobDao.JobRow job = jobDao.findById(task.jobId())
                    .orElseThrow(() -> new IllegalStateException("岗位不存在"));
            List<String> keywords = splitKeywords(job.keywords());
            if (keywords.isEmpty()) keywords = RuleEngine.extractKeywords(job.jd());

            if ("RULE".equals(task.mode())) {
                Thread.sleep(1200); // 让前端有“分析中”的可感知过程
                RuleEngine.RuleResult r = ruleEngine.compute(resumeText, keywords);
                analysisDao.markSuccess(taskId, r.score(), elapsed(start),
                        String.join(",", r.missing()), objectMapper.writeValueAsString(r.suggestions()));
                return;
            }

            AiClient.AiResult r = aiClient.analyze(resumeText, job.jd(), keywords);
            analysisDao.markSuccess(taskId, r.score(), elapsed(start),
                    String.join(",", r.missing()), objectMapper.writeValueAsString(r.suggestions()));
        } catch (AiClient.AiException e) {
            log.warn("任务 {} AI 分析失败：{}", taskId, e.getMessage());
            if (e.isUnavailable() && "AI".equals(task.mode())) {
                // AI 未启动/无法连接：按 BR-ANALYSIS-04 降级规则模式，不中断流程
                fallbackToRule(task, start, e.getMessage());
            } else {
                // 超时、HTTP 错误、返回格式异常：按 BR-ANALYSIS-03/05 置为失败，可重试
                analysisDao.markFailed(taskId, elapsed(start), e.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            analysisDao.markFailed(taskId, elapsed(start), "分析任务被中断，请重试。");
        } catch (Exception e) {
            log.error("任务 {} 执行异常", taskId, e);
            analysisDao.markFailed(taskId, elapsed(start), "系统异常导致分析失败，请重试。");
        }
    }

    /** AI 服务不可达时的规则降级：结果可用，但明确标注降级原因与分析方式。 */
    private void fallbackToRule(AnalysisDao.TaskRow task, long start, String cause) {
        try {
            String resumeText = resumeTextService.build(task.resumeId());
            JobDao.JobRow job = jobDao.findById(task.jobId()).orElse(null);
            List<String> keywords = job == null ? new ArrayList<>() : splitKeywords(job.keywords());
            if (keywords.isEmpty() && job != null) keywords = RuleEngine.extractKeywords(job.jd());
            RuleEngine.RuleResult r = ruleEngine.compute(resumeText, keywords);
            analysisDao.markRuleFallback(task.id(), r.score(), elapsed(start),
                    String.join(",", r.missing()), objectMapper.writeValueAsString(r.suggestions()),
                    "AI 服务不可用，已自动降级为规则模式（关键词覆盖率）。原因：" + cause);
        } catch (Exception e) {
            log.error("任务 {} 规则降级失败", task.id(), e);
            analysisDao.markFailed(task.id(), elapsed(start), "AI 服务不可用且规则降级失败，请重试。");
        }
    }

    private static List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) return new ArrayList<>();
        return Arrays.stream(keywords.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static double elapsed(long start) {
        return Math.round((System.currentTimeMillis() - start) / 100.0) / 10.0;
    }
}
