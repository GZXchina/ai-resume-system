package cn.course.airesume.analysis;

import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import cn.course.airesume.job.JobDao;
import cn.course.airesume.resume.ResumeDao;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 模拟面试题生成（FR-INTERVIEW-01）。
 * 基于已成功的分析任务、岗位 JD 关键词与简历项目经历，用模板组合出结构化面试题；
 * 不调用大模型、不落库，同一任务每次生成的题目一致，便于课堂演示与复述练习。
 */
@Service
public class InterviewService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AnalysisDao analysisDao;
    private final JobDao jobDao;
    private final ResumeDao resumeDao;

    public InterviewService(AnalysisDao analysisDao, JobDao jobDao, ResumeDao resumeDao) {
        this.analysisDao = analysisDao;
        this.jobDao = jobDao;
        this.resumeDao = resumeDao;
    }

    public Map<String, Object> generate(String taskId) {
        LoginUser me = AuthContext.require();
        AnalysisDao.TaskRow task = analysisDao.findById(taskId)
                .orElseThrow(() -> new BizException("分析任务不存在"));
        if (task.userId() != me.id()) throw new BizException("无权访问该分析记录");
        if (!"SUCCESS".equals(task.status())) throw new BizException("仅成功的分析可以生成模拟面试题");

        JobDao.JobRow job = jobDao.findById(task.jobId())
                .orElseThrow(() -> new BizException("岗位不存在，无法生成面试题"));

        List<String> keywords = split(job.keywords());
        if (keywords.isEmpty()) keywords = RuleEngine.extractKeywords(job.jd());
        List<String> missing = split(task.missingKeywords());
        // 未覆盖的关键词优先提问，其次才是已覆盖的关键词
        Set<String> ordered = new LinkedHashSet<>(missing);
        ordered.addAll(keywords);

        List<Map<String, String>> questions = new ArrayList<>();
        addProjectQuestions(task.resumeId(), questions);
        addKeywordQuestions(ordered, missing, questions);
        addMatchQuestions(task, job, questions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.id());
        result.put("resumeName", task.resumeName());
        result.put("jobTitle", task.jobTitle());
        result.put("generatedAt", LocalDateTime.now().format(TS));
        result.put("mode", "rule");
        result.put("questions", questions);
        return result;
    }

    /** 项目经历深挖：优先取前两个有标题的项目，避免泛泛而谈。 */
    private void addProjectQuestions(long resumeId, List<Map<String, String>> out) {
        List<ResumeDao.Project> projects = resumeDao.listProjects(resumeId).stream()
                .filter(p -> p.title() != null && !p.title().isBlank())
                .limit(2)
                .toList();
        if (projects.isEmpty()) {
            out.add(q("项目经历", "请介绍一段你最有代表性的项目经历：背景、你的职责和最终结果。",
                    "按 STAR 组织；结果尽量量化（耗时、QPS、数据量、覆盖率）。"));
            return;
        }
        for (ResumeDao.Project p : projects) {
            String title = p.title().trim();
            out.add(q("项目经历", "请用 2 分钟介绍「" + title + "」：项目要解决什么问题，你具体负责哪部分？",
                    "先讲背景与目标，再讲你的职责边界，避免把团队成果说成个人成果。"));
            out.add(q("项目经历", "「" + title + "」中你遇到的最大技术难点是什么？当时有哪些备选方案，为什么选最终方案？",
                    "准备一个真实的取舍过程：约束条件 → 备选方案 → 选择理由 → 结果与代价。"));
        }
    }

    /** 技术深挖：围绕 JD 关键词逐个核对掌握程度，未覆盖关键词优先。 */
    private void addKeywordQuestions(Set<String> ordered, List<String> missing, List<Map<String, String>> out) {
        int i = 0;
        for (String k : ordered) {
            if (i >= 3) break;
            boolean gap = missing.contains(k);
            if (gap) {
                out.add(q("技术深挖", "JD 要求「" + k + "」，但简历中没有体现。你是否接触过？如果接触过，请说明使用场景；如果没有，打算如何补齐？",
                        "如实回答，不要虚构经历；给出可验证的学习计划（课程、练手项目、时间点）。"));
            } else {
                out.add(q("技术深挖", "简历中提到了「" + k + "」，请结合具体项目说明你的使用方式，以及它在该场景下的局限。",
                        "准备「用法 + 原理 + 踩坑 + 边界」四层，能说出局限比只背优点更可信。"));
            }
            i++;
        }
    }

    /** 动机与匹配度、反问环节。 */
    private void addMatchQuestions(AnalysisDao.TaskRow task, JobDao.JobRow job, List<Map<String, String>> out) {
        String jobName = job.title() + (job.company() == null || job.company().isBlank() ? "" : "（" + job.company() + "）");
        out.add(q("岗位匹配", "你为什么应聘「" + jobName + "」？请用三点说明你与这个岗位的匹配之处。",
                "每点都要有简历中的事实支撑：技能 + 项目证据 + 求职动机。"));
        if (task.score() != null) {
            out.add(q("岗位匹配", "如果让你给自己这次简历与岗位的匹配度打分（当前系统评估 " + task.score()
                            + " 分），你会打多少？差距主要在哪里？",
                    "分数不重要，重点是能说清差距和补强路径。"));
        }
        out.add(q("反问环节", "面试最后，你想向面试官了解什么？",
                "准备 2~3 个与团队、业务、成长相关的问题，避免只问薪资与加班。"));
    }

    private static Map<String, String> q(String category, String question, String tip) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("category", category);
        m.put("question", question);
        m.put("tip", tip);
        return m;
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) return new ArrayList<>();
        return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
