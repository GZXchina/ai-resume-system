package cn.course.airesume.resume;

import org.springframework.stereotype.Component;

/**
 * 简历文本视图：把结构化简历拼成用于关键词比对的纯文本。
 * 分析任务（RuleEngine/AiClient）与岗位匹配度排序共用同一口径，避免两处结果不一致。
 */
@Component
public class ResumeTextService {
    private final ResumeDao resumeDao;

    public ResumeTextService(ResumeDao resumeDao) {
        this.resumeDao = resumeDao;
    }

    public String build(long resumeId) {
        return resumeDao.findById(resumeId).map(r -> {
            StringBuilder sb = new StringBuilder();
            sb.append(r.summary() == null ? "" : r.summary()).append(' ');
            resumeDao.listSkills(resumeId).forEach(s -> sb.append(s).append(' '));
            resumeDao.listProjects(resumeId).forEach(p ->
                    sb.append(p.title() == null ? "" : p.title()).append(' ')
                      .append(p.description() == null ? "" : p.description()).append(' '));
            return sb.toString();
        }).orElse("");
    }
}
