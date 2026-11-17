package cn.course.airesume.resume;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class VersionDao {
    private final JdbcTemplate jdbc;

    public VersionDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record VersionRow(String id, long resumeId, int version, String sourceTaskId,
                             String note, String snapshotJson, String createdAt) {}

    private static final RowMapper<VersionRow> MAPPER = (rs, i) -> map(rs);

    private static VersionRow map(ResultSet rs) throws SQLException {
        return new VersionRow(
                rs.getString("id"), rs.getLong("resume_id"), rs.getInt("version"),
                rs.getString("source_task_id"), rs.getString("note"),
                rs.getString("snapshot_json"), rs.getString("created_at"));
    }

    private static final String COLS =
            "id, resume_id, version, source_task_id, note, snapshot_json, "
            + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS created_at";

    public List<VersionRow> listByResume(long resumeId) {
        return jdbc.query("SELECT " + COLS + " FROM resume_version WHERE resume_id=? ORDER BY version DESC",
                MAPPER, resumeId);
    }

    public Optional<VersionRow> findByVersion(long resumeId, int version) {
        List<VersionRow> list = jdbc.query(
                "SELECT " + COLS + " FROM resume_version WHERE resume_id=? AND version=?", MAPPER, resumeId, version);
        return list.stream().findFirst();
    }

    public int maxVersion(long resumeId) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM resume_version WHERE resume_id=?", Integer.class, resumeId);
        return v == null ? 0 : v;
    }

    public int countByResume(long resumeId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resume_version WHERE resume_id=?", Integer.class, resumeId);
        return c == null ? 0 : c;
    }

    /**
     * 插入新版本；uk_resume_version(resume_id, version) 冲突时返回 false（BR-VERSION-01 并发保护）。
     */
    public boolean tryInsert(String id, long resumeId, int version, String sourceTaskId,
                             String note, String snapshotJson) {
        try {
            jdbc.update("INSERT INTO resume_version (id, resume_id, version, source_task_id, note, snapshot_json, created_at) "
                            + "VALUES (?,?,?,?,?,?,NOW())",
                    id, resumeId, version, sourceTaskId, note, snapshotJson);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
