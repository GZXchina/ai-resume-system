package cn.course.airesume.user;

import cn.course.airesume.auth.LoginUser;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserDao {
    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<LoginUser> MAPPER = (rs, i) -> map(rs);

    private static LoginUser map(ResultSet rs) throws SQLException {
        return new LoginUser(
                rs.getLong("id"),
                rs.getString("account"),
                rs.getString("name"),
                rs.getString("role"),
                rs.getString("status"));
    }

    public Optional<LoginUser> findById(long id) {
        List<LoginUser> list = jdbc.query(
                "SELECT id, account, name, role, status FROM sys_user WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<LoginUser> findByAccount(String account) {
        List<LoginUser> list = jdbc.query(
                "SELECT id, account, name, role, status FROM sys_user WHERE account = ?", MAPPER, account);
        return list.stream().findFirst();
    }

    public String passwordHashOf(String account) {
        List<String> list = jdbc.query(
                "SELECT password_hash FROM sys_user WHERE account = ?",
                (rs, i) -> rs.getString(1), account);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(String account, String passwordHash, String name) {
        jdbc.update(
                "INSERT INTO sys_user (account, password_hash, name, role, status, created_at) VALUES (?,?,?,'STUDENT','ACTIVE',NOW())",
                account, passwordHash, name);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE sys_user SET status = ? WHERE id = ?", status, id);
    }
}
