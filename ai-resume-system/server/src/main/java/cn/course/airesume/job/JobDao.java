package cn.course.airesume.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JobDao {
    private final JdbcTemplate jdbc;

    public JobDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record JobRow(long id, long userId, String title, String company, String jd,
                         String keywords, String updatedAt) {}

    private static final RowMapper<JobRow> MAPPER = (rs, i) -> map(rs);

    private static JobRow map(ResultSet rs) throws SQLException {
        return new JobRow(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("title"),
                rs.getString("company"), rs.getString("jd"), rs.getString("keywords"),
                rs.getString("updated_at"));
    }

    private static final String COLS =
            "id, user_id, title, company, jd, keywords, DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i') AS updated_at";

    public List<JobRow> listByUser(long userId, String keyword) {
        String sql = "SELECT " + COLS + " FROM job WHERE user_id=?";
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            return jdbc.query(sql + " AND (title LIKE ? OR company LIKE ?) ORDER BY updated_at DESC",
                    MAPPER, userId, like, like);
        }
        return jdbc.query(sql + " ORDER BY updated_at DESC", MAPPER, userId);
    }

    public Optional<JobRow> findById(long id) {
        List<JobRow> list = jdbc.query("SELECT " + COLS + " FROM job WHERE id=?", MAPPER, id);
        return list.stream().findFirst();
    }

    public long insert(long userId, String title, String company, String jd, String keywords) {
        jdbc.update("INSERT INTO job (user_id, title, company, jd, keywords, created_at, updated_at) VALUES (?,?,?,?,?,NOW(),NOW())",
                userId, title, company, jd, keywords);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public void update(long id, String title, String company, String jd, String keywords) {
        jdbc.update("UPDATE job SET title=?, company=?, jd=?, keywords=?, updated_at=NOW() WHERE id=?",
                title, company, jd, keywords, id);
    }
}
