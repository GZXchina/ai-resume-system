package cn.course.airesume.job;

import cn.course.airesume.analysis.RuleEngine;
import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import cn.course.airesume.resume.ResumeDao;
import cn.course.airesume.resume.ResumeService;
import cn.course.airesume.resume.ResumeTextService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 岗位匹配度排序（FR-JOB-02）与多简历对比（FR-JOB-03）。
 * 用与规则分析完全相同的文本口径（ResumeTextService）计算 JD 关键词覆盖率，
 * 覆盖率 = 命中关键词数 / 关键词总数；仅用于排序与横向对比，不写入分析记录。
 */
@Service
public class JobMatchService {
    private final JobDao jobDao;
    private final ResumeService resumeService;
    private final ResumeTextService resumeTextService;
    private final ResumeDao resumeDao;

    public JobMatchService(JobDao jobDao, ResumeService resumeService,
                           ResumeTextService resumeTextService, ResumeDao resumeDao) {
        this.jobDao = jobDao;
        this.resumeService = resumeService;
        this.resumeTextService = resumeTextService;
        this.resumeDao = resumeDao;
    }

    public Map<String, Object> match(long resumeId, String q) {
        LoginUser me = AuthContext.require();
        // 归属校验（BR-USER-02）：只能用自己的简历与岗位做匹配
        resumeService.requireOwn(resumeId, me);
        String text = resumeTextService.build(resumeId).toLowerCase();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (JobDao.JobRow job : jobDao.listByUser(me.id(), q)) {
            KeywordHit hit = evaluate(job, text);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(job.id()));
            m.put("title", job.title());
            m.put("company", job.company() == null ? "" : job.company());
            m.put("jd", job.jd());
            m.put("keywords", hit.keywords());
            m.put("updatedAt", job.updatedAt());
            m.put("matchScore", hit.score());
            m.put("matched", hit.matched());
            m.put("missing", hit.missing());
            m.put("keywordCount", hit.keywords().size());
            m.put("keywordSource", hit.fallback() ? "jd" : "manual");
            rows.add(m);
        }
        rows.sort(Comparator.<Map<String, Object>>comparingInt(m -> (Integer) m.get("matchScore"))
                .reversed()
                .thenComparing(m -> String.valueOf(m.get("updatedAt")), Comparator.reverseOrder()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumeId", String.valueOf(resumeId));
        result.put("jobs", rows);
        return result;
    }

    /**
     * 多简历对比（FR-JOB-03）：同一岗位下 2~3 份本人简历的匹配度、完整度与关键词覆盖。
     * 不做业务写入，只返回对比视图所需数据。
     */
    public Map<String, Object> compare(long jobId, List<Long> resumeIds) {
        LoginUser me = AuthContext.require();
        JobDao.JobRow job = jobDao.findById(jobId)
                .orElseThrow(() -> new BizException("岗位不存在"));
        if (job.userId() != me.id()) throw new BizException("无权访问该岗位");
        if (resumeIds == null || resumeIds.size() < 2) throw new BizException("请选择 2~3 份简历进行对比");
        if (resumeIds.size() > 3) throw new BizException("最多同时对比 3 份简历");
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(resumeIds));
        if (distinct.size() != resumeIds.size()) throw new BizException("请勿重复选择同一份简历");

        List<Map<String, Object>> items = new ArrayList<>();
        for (Long resumeId : distinct) {
            ResumeDao.ResumeRow resume = resumeService.requireOwn(resumeId, me);
            KeywordHit hit = evaluate(job, resumeTextService.build(resumeId).toLowerCase());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resumeId", String.valueOf(resume.id()));
            m.put("name", resume.name());
            m.put("personName", resume.personName());
            m.put("target", resume.target());
            m.put("version", resume.version());
            m.put("completeness", resume.completeness());
            m.put("skillCount", resumeDao.listSkills(resumeId).size());
            m.put("projectCount", resumeDao.listProjects(resumeId).size());
            m.put("matchScore", hit.score());
            m.put("matched", hit.matched());
            m.put("missing", hit.missing());
            m.put("keywordCount", hit.keywords().size());
            m.put("keywordSource", hit.fallback() ? "jd" : "manual");
            items.add(m);
        }
        items.sort(Comparator.<Map<String, Object>>comparingInt(m -> (Integer) m.get("matchScore"))
                .reversed()
                .thenComparing(m -> (Integer) m.get("completeness"), Comparator.reverseOrder()));

        String bestMatch = String.valueOf(items.get(0).get("resumeId"));
        String bestCompleteness = items.stream()
                .max(Comparator.comparingInt(m -> (Integer) m.get("completeness")))
                .map(m -> String.valueOf(m.get("resumeId"))).orElse(bestMatch);
        for (Map<String, Object> m : items) {
            m.put("bestMatch", bestMatch.equals(String.valueOf(m.get("resumeId"))));
            m.put("bestCompleteness", bestCompleteness.equals(String.valueOf(m.get("resumeId"))));
        }

        Map<String, Object> jobInfo = new LinkedHashMap<>();
        jobInfo.put("id", String.valueOf(job.id()));
        jobInfo.put("title", job.title());
        jobInfo.put("company", job.company() == null ? "" : job.company());
        jobInfo.put("keywordCount", items.get(0).get("keywordCount"));
        jobInfo.put("keywordSource", items.get(0).get("keywordSource"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("job", jobInfo);
        result.put("items", items);
        result.put("bestMatchResumeId", bestMatch);
        result.put("bestCompletenessResumeId", bestCompleteness);
        result.put("notes", List.of(
                "匹配度 = 岗位 JD 关键词在该简历中的覆盖率，仅用于横向参考",
                "完整度来自简历自身评分，与岗位无关",
                "正式分析结果以「简历分析」任务为准"));
        return result;
    }

    private KeywordHit evaluate(JobDao.JobRow job, String text) {
        List<String> keywords = splitKeywords(job.keywords());
        boolean fallback = keywords.isEmpty();
        if (fallback) keywords = RuleEngine.extractKeywords(job.jd());
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String k : keywords) {
            if (k == null || k.isBlank()) continue;
            if (text.contains(k.trim().toLowerCase())) matched.add(k.trim());
            else missing.add(k.trim());
        }
        int score = keywords.isEmpty() ? 0 : (int) Math.round(matched.size() * 100.0 / keywords.size());
        return new KeywordHit(keywords, matched, missing, score, fallback);
    }

    private record KeywordHit(List<String> keywords, List<String> matched, List<String> missing,
                              int score, boolean fallback) {}

    private static List<String> splitKeywords(String keywords) {
        if (keywords == null || keywords.isBlank()) return new ArrayList<>();
        return Arrays.stream(keywords.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
