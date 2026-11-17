package cn.course.airesume.analysis;

import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import cn.course.airesume.job.JobDao;
import cn.course.airesume.job.JobService;
import cn.course.airesume.resume.ResumeDao;
import cn.course.airesume.resume.ResumeService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分析任务业务（FR-ANALYSIS-01~04）。
 * 状态机：RUNNING(分析中) → SUCCESS(成功，终态) / FAILED(失败，可重试生成新任务)。
 */
@Service
public class AnalysisService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final AnalysisDao analysisDao;
    private final ResumeService resumeService;
    private final JobService jobService;
    private final AnalysisRunner runner;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public AnalysisService(AnalysisDao analysisDao, ResumeService resumeService,
                           JobService jobService, AnalysisRunner runner,
                           AiProperties aiProperties, ObjectMapper objectMapper,
                           PlatformTransactionManager txManager) {
        this.analysisDao = analysisDao;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.runner = runner;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /* ---------------- 发起任务 ---------------- */

    public Map<String, Object> create(Map<String, Object> body) {
        LoginUser me = AuthContext.require();
        long resumeId = parseId(body.get("resumeId"), "请选择简历");
        long jobId = parseId(body.get("jobId"), "请选择岗位");

        // 分析方式：演示可强制规则模式；AI 未启用/未配置完整时自动降级规则模式（BR-ANALYSIS-04）
        boolean demoRule = false;
        boolean demoFail = false;
        if (body.get("demo") instanceof Map<?, ?> demo) {
            demoRule = "rule".equals(String.valueOf(demo.get("mode")));
            demoFail = Boolean.parseBoolean(String.valueOf(demo.get("fail")));
        }
        String mode = (demoRule || !aiProperties.isUsable()) ? "RULE" : "AI";
        final String analysisMode = mode;
        final boolean failDemo = demoFail;

        // DR-001：锁定简历行后再检查 RUNNING 并插入，消除并发窗口（BR-ANALYSIS-02）。
        // 事务提交后再触发异步执行，避免异步线程读不到未提交的任务。
        AnalysisDao.TaskRow task = txTemplate.execute(status -> {
            ResumeDao.ResumeRow resume = resumeService.requireOwnForUpdate(resumeId, me);
            JobDao.JobRow job = jobService.requireOwn(jobId, me);
            if (analysisDao.existsRunning(resumeId)) {
                throw new BizException("该简历已有进行中的分析任务，请稍后再试");
            }
            AnalysisDao.TaskRow t = new AnalysisDao.TaskRow(
                    ResumeService.uid("a"), me.id(), resumeId, jobId,
                    resume.name(), job.title() + " · " + (job.company() == null ? "" : job.company()),
                    "RUNNING", analysisMode, null, 0, null, null, null, null, null, null, failDemo, null);
            analysisDao.insert(t);
            return t;
        });
        if (task == null) throw new BizException("创建分析任务失败，请稍后重试");
        runner.run(task.id());
        return toClient(analysisDao.findById(task.id()).orElseThrow());
    }

    /* ---------------- 查询 ---------------- */

    public Map<String, Object> get(String id) {
        LoginUser me = AuthContext.require();
        return toClient(requireOwnTask(id, me));
    }

    public List<Map<String, Object>> list(String status, String q) {
        LoginUser me = AuthContext.require();
        return analysisDao.listByUser(me.id(), dbStatus(status), q).stream()
                .map(AnalysisService::toClient).toList();
    }

    /* ---------------- 反馈（FR-ANALYSIS-04，覆盖式） ---------------- */

    public Map<String, Object> feedback(String id, Map<String, String> body) {
        LoginUser me = AuthContext.require();
        AnalysisDao.TaskRow t = requireOwnTask(id, me);
        if (!"SUCCESS".equals(t.status())) throw new BizException("仅成功的分析可以反馈");
        String type = body.get("type");
        if (!"useful".equals(type) && !"useless".equals(type)) {
            throw new BizException("反馈类型必须为 useful 或 useless");
        }
        String note = body.get("note") == null ? "" : body.get("note").trim();
        if (note.length() > 200) throw new BizException("反馈备注不能超过 200 字");
        // 数据库统一大写（DR-005），对外 API 保持小写
        String dbType = "useful".equals(type) ? "USEFUL" : "USELESS";
        // 每条任务仅保留一条反馈，重复提交覆盖（BR 对应 FR-ANALYSIS-04 异常处理）
        analysisDao.saveFeedback(id, dbType, note);
        AnalysisDao.TaskRow updated = analysisDao.findById(id).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "USEFUL".equals(updated.feedbackType()) ? "useful" : "useless");
        m.put("note", updated.feedbackNote() == null ? "" : updated.feedbackNote());
        m.put("at", updated.feedbackAt());
        return m;
    }

    /* ---------------- 采纳建议生成新版本（FR-VERSION-01） ---------------- */

    public Map<String, Object> createVersion(String taskId, Map<String, Object> body) {
        LoginUser me = AuthContext.require();
        AnalysisDao.TaskRow t = requireOwnTask(taskId, me);
        if (!"SUCCESS".equals(t.status())) throw new BizException("仅成功的分析可以生成新版本");

        String note = body.get("note") == null ? "" : String.valueOf(body.get("note"));
        String summary = body.get("summary") == null ? "" : String.valueOf(body.get("summary"));
        List<String> skills = new ArrayList<>();
        if (body.get("skills") instanceof List<?> list) {
            for (Object o : list) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) skills.add(s);
            }
        }
        List<ResumeDao.Project> projects = new ArrayList<>();
        if (body.get("projects") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    projects.add(new ResumeDao.Project(0,
                            strOf(m.get("title")), strOf(m.get("role")),
                            strOf(m.get("period")), strOf(m.get("desc"))));
                }
            }
        }
        return resumeService.createVersionFromAnalysis(t.resumeId(), taskId, note, summary, skills, projects);
    }

    /* ---------------- 内部工具 ---------------- */

    private AnalysisDao.TaskRow requireOwnTask(String id, LoginUser me) {
        AnalysisDao.TaskRow t = analysisDao.findById(id)
                .orElseThrow(() -> new BizException("分析任务不存在"));
        if (t.userId() != me.id()) throw new BizException("无权访问该分析记录");
        return t;
    }

    private static long parseId(Object v, String emptyMsg) {
        if (v == null || String.valueOf(v).isBlank()) throw new BizException(emptyMsg);
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new BizException("参数格式不正确");
        }
    }

    private static String strOf(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    private static List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) return new ArrayList<>();
        return Arrays.stream(keywords.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static double elapsed(long start) {
        return Math.round((System.currentTimeMillis() - start) / 100.0) / 10.0;
    }

    /** 前端状态值 → 数据库存储值 */
    public static String dbStatus(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status) {
            case "analyzing" -> "RUNNING";
            case "success" -> "SUCCESS";
            case "failed" -> "FAILED";
            default -> status;
        };
    }

    /** 数据库存储值 → 前端展示对象（小写枚举，与原型契约一致）。 */
    public static Map<String, Object> toClient(AnalysisDao.TaskRow t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("resumeId", String.valueOf(t.resumeId()));
        m.put("jobId", String.valueOf(t.jobId()));
        m.put("resumeName", t.resumeName());
        m.put("jobTitle", t.jobTitle());
        m.put("status", switch (t.status()) {
            case "RUNNING" -> "analyzing";
            case "SUCCESS" -> "success";
            case "FAILED" -> "failed";
            default -> t.status().toLowerCase();
        });
        m.put("mode", "RULE".equals(t.mode()) ? "rule" : "ai");
        m.put("score", t.score());
        m.put("duration", t.duration());
        m.put("missing", splitKeywords(t.missingKeywords()));
        m.put("suggestions", parseSuggestions(t.suggestionsJson()));
        m.put("reason", t.reason());
        m.put("createdAt", t.createdAt());
        if (t.feedbackType() != null) {
            Map<String, Object> fb = new LinkedHashMap<>();
            fb.put("type", "USEFUL".equals(t.feedbackType()) ? "useful" : "useless");
            fb.put("note", t.feedbackNote() == null ? "" : t.feedbackNote());
            fb.put("at", t.feedbackAt());
            m.put("feedback", fb);
        } else {
            m.put("feedback", null);
        }
        return m;
    }

    private static List<Map<String, String>> parseSuggestions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
