package cn.course.airesume.admin;

import cn.course.airesume.analysis.AiProperties;
import cn.course.airesume.analysis.AnalysisDao;
import cn.course.airesume.analysis.AnalysisService;
import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.auth.TokenService;
import cn.course.airesume.common.BizException;
import cn.course.airesume.user.UserDao;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 管理端业务（FR-ADMIN-01/02、FR-STAT-01/02）。 */
@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final UserDao userDao;
    private final AnalysisDao analysisDao;
    private final TokenService tokenService;
    private final AiProperties aiProperties;

    public AdminService(JdbcTemplate jdbc, UserDao userDao, AnalysisDao analysisDao,
                        TokenService tokenService, AiProperties aiProperties) {
        this.jdbc = jdbc;
        this.userDao = userDao;
        this.analysisDao = analysisDao;
        this.tokenService = tokenService;
        this.aiProperties = aiProperties;
    }

    /** 用户列表（仅学生），附分析/简历数量。 */
    public List<Map<String, Object>> users(String q) {
        AuthContext.requireAdmin();
        StringBuilder sql = new StringBuilder(
                "SELECT id, account, name, role, status, DATE_FORMAT(created_at,'%Y-%m-%d') AS created_at "
                        + "FROM sys_user WHERE role='STUDENT'");
        var args = new ArrayList<Object>();
        if (q != null && !q.isBlank()) {
            sql.append(" AND (account LIKE ? OR name LIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY created_at");
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            long uid = rs.getLong("id");
            m.put("id", String.valueOf(uid));
            m.put("account", rs.getString("account"));
            m.put("name", rs.getString("name"));
            m.put("role", "student");
            m.put("status", "ACTIVE".equals(rs.getString("status")) ? "active" : "disabled");
            m.put("createdAt", rs.getString("created_at"));
            m.put("analyses", countOf("SELECT COUNT(*) FROM analysis_task WHERE user_id=" + uid));
            m.put("resumes", countOf("SELECT COUNT(*) FROM resume WHERE user_id=" + uid));
            return m;
        }, args.toArray());
    }

    /** 启用/禁用账号；禁用后立即使凭证失效（BR-USER-04）；不能操作自身（BR-ADMIN-02）。 */
    public Map<String, Object> toggleUser(long id) {
        LoginUser me = AuthContext.requireAdmin();
        if (id == me.id()) throw new BizException("不能禁用当前登录的管理员账号");
        var user = userDao.findById(id).orElseThrow(() -> new BizException("用户不存在"));
        String next = "ACTIVE".equals(user.status()) ? "DISABLED" : "ACTIVE";
        userDao.updateStatus(id, next);
        if ("DISABLED".equals(next)) tokenService.revokeByUser(id);
        return userDao.findById(id).map(u -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", String.valueOf(u.id()));
            m.put("account", u.account());
            m.put("name", u.name());
            m.put("role", u.roleForClient());
            m.put("status", "ACTIVE".equals(u.status()) ? "active" : "disabled");
            return m;
        }).orElseThrow();
    }

    /**
     * 任务监管列表（只读，BR-ADMIN-01：不返回简历正文与建议全文，仅元信息）。
     */
    public List<Map<String, Object>> tasks(String status, String q) {
        AuthContext.requireAdmin();
        return analysisDao.listAll(AnalysisService.dbStatus(status), q).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", t.id());
            m.put("userId", String.valueOf(t.userId()));
            m.put("userName", userDao.findById(t.userId()).map(LoginUser::name).orElse("未知用户"));
            m.put("resumeId", String.valueOf(t.resumeId()));
            m.put("jobId", String.valueOf(t.jobId()));
            m.put("resumeName", t.resumeName());
            m.put("jobTitle", t.jobTitle());
            m.put("status", switch (t.status()) {
                case "RUNNING" -> "analyzing";
                case "SUCCESS" -> "success";
                case "FAILED" -> "failed";
                default -> t.status().toLowerCase();
            });
            m.put("mode", "RULE".equals(t.mode()) ? "rule" : "ai");
            m.put("score", t.score());
            m.put("duration", t.duration());
            m.put("reason", t.reason());
            m.put("createdAt", t.createdAt());
            // 明确不包含 suggestions / missing / 简历正文
            return m;
        }).toList();
    }

    /** 基础统计（FR-STAT-01）：数值与任务列表同库同口径，保证一致。 */
    public Map<String, Object> stats() {
        AuthContext.requireAdmin();
        int total = analysisDao.countAll();
        int success = analysisDao.countByStatus("SUCCESS");
        int failed = analysisDao.countByStatus("FAILED");
        int running = analysisDao.countByStatus("RUNNING");
        int rule = analysisDao.countByMode("RULE");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users", countOf("SELECT COUNT(*) FROM sys_user WHERE role='STUDENT'"));
        m.put("activeUsers", countOf("SELECT COUNT(*) FROM sys_user WHERE role='STUDENT' AND status='ACTIVE'"));
        m.put("analyses", total);
        m.put("success", success);
        m.put("failed", failed);
        m.put("analyzing", running);
        m.put("rule", rule);
        m.put("successRate", total == 0 ? 0 : Math.round(success * 100.0 / total));
        m.put("ruleShare", total == 0 ? 0 : Math.round(rule * 100.0 / total));
        m.put("avgScore", (int) Math.round(analysisDao.avgScoreOfSuccess()));

        // 近 7 天趋势（无数据的日期补 0）
        Map<String, int[]> byDay = new LinkedHashMap<>();
        for (Object[] row : analysisDao.dailyCounts()) {
            byDay.put((String) row[0], new int[]{(Integer) row[1], (Integer) row[2]});
        }
        List<Map<String, Object>> chart = new ArrayList<>();
        DateTimeFormatter dayKey = DateTimeFormatter.ISO_LOCAL_DATE;
        DateTimeFormatter label = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 6; i >= 0; i--) {
            LocalDate d = LocalDate.now().minusDays(i);
            int[] c = byDay.getOrDefault(d.format(dayKey), new int[]{0, 0});
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("day", d.format(label));
            point.put("count", c[0]);
            point.put("success", c[1]);
            chart.add(point);
        }
        m.put("chart", chart);
        return m;
    }

    /**
     * 质量与反馈统计（FR-STAT-02）：反馈有用率、分析方式分布、降级次数、Top 缺失关键词与系统信息。
     * 所有数值与任务列表同库同口径；不返回简历正文与建议全文。
     */
    public Map<String, Object> quality() {
        AuthContext.requireAdmin();
        int success = analysisDao.countByStatus("SUCCESS");

        Map<String, Integer> feedbackCounts = new HashMap<>();
        jdbc.query("SELECT feedback_type, COUNT(*) AS c FROM analysis_task WHERE status='SUCCESS' GROUP BY feedback_type",
                rs -> {
                    String type = rs.getString("feedback_type");
                    feedbackCounts.put(type == null ? "NONE" : type.toUpperCase(java.util.Locale.ROOT), rs.getInt("c"));
                });
        int useful = feedbackCounts.getOrDefault("USEFUL", 0);
        int useless = feedbackCounts.getOrDefault("USELESS", 0);
        int feedbackTotal = useful + useless;

        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("successTotal", success);
        feedback.put("useful", useful);
        feedback.put("useless", useless);
        feedback.put("none", Math.max(0, success - feedbackTotal));
        feedback.put("total", feedbackTotal);
        feedback.put("usefulRate", feedbackTotal == 0 ? 0 : Math.round(useful * 100.0f / feedbackTotal));
        feedback.put("coverage", success == 0 ? 0 : Math.round(feedbackTotal * 100.0f / success));

        Map<String, Map<String, Object>> modeMap = new LinkedHashMap<>();
        for (String mode : List.of("ai", "rule")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mode", mode);
            item.put("count", 0);
            item.put("avgScore", 0);
            item.put("share", 0);
            modeMap.put(mode, item);
        }
        jdbc.query("SELECT mode, COUNT(*) AS c, AVG(score) AS a FROM analysis_task "
                + "WHERE status='SUCCESS' GROUP BY mode", rs -> {
            String mode = "RULE".equalsIgnoreCase(rs.getString("mode")) ? "rule" : "ai";
            Map<String, Object> item = modeMap.get(mode);
            int count = rs.getInt("c");
            item.put("count", count);
            item.put("avgScore", (int) Math.round(rs.getDouble("a")));
            item.put("share", success == 0 ? 0 : Math.round(count * 100.0f / success));
        });

        int fallbackCount = countOf(
                "SELECT COUNT(*) FROM analysis_task WHERE mode='RULE' AND reason LIKE '%降级%'");

        List<String> missingRows = jdbc.queryForList(
                "SELECT missing_keywords FROM analysis_task WHERE status='SUCCESS' "
                        + "AND missing_keywords IS NOT NULL AND missing_keywords <> ''", String.class);
        Map<String, Integer> keywordCounts = new HashMap<>();
        for (String row : missingRows) {
            for (String keyword : row.split(",")) {
                String k = keyword.trim();
                if (!k.isEmpty()) keywordCounts.merge(k, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> topMissing = keywordCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(8)
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("keyword", e.getKey());
                    item.put("count", e.getValue());
                    return item;
                })
                .toList();

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("aiEnabled", aiProperties.isEnabled());
        system.put("aiUsable", aiProperties.isUsable());
        system.put("aiModel", aiProperties.resolveModel());
        system.put("dbVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
        system.put("storage", "本机 MySQL");
        system.put("tokenStore", "数据库 auth_token（重启后仍有效）");
        system.put("tokenActive", tokenService.activeCount());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("feedback", feedback);
        result.put("modes", new ArrayList<>(modeMap.values()));
        result.put("fallbackCount", fallbackCount);
        result.put("topMissing", topMissing);
        result.put("system", system);
        return result;
    }

    private int countOf(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }
}
