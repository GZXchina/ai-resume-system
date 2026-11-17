package cn.course.airesume.auth;

import jakarta.annotation.PostConstruct;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录凭证服务（v1.7 起持久化到 auth_token 表）。
 *
 * <p>与 v1.6 内存实现的差异：
 * <ul>
 *   <li>凭证落库，服务重启后仍有效，学生无需重新登录；</li>
 *   <li>过期时间滑动续期（默认 12 小时），续期写库做 5 分钟节流，避免每个请求一次 UPDATE；</li>
 *   <li>内存只保留 30 秒短 TTL 缓存，减少鉴权查库；</li>
 *   <li>账号禁用/删除时同时清库与清缓存，保证本实例立即踢下线（BR-USER-04）；</li>
 *   <li>auth_token.user_id 上有 ON DELETE CASCADE，删除用户自动清理凭证。</li>
 * </ul>
 * 多实例部署时缓存最多滞后 30 秒；如需强一致可把 CACHE_TTL_MILLIS 调小。
 */
@Component
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** 缓存有效期：期间不再查库，跨实例场景下最迟 30 秒感知凭证被删除 */
    private static final long CACHE_TTL_MILLIS = 30_000;
    /** 滑动续期写库节流：同一凭证 5 分钟内只更新一次 expires_at */
    private static final long SLIDE_INTERVAL_MILLIS = 5 * 60_000L;
    /** 缓存容量上限，超出后先清过期项，再退化为整体重建 */
    private static final int CACHE_MAX = 20_000;

    private static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS auth_token (
              token        VARCHAR(64) NOT NULL,
              user_id      BIGINT      NOT NULL,
              created_at   DATETIME    NOT NULL,
              expires_at   DATETIME    NOT NULL,
              last_seen_at DATETIME    NOT NULL,
              PRIMARY KEY (token),
              KEY idx_token_user (user_id),
              KEY idx_token_expires (expires_at),
              CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci""";

    private static final class Entry {
        final long userId;
        volatile long expiresAt;
        volatile long checkedAt;
        volatile long lastSlide;

        Entry(long userId, long expiresAt, long now) {
            this.userId = userId;
            this.expiresAt = expiresAt;
            this.checkedAt = now;
            this.lastSlide = now;
        }
    }

    private final JdbcTemplate jdbc;
    private final long ttlMillis;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public TokenService(JdbcTemplate jdbc, @Value("${auth.token-ttl-hours:12}") long ttlHours) {
        this.jdbc = jdbc;
        this.ttlMillis = Math.max(1, ttlHours) * 3600_000L;
    }

    /** 启动自愈：表缺失时自动建表（避免忘记执行迁移脚本），并清理历史过期凭证。 */
    @PostConstruct
    void initStorage() {
        try {
            jdbc.execute(CREATE_SQL);
            int removed = jdbc.update("DELETE FROM auth_token WHERE expires_at < ?",
                    new Timestamp(System.currentTimeMillis()));
            if (removed > 0) log.info("启动清理过期登录凭证 {} 条", removed);
        } catch (Exception e) {
            log.error("登录凭证表初始化失败，登录将不可用：{}", e.getMessage());
        }
    }

    public String issue(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        long expires = now + ttlMillis;
        Timestamp ts = new Timestamp(now);
        jdbc.update("INSERT INTO auth_token (token, user_id, created_at, expires_at, last_seen_at) VALUES (?,?,?,?,?)",
                token, userId, ts, new Timestamp(expires), ts);
        // 顺手清理该用户已过期凭证，避免表无限增长
        jdbc.update("DELETE FROM auth_token WHERE user_id = ? AND expires_at < ?", userId, ts);
        putCache(token, new Entry(userId, expires, now));
        return token;
    }

    /** 返回 userId；无效或过期返回 null。 */
    public Long resolve(String token) {
        if (token == null || token.isBlank()) return null;
        long now = System.currentTimeMillis();
        Entry e = cache.get(token);
        if (e != null) {
            if (now >= e.expiresAt) {
                cache.remove(token);
                jdbc.update("DELETE FROM auth_token WHERE token = ?", token);
                return null;
            }
            if (now - e.checkedAt < CACHE_TTL_MILLIS) {
                slide(token, e, now);
                return e.userId;
            }
        }
        return loadFromDb(token, now);
    }

    private Long loadFromDb(String token, long now) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, expires_at FROM auth_token WHERE token = ?", token);
        if (rows.isEmpty()) {
            cache.remove(token);
            return null;
        }
        Map<String, Object> row = rows.get(0);
        long userId = ((Number) row.get("user_id")).longValue();
        long expiresAt = ((java.util.Date) row.get("expires_at")).getTime();
        if (expiresAt <= now) {
            jdbc.update("DELETE FROM auth_token WHERE token = ?", token);
            cache.remove(token);
            return null;
        }
        long newExpires = now + ttlMillis;
        jdbc.update("UPDATE auth_token SET expires_at = ?, last_seen_at = ? WHERE token = ?",
                new Timestamp(newExpires), new Timestamp(now), token);
        putCache(token, new Entry(userId, newExpires, now));
        return userId;
    }

    private void slide(String token, Entry e, long now) {
        if (now - e.lastSlide < SLIDE_INTERVAL_MILLIS) return;
        long newExpires = now + ttlMillis;
        try {
            jdbc.update("UPDATE auth_token SET expires_at = ?, last_seen_at = ? WHERE token = ?",
                    new Timestamp(newExpires), new Timestamp(now), token);
        } catch (Exception ex) {
            // 续期失败不影响本次鉴权，下次命中缓存时再试
            log.warn("凭证续期失败：{}", ex.getMessage());
            return;
        }
        e.expiresAt = newExpires;
        e.lastSlide = now;
        e.checkedAt = now;
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) return;
        cache.remove(token);
        jdbc.update("DELETE FROM auth_token WHERE token = ?", token);
    }

    /** 账号被禁用/状态变化时，使其全部凭证立即失效（BR-USER-04）。 */
    public void revokeByUser(long userId) {
        cache.entrySet().removeIf(e -> e.getValue().userId == userId);
        jdbc.update("DELETE FROM auth_token WHERE user_id = ?", userId);
    }

    private void putCache(String token, Entry e) {
        if (cache.size() >= CACHE_MAX) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(en -> now >= en.getValue().expiresAt);
            if (cache.size() >= CACHE_MAX) cache.clear();
        }
        cache.put(token, e);
    }

    /** 供管理端展示：当前有效凭证数量。 */
    public int activeCount() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_token WHERE expires_at > ?",
                Integer.class, new Timestamp(System.currentTimeMillis()));
        return n == null ? 0 : n;
    }
}
