package cn.course.airesume.customapi;

import jakarta.annotation.PostConstruct;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 学生自定义 API 配置的数据访问（v2.0）。
 *
 * <p>配置项按 user_id 隔离；headers/params 以 JSON 文本存储，tags 以逗号分隔，
 * 标签过滤使用 MySQL 的 FIND_IN_SET，避免 LIKE 误命中。
 */
@Repository
public class UserApiConfigDao {
    private static final Logger log = LoggerFactory.getLogger(UserApiConfigDao.class);

    private static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS user_api_config (
              id           BIGINT AUTO_INCREMENT PRIMARY KEY,
              user_id      BIGINT       NOT NULL,
              name         VARCHAR(60)  NOT NULL,
              method       VARCHAR(10)  NOT NULL DEFAULT 'GET',
              url          VARCHAR(500) NOT NULL,
              headers_json TEXT         NULL,
              params_json  TEXT         NULL,
              body_type    VARCHAR(20)  NOT NULL DEFAULT 'none',
              body_json    TEXT         NULL,
              group_name   VARCHAR(40)  NOT NULL DEFAULT '默认分组',
              tags         VARCHAR(200) NOT NULL DEFAULT '',
              timeout_ms   INT          NOT NULL DEFAULT 15000,
              retries      INT          NOT NULL DEFAULT 0,
              enabled      TINYINT      NOT NULL DEFAULT 1,
              created_at   DATETIME     NOT NULL,
              updated_at   DATETIME     NOT NULL,
              KEY idx_uac_user (user_id),
              CONSTRAINT fk_uac_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci""";

    private static final String COLS = "id, user_id, name, method, url, headers_json, params_json, "
            + "body_type, body_json, group_name, tags, timeout_ms, retries, enabled, "
            + "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS created_at, "
            + "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i') AS updated_at";

    private final JdbcTemplate jdbc;

    public UserApiConfigDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long id, long userId, String name, String method, String url,
                      String headersJson, String paramsJson, String bodyType, String bodyJson,
                      String groupName, String tags, int timeoutMs, int retries, boolean enabled,
                      String createdAt, String updatedAt) {
    }

    /** 启动时自动补建表，避免漏跑迁移脚本导致功能不可用。 */
    @PostConstruct
    void ensureTable() {
        try {
            jdbc.execute(CREATE_SQL);
        } catch (Exception e) {
            log.warn("user_api_config 表初始化失败（请执行 db/migration-v2.0-custom-api.sql）：{}", e.getMessage());
        }
    }

    private static final RowMapper<Row> MAPPER = (rs, i) -> map(rs);

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                rs.getString("method"), rs.getString("url"),
                rs.getString("headers_json"), rs.getString("params_json"),
                rs.getString("body_type"), rs.getString("body_json"),
                rs.getString("group_name"), rs.getString("tags"),
                rs.getInt("timeout_ms"), rs.getInt("retries"), rs.getInt("enabled") == 1,
                rs.getString("created_at"), rs.getString("updated_at"));
    }

    public List<Row> listByUser(long userId, String q, String group, String tag) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM user_api_config WHERE user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (q != null && !q.isBlank()) {
            sql.append(" AND (name LIKE ? OR url LIKE ? OR tags LIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (group != null && !group.isBlank()) {
            sql.append(" AND group_name=?");
            args.add(group.trim());
        }
        if (tag != null && !tag.isBlank()) {
            sql.append(" AND FIND_IN_SET(?, tags) > 0");
            args.add(tag.trim());
        }
        sql.append(" ORDER BY updated_at DESC, id DESC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<Row> findById(long id) {
        List<Row> list = jdbc.query("SELECT " + COLS + " FROM user_api_config WHERE id=?", MAPPER, id);
        return list.stream().findFirst();
    }

    /** 全量配置（仅启动时做历史明文请求头加密迁移用）。 */
    public List<Row> listAll() {
        return jdbc.query("SELECT " + COLS + " FROM user_api_config", MAPPER);
    }

    /** 只更新请求头 JSON（迁移与敏感值重写用）。 */
    public int updateHeaders(long id, String headersJson) {
        return jdbc.update("UPDATE user_api_config SET headers_json=? WHERE id=?", headersJson, id);
    }

    public int countByUser(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user_api_config WHERE user_id=?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    public int countByName(long userId, String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_api_config WHERE user_id=? AND name=?", Integer.class, userId, name);
        return n == null ? 0 : n;
    }

    public List<String> groups(long userId) {
        return jdbc.queryForList(
                "SELECT DISTINCT group_name FROM user_api_config WHERE user_id=? ORDER BY group_name",
                String.class, userId);
    }

    public List<String> tags(long userId) {
        return jdbc.queryForList(
                "SELECT tags FROM user_api_config WHERE user_id=? AND tags<>'' ORDER BY updated_at DESC",
                String.class, userId);
    }

    /**
     * 插入并返回自增主键。
     *
     * <p>用 {@link KeyHolder} 与 INSERT 同一条语句取主键，避免连接池下
     * 「另起一次 SELECT LAST_INSERT_ID()」拿到其它连接的 0。
     */
    public long insert(long userId, String name, String method, String url,
                       String headersJson, String paramsJson, String bodyType, String bodyJson,
                       String groupName, String tags, int timeoutMs, int retries, boolean enabled) {
        String sql = "INSERT INTO user_api_config (user_id, name, method, url, headers_json, params_json, "
                + "body_type, body_json, group_name, tags, timeout_ms, retries, enabled, created_at, updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(),NOW())";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, method);
            ps.setString(4, url);
            ps.setString(5, headersJson);
            ps.setString(6, paramsJson);
            ps.setString(7, bodyType);
            ps.setString(8, bodyJson);
            ps.setString(9, groupName);
            ps.setString(10, tags);
            ps.setInt(11, timeoutMs);
            ps.setInt(12, retries);
            ps.setInt(13, enabled ? 1 : 0);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1 : key.longValue();
    }

    public int update(long id, long userId, String name, String method, String url,
                      String headersJson, String paramsJson, String bodyType, String bodyJson,
                      String groupName, String tags, int timeoutMs, int retries, boolean enabled) {
        return jdbc.update("UPDATE user_api_config SET name=?, method=?, url=?, headers_json=?, params_json=?, "
                        + "body_type=?, body_json=?, group_name=?, tags=?, timeout_ms=?, retries=?, enabled=?, "
                        + "updated_at=NOW() WHERE id=? AND user_id=?",
                name, method, url, headersJson, paramsJson, bodyType, bodyJson,
                groupName, tags, timeoutMs, retries, enabled ? 1 : 0, id, userId);
    }

    public int delete(long id, long userId) {
        return jdbc.update("DELETE FROM user_api_config WHERE id=? AND user_id=?", id, userId);
    }
}
