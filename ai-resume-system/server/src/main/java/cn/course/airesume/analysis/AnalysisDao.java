package cn.course.airesume.analysis;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AnalysisDao {
    private final JdbcTemplate jdbc;

    public AnalysisDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record TaskRow(String id, long userId, long resumeId, long jobId,
                          String resumeName, String jobTitle, String status, String mode,
                          Integer score, double duration, String missingKeywords,
                          String suggestionsJson, String reason,
                          String feedbackType, String feedbackNote, String feedbackAt,
                          boolean demoFail, String createdAt) {}

    private static final RowMapper<TaskRow> MAPPER = (rs, i) -> map(rs);

    private static TaskRow map(ResultSet rs) throws SQLException {
        Integer score = rs.getObject("score") == null ? null : rs.getInt("score");
        return new TaskRow(
                rs.getString("id"), rs.getLong("user_id"), rs.getLong("resume_id"), rs.getLong("job_id"),
                rs.getString("resume_name"), rs.getString("job_title"), rs.getString("status"), rs.getString("mode"),
                score, rs.getDouble("duration"), rs.getString("missing_keywords"), rs.getString("suggestions_json"),
                rs.getString("reason"), rs.getString("feedback_type"), rs.getString("feedback_note"),
                rs.getString("feedback_at"), rs.getInt("demo_fail") == 1, rs.getString("created_at"));
    }

    private static final String COLS =
            "id, user_id, resume_id, job_id, resume_name, job_title, status, mode, score, duration, "
            + "missing_keywords, suggestions_json, reason, feedback_type, feedback_note, "
            + "DATE_FORMAT(feedback_at, '%Y-%m-%d %H:%i') AS feedback_at, demo_fail, "
            + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS created_at";

    public void insert(TaskRow t) {
        jdbc.update("INSERT INTO analysis_task (id, user_id, resume_id, job_id, resume_name, job_title, status, mode, "
                        + "score, duration, missing_keywords, suggestions_json, reason, demo_fail, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,NULL,NULL,NULL,?,NOW(),NOW())",
                t.id(), t.userId(), t.resumeId(), t.jobId(), t.resumeName(), t.jobTitle(),
                t.status(), t.mode(), t.score(), t.demoFail() ? 1 : 0);
    }

    public Optional<TaskRow> findById(String id) {
        List<TaskRow> list = jdbc.query("SELECT " + COLS + " FROM analysis_task WHERE id=?", MAPPER, id);
        return list.stream().findFirst();
    }

    public List<TaskRow> listByUser(long userId, String status, String q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM analysis_task WHERE user_id=?");
        var args = new java.util.ArrayList<Object>();
        args.add(userId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (resume_name LIKE ? OR job_title LIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 管理端：按状态/关键词只读查询全部任务元信息。 */
    public List<TaskRow> listAll(String status, String q) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM analysis_task WHERE 1=1");
        var args = new java.util.ArrayList<Object>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (resume_name LIKE ? OR job_title LIKE ? OR CAST(user_id AS CHAR) LIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public boolean existsRunning(long resumeId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analysis_task WHERE resume_id=? AND status='RUNNING'", Integer.class, resumeId);
        return c != null && c > 0;
    }

    public void markSuccess(String id, int score, double duration, String missingKeywords, String suggestionsJson) {
        jdbc.update("UPDATE analysis_task SET status='SUCCESS', score=?, duration=?, missing_keywords=?, suggestions_json=?, "
                        + "reason=NULL, demo_fail=0, updated_at=NOW() WHERE id=?",
                score, duration, missingKeywords, suggestionsJson, id);
    }

    public void markFailed(String id, double duration, String reason) {
        jdbc.update("UPDATE analysis_task SET status='FAILED', duration=?, reason=?, demo_fail=0, updated_at=NOW() WHERE id=?",
                duration, reason, id);
    }

    /**
     * AI 服务不可达时降级为规则模式（BR-ANALYSIS-04）：任务仍为成功，但分析方式改为 RULE，
     * 并在 reason 中保留降级原因，前端据此展示提示。
     */
    public void markRuleFallback(String id, int score, double duration, String missingKeywords,
                                 String suggestionsJson, String reason) {
        jdbc.update("UPDATE analysis_task SET status='SUCCESS', mode='RULE', score=?, duration=?, "
                        + "missing_keywords=?, suggestions_json=?, reason=?, demo_fail=0, updated_at=NOW() WHERE id=?",
                score, duration, missingKeywords, suggestionsJson, reason, id);
    }

    public void saveFeedback(String id, String type, String note) {
        jdbc.update("UPDATE analysis_task SET feedback_type=?, feedback_note=?, feedback_at=NOW(), updated_at=NOW() WHERE id=?",
                type, note, id);
    }

    public Optional<String> findJobTitleById(String taskId) {
        List<String> list = jdbc.query("SELECT job_title FROM analysis_task WHERE id=?",
                (rs, i) -> rs.getString(1), taskId);
        return list.stream().findFirst();
    }

    /* ---------- 统计（FR-STAT-01） ---------- */

    public int countByStatus(String status) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task WHERE status=?", Integer.class, status);
        return c == null ? 0 : c;
    }

    public int countAll() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task", Integer.class);
        return c == null ? 0 : c;
    }

    public int countByMode(String mode) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM analysis_task WHERE mode=?", Integer.class, mode);
        return c == null ? 0 : c;
    }

    public double avgScoreOfSuccess() {
        Double d = jdbc.queryForObject(
                "SELECT COALESCE(AVG(score),0) FROM analysis_task WHERE status='SUCCESS'", Double.class);
        return d == null ? 0 : d;
    }

    /** 近 7 天（含今天）按日统计：count / success。 */
    public List<Object[]> dailyCounts() {
        return jdbc.query("SELECT DATE_FORMAT(created_at,'%Y-%m-%d'), COUNT(*), "
                        + "SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) "
                        + "FROM analysis_task WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
                        + "GROUP BY DATE_FORMAT(created_at,'%Y-%m-%d')",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getInt(3)});
    }
}
