package cn.course.airesume.resume;

import cn.course.airesume.analysis.AnalysisDao;
import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 简历与版本业务（FR-RESUME-01/02、FR-VERSION-01/02）。
 * 所有读写均校验数据归属当前登录用户（BR-USER-02）。
 */
@Service
public class ResumeService {
    private final ResumeDao resumeDao;
    private final VersionDao versionDao;
    private final AnalysisDao analysisDao;
    private final SensitiveInfoScanner sensitiveInfoScanner;
    private final ObjectMapper objectMapper;

    public ResumeService(ResumeDao resumeDao, VersionDao versionDao, AnalysisDao analysisDao,
                         SensitiveInfoScanner sensitiveInfoScanner, ObjectMapper objectMapper) {
        this.resumeDao = resumeDao;
        this.versionDao = versionDao;
        this.analysisDao = analysisDao;
        this.sensitiveInfoScanner = sensitiveInfoScanner;
        this.objectMapper = objectMapper;
    }

    /* ---------------- 查询 ---------------- */

    public List<Map<String, Object>> list(String q) {
        LoginUser me = AuthContext.require();
        return resumeDao.listByUser(me.id(), q).stream().map(r -> {
            Map<String, Object> m = toClient(r, false);
            m.put("versionCount", versionDao.countByResume(r.id()));
            return m;
        }).toList();
    }

    public Map<String, Object> get(long id) {
        LoginUser me = AuthContext.require();
        ResumeDao.ResumeRow r = requireOwn(id, me);
        return toClient(r, true);
    }

    /* ---------------- 新建 / 编辑 ---------------- */

    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        LoginUser me = AuthContext.require();
        ResumeForm form = ResumeForm.from(payload); // 含字段校验，失败抛出指明字段的 BizException

        Long id = payload.get("id") == null || String.valueOf(payload.get("id")).isBlank()
                ? null : Long.valueOf(String.valueOf(payload.get("id")));

        if (id == null) {
            long newId = resumeDao.insert(me.id(), form.name(), form.personName(), form.target(),
                    form.phone(), form.email(), form.summary(), form.completeness());
            if (newId < 0) throw new BizException("保存失败，请稍后重试");
            resumeDao.replaceSubModules(newId, form.educations(), form.projects(), form.skills());
            // 新建即产生版本 1（便于后续对比基线）
            versionDao.tryInsert(uid("v"), newId, 1, null, "新建简历",
                    snapshotJson(form.summary(), form.skills(), form.projects()));
            return toClient(resumeDao.findById(newId).orElseThrow(), true);
        }

        ResumeDao.ResumeRow existing = requireOwn(id, me);
        resumeDao.updateContent(existing.id(), form.name(), form.personName(), form.target(),
                form.phone(), form.email(), form.summary(), form.completeness());
        resumeDao.replaceSubModules(existing.id(), form.educations(), form.projects(), form.skills());
        return toClient(resumeDao.findById(existing.id()).orElseThrow(), true);
    }

    /* ---------------- 版本 ---------------- */

    public Map<String, Object> listVersions(long resumeId) {
        LoginUser me = AuthContext.require();
        ResumeDao.ResumeRow r = requireOwn(resumeId, me);
        List<Map<String, Object>> versions = versionDao.listByResume(resumeId).stream()
                .map(this::versionToClient).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resume", toClient(r, true));
        result.put("versions", versions);
        return result;
    }

    public Map<String, Object> compare(long resumeId, int a, int b) {
        LoginUser me = AuthContext.require();
        requireOwn(resumeId, me);
        VersionDao.VersionRow va = versionDao.findByVersion(resumeId, a)
                .orElseThrow(() -> new BizException("所选版本不存在"));
        VersionDao.VersionRow vb = versionDao.findByVersion(resumeId, b)
                .orElseThrow(() -> new BizException("所选版本不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("a", versionToClient(va));
        result.put("b", versionToClient(vb));
        return result;
    }

    /**
     * 采纳分析建议生成新版本（FR-VERSION-01）：
     * 版本号 = 当前最大版本号 + 1，唯一键冲突时拒绝；原版本只读不受影响。
     */
    @Transactional
    public Map<String, Object> createVersionFromAnalysis(long resumeId, String sourceTaskId,
                                                         String note, String summary,
                                                         List<String> skills,
                                                         List<ResumeDao.Project> projects) {
        LoginUser me = AuthContext.require();
        ResumeDao.ResumeRow r = requireOwn(resumeId, me);
        if (note == null || note.isBlank()) throw new BizException("请填写本次修改说明");
        if (note.trim().length() > 200) throw new BizException("修改说明不能超过 200 字");
        skills = distinctSkills(skills);

        int next = versionDao.maxVersion(resumeId) + 1;
        String snapshot = snapshotJson(summary == null ? "" : summary, skills, projects);
        if (!versionDao.tryInsert(uid("v"), resumeId, next, sourceTaskId, note.trim(), snapshot)) {
            // 并发下版本号冲突：拒绝并提示刷新（BR-VERSION-01）
            throw new BizException("版本保存冲突，请刷新页面后重试");
        }
        resumeDao.replaceSkillsAndProjects(resumeId, projects, skills);
        int completeness = ResumeForm.completeness(r.name(), r.target(), r.phone(), r.email(),
                summary, resumeDao.listEducation(resumeId).size(), projects.size(), skills.size());
        resumeDao.applyVersion(resumeId, summary == null ? "" : summary, next, completeness);
        return toClient(resumeDao.findById(resumeId).orElseThrow(), true);
    }

    /** 新建简历时生成版本 1 快照（供外部复用）。 */
    public String snapshotJson(String summary, List<String> skills, List<ResumeDao.Project> projects) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("summary", summary == null ? "" : summary);
            snap.put("skills", skills == null ? List.of() : skills);
            List<Map<String, String>> ps = new ArrayList<>();
            if (projects != null) {
                for (ResumeDao.Project p : projects) {
                    ps.add(Map.of("title", p.title() == null ? "" : p.title(),
                                  "desc", p.description() == null ? "" : p.description()));
                }
            }
            snap.put("projects", ps);
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            throw new IllegalStateException("版本快照序列化失败", e);
        }
    }

    /* ---------------- 内部工具 ---------------- */

    public ResumeDao.ResumeRow requireOwn(long resumeId, LoginUser me) {
        ResumeDao.ResumeRow r = resumeDao.findById(resumeId)
                .orElseThrow(() -> new BizException("简历不存在"));
        if (r.userId() != me.id()) throw new BizException("无权访问该简历");
        return r;
    }

    /** 事务内锁定并校验归属：用于发起分析时串行化同一简历的并发请求（BR-ANALYSIS-02）。 */
    public ResumeDao.ResumeRow requireOwnForUpdate(long resumeId, LoginUser me) {
        ResumeDao.ResumeRow r = resumeDao.findByIdForUpdate(resumeId)
                .orElseThrow(() -> new BizException("简历不存在"));
        if (r.userId() != me.id()) throw new BizException("无权访问该简历");
        return r;
    }

    private Map<String, Object> toClient(ResumeDao.ResumeRow r, boolean withDetail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(r.id()));
        m.put("name", r.name());
        m.put("personName", r.personName());
        m.put("target", r.target());
        m.put("phone", r.phone() == null ? "" : r.phone());
        m.put("email", r.email() == null ? "" : r.email());
        m.put("summary", r.summary() == null ? "" : r.summary());
        m.put("version", r.version());
        m.put("completeness", r.completeness());
        m.put("updatedAt", r.updatedAt());
        if (withDetail) {
            m.put("skills", resumeDao.listSkills(r.id()));
            m.put("education", resumeDao.listEducation(r.id()).stream().map(e -> {
                Map<String, Object> em = new LinkedHashMap<String, Object>();
                em.put("school", nullToEmpty(e.school()));
                em.put("major", nullToEmpty(e.major()));
                em.put("degree", nullToEmpty(e.degree()));
                em.put("period", nullToEmpty(e.period()));
                em.put("gpa", nullToEmpty(e.gpa()));
                return em;
            }).toList());
            List<ResumeDao.Project> projects = resumeDao.listProjects(r.id());
            m.put("projects", projects.stream().map(p -> {
                Map<String, Object> pm = new LinkedHashMap<String, Object>();
                pm.put("title", nullToEmpty(p.title()));
                pm.put("role", nullToEmpty(p.role()));
                pm.put("period", nullToEmpty(p.period()));
                pm.put("desc", nullToEmpty(p.description()));
                return pm;
            }).toList());
            // 敏感信息提醒（FR-RESUME-03）：只提示不阻断，返回脱敏示例而非完整号码
            m.put("sensitiveWarnings", sensitiveInfoScanner.scan(r.phone(), r.summary(), projects));
        }
        return m;
    }

    private Map<String, Object> versionToClient(VersionDao.VersionRow v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("resumeId", String.valueOf(v.resumeId()));
        m.put("version", v.version());
        m.put("createdAt", v.createdAt());
        m.put("sourceTaskId", v.sourceTaskId());
        m.put("note", v.note());
        try {
            m.put("snapshot", objectMapper.readValue(v.snapshotJson(), new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            m.put("snapshot", Map.of("summary", "", "skills", List.of(), "projects", List.of()));
        }
        // 关联来源分析任务的岗位名称，便于版本列表展示来源
        m.put("sourceTask", v.sourceTaskId() == null ? null
                : analysisDao.findJobTitleById(v.sourceTaskId()).orElse(null));
        return m;
    }

    public static String uid(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 技能去重（大小写不敏感），与 uk_resume_skill 约束配合，避免采纳版本时插入重复技能。 */
    static List<String> distinctSkills(List<String> skills) {
        if (skills == null) return new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String raw : skills) {
            String s = raw == null ? "" : raw.trim();
            if (!s.isEmpty() && seen.add(s.toLowerCase(Locale.ROOT))) result.add(s);
        }
        return result;
    }

    static String nullToEmpty(String s) { return s == null ? "" : s; }
}
