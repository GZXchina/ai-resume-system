package cn.course.airesume.resume;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ResumeDao {
    private final JdbcTemplate jdbc;

    public ResumeDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** resume 主表行（updated_at 直接格式化为前端约定的 "yyyy-MM-dd HH:mm"）。 */
    public record ResumeRow(long id, long userId, String name, String personName, String target,
                            String phone, String email, String summary, int version,
                            int completeness, String updatedAt) {}

    private static final RowMapper<ResumeRow> MAPPER = (rs, i) -> map(rs);

    private static ResumeRow map(ResultSet rs) throws SQLException {
        return new ResumeRow(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                rs.getString("person_name"), rs.getString("target"),
                rs.getString("phone"), rs.getString("email"), rs.getString("summary"),
                rs.getInt("version"), rs.getInt("completeness"), rs.getString("updated_at"));
    }

    private static final String COLS =
            "id, user_id, name, person_name, target, phone, email, summary, version, completeness, "
            + "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i') AS updated_at";

    public List<ResumeRow> listByUser(long userId, String keyword) {
        String sql = "SELECT " + COLS + " FROM resume WHERE user_id = ?";
        if (keyword != null && !keyword.isBlank()) {
            sql += " AND (name LIKE ? OR target LIKE ?) ORDER BY updated_at DESC";
            String like = "%" + keyword.trim() + "%";
            return jdbc.query(sql, MAPPER, userId, like, like);
        }
        return jdbc.query(sql + " ORDER BY updated_at DESC", MAPPER, userId);
    }

    public Optional<ResumeRow> findById(long id) {
        List<ResumeRow> list = jdbc.query("SELECT " + COLS + " FROM resume WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    /** 事务内锁定简历行（BR-ANALYSIS-02）：同一简历的发起请求串行化。 */
    public Optional<ResumeRow> findByIdForUpdate(long id) {
        List<ResumeRow> list = jdbc.query("SELECT " + COLS + " FROM resume WHERE id = ? FOR UPDATE", MAPPER, id);
        return list.stream().findFirst();
    }

    public long insert(long userId, String name, String personName, String target,
                       String phone, String email, String summary, int completeness) {
        jdbc.update("INSERT INTO resume (user_id, name, person_name, target, phone, email, summary, version, completeness, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,1,?,NOW(),NOW())",
                userId, name, personName, target, phone, email, summary, completeness);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public void updateContent(long id, String name, String personName, String target,
                              String phone, String email, String summary, int completeness) {
        jdbc.update("UPDATE resume SET name=?, person_name=?, target=?, phone=?, email=?, summary=?, completeness=?, updated_at=NOW() WHERE id=?",
                name, personName, target, phone, email, summary, completeness, id);
    }

    /** 采纳建议时：只更新内容字段并同步版本号。 */
    public void applyVersion(long id, String summary, int version, int completeness) {
        jdbc.update("UPDATE resume SET summary=?, version=?, completeness=?, updated_at=NOW() WHERE id=?",
                summary, version, completeness, id);
    }

    /* ---------- 子模块 ---------- */

    public record Education(long id, String school, String major, String degree, String period, String gpa) {}

    public List<Education> listEducation(long resumeId) {
        return jdbc.query("SELECT id, school, major, degree, period, gpa FROM resume_education WHERE resume_id=? ORDER BY sort_no",
                (rs, i) -> new Education(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                resumeId);
    }

    public record Project(long id, String title, String role, String period, String description) {}

    public List<Project> listProjects(long resumeId) {
        return jdbc.query("SELECT id, title, role, period, description FROM resume_project WHERE resume_id=? ORDER BY sort_no",
                (rs, i) -> new Project(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                resumeId);
    }

    public List<String> listSkills(long resumeId) {
        return jdbc.query("SELECT skill FROM resume_skill WHERE resume_id=? ORDER BY sort_no",
                (rs, i) -> rs.getString(1), resumeId);
    }

    public void replaceSubModules(long resumeId, List<Education> edus, List<Project> projects, List<String> skills) {
        jdbc.update("DELETE FROM resume_education WHERE resume_id=?", resumeId);
        jdbc.update("DELETE FROM resume_project WHERE resume_id=?", resumeId);
        jdbc.update("DELETE FROM resume_skill WHERE resume_id=?", resumeId);
        for (int i = 0; i < edus.size(); i++) {
            Education e = edus.get(i);
            jdbc.update("INSERT INTO resume_education (resume_id, school, major, degree, period, gpa, sort_no) VALUES (?,?,?,?,?,?,?)",
                    resumeId, e.school(), e.major(), e.degree(), e.period(), e.gpa(), i);
        }
        for (int i = 0; i < projects.size(); i++) {
            Project p = projects.get(i);
            jdbc.update("INSERT INTO resume_project (resume_id, title, role, period, description, sort_no) VALUES (?,?,?,?,?,?)",
                    resumeId, p.title(), p.role(), p.period(), p.description(), i);
        }
        for (int i = 0; i < skills.size(); i++) {
            jdbc.update("INSERT INTO resume_skill (resume_id, skill, sort_no) VALUES (?,?,?)", resumeId, skills.get(i), i);
        }
    }

    /** 采纳建议时只替换技能与项目（教育经历保留）。 */
    public void replaceSkillsAndProjects(long resumeId, List<Project> projects, List<String> skills) {
        jdbc.update("DELETE FROM resume_project WHERE resume_id=?", resumeId);
        jdbc.update("DELETE FROM resume_skill WHERE resume_id=?", resumeId);
        for (int i = 0; i < projects.size(); i++) {
            Project p = projects.get(i);
            jdbc.update("INSERT INTO resume_project (resume_id, title, role, period, description, sort_no) VALUES (?,?,?,?,?,?)",
                    resumeId, p.title(), p.role(), p.period(), p.description(), i);
        }
        for (int i = 0; i < skills.size(); i++) {
            jdbc.update("INSERT INTO resume_skill (resume_id, skill, sort_no) VALUES (?,?,?)", resumeId, skills.get(i), i);
        }
    }
}
