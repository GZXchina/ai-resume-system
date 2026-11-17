package cn.course.airesume.resume;

import cn.course.airesume.common.BizException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 简历表单解析与校验（BR-RESUME-01/02）：
 * 必填：简历名称、姓名、求职意向；至少一条教育经历与一条项目/实习经历（与原型校验口径一致）。
 * 长度：简历名称 ≤ 50 字，姓名 ≤ 20 字，求职意向 ≤ 50 字，单条经历描述 ≤ 1000 字。
 */
public record ResumeForm(String name, String personName, String target, String phone, String email,
                         String summary, List<ResumeDao.Education> educations,
                         List<ResumeDao.Project> projects, List<String> skills, int completeness) {

    public static ResumeForm from(Map<String, Object> p) {
        String name = str(p.get("name"));
        String personName = str(p.get("personName"));
        String target = str(p.get("target"));
        String phone = str(p.get("phone"));
        String email = str(p.get("email"));
        String summary = str(p.get("summary"));

        if (name.isEmpty()) throw new BizException("简历名称不能为空");
        if (name.length() > 50) throw new BizException("简历名称不能超过 50 字");
        if (personName.isEmpty()) throw new BizException("姓名不能为空");
        if (personName.length() > 20) throw new BizException("姓名不能超过 20 字");
        if (target.isEmpty()) throw new BizException("求职意向不能为空");
        if (target.length() > 50) throw new BizException("求职意向不能超过 50 字");
        if (phone.length() > 20) throw new BizException("联系电话不能超过 20 字");
        if (email.length() > 60) throw new BizException("邮箱不能超过 60 字");

        List<ResumeDao.Education> edus = new ArrayList<>();
        for (Object o : listOf(p.get("education"))) {
            if (o instanceof Map<?, ?> m) {
                edus.add(new ResumeDao.Education(0,
                        cut(str(m.get("school")), 80, "学校名称"),
                        cut(str(m.get("major")), 80, "专业名称"),
                        cut(str(m.get("degree")), 20, "学历"),
                        cut(str(m.get("period")), 40, "教育时间段"),
                        cut(str(m.get("gpa")), 20, "GPA")));
            }
        }
        List<ResumeDao.Project> projects = new ArrayList<>();
        for (Object o : listOf(p.get("projects"))) {
            if (o instanceof Map<?, ?> m) {
                projects.add(new ResumeDao.Project(0,
                        cut(str(m.get("title")), 80, "项目名称"),
                        cut(str(m.get("role")), 40, "担任角色"),
                        cut(str(m.get("period")), 40, "项目时间段"),
                        cut(str(m.get("desc")), 1000, "经历描述")));
            }
        }
        List<String> skills = new ArrayList<>();
        Set<String> seenSkills = new HashSet<>();
        for (Object o : listOf(p.get("skills"))) {
            String s = cut(str(o), 50, "技能");
            if (!s.isEmpty() && seenSkills.add(s.toLowerCase(Locale.ROOT))) skills.add(s);
        }

        if (edus.isEmpty()) throw new BizException("请至少填写一条教育经历");
        if (projects.isEmpty()) throw new BizException("请至少填写一条项目/实习经历");

        int completeness = completeness(name, target, phone, email, summary,
                edus.size(), projects.size(), skills.size());
        return new ResumeForm(name, personName, target, phone, email, summary,
                edus, projects, skills, completeness);
    }

    /** 完整度评分（与原接口径一致，满分 100）。 */
    public static int completeness(String name, String target, String phone, String email,
                                   String summary, int eduCount, int projectCount, int skillCount) {
        int s = 0;
        if (name != null && !name.isEmpty()) s += 10;
        if (target != null && !target.isEmpty()) s += 10;
        if (phone != null && !phone.isEmpty()) s += 5;
        if (email != null && !email.isEmpty()) s += 5;
        if (summary != null && summary.length() >= 20) s += 15;
        if (eduCount > 0) s += 20;
        if (projectCount > 0) s += 25;
        if (skillCount >= 3) s += 15;
        return Math.min(100, s);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static String cut(String s, int max, String field) {
        if (s.length() > max) throw new BizException(field + "不能超过 " + max + " 字");
        return s;
    }
}
