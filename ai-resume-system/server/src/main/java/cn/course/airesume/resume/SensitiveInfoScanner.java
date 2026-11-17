package cn.course.airesume.resume;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 敏感信息脱敏提醒（FR-RESUME-03）。
 * 扫描简历中的手机号、身份证号、银行卡号，只返回「类型 + 字段 + 脱敏示例 + 建议」，
 * 不回传完整号码，也不阻断保存；命中结果随简历详情一起返回给前端提示。
 */
@Component
public class SensitiveInfoScanner {

    public record Finding(String type, String field, String masked, String advice) {}

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern CANDIDATE = Pattern.compile("(?<![0-9A-Za-z])(?:\\d{17}[0-9Xx]|\\d{16,19})(?![0-9A-Za-z])");
    private static final DateTimeFormatter BIRTH = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    /** 扫描一份简历；field 用于告诉学生问题出现在哪一块。 */
    public List<Finding> scan(String phone, String summary, List<ResumeDao.Project> projects) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        scanText("联系电话", phone, findings, seen);
        scanText("个人简介", summary, findings, seen);
        if (projects != null) {
            for (ResumeDao.Project p : projects) {
                String label = p.title() == null || p.title().isBlank()
                        ? "项目/实习经历" : "项目经历「" + p.title().trim() + "」";
                scanText(label, p.description(), findings, seen);
            }
        }
        return findings;
    }

    private void scanText(String field, String text, List<Finding> out, Map<String, Boolean> seen) {
        if (text == null || text.isBlank()) return;

        Matcher pm = PHONE.matcher(text);
        while (pm.find()) {
            String v = pm.group();
            add(out, seen, new Finding("手机号", field, v.substring(0, 3) + "****" + v.substring(7),
                    "公开投递的简历建议使用「138****8888」形式，完整号码在面试沟通时再提供。"));
        }

        Matcher cm = CANDIDATE.matcher(text);
        while (cm.find()) {
            String v = cm.group();
            if (v.length() == 18 && looksLikeIdCard(v)) {
                add(out, seen, new Finding("身份证号", field,
                        v.substring(0, 6) + "********" + v.substring(14),
                        "简历中不建议出现完整身份证号，建议删除或只保留后四位。"));
            } else if (v.length() >= 16 && v.length() <= 19) {
                add(out, seen, new Finding("银行卡号", field,
                        v.substring(0, 4) + " **** **** " + v.substring(v.length() - 4),
                        "简历中不建议出现银行卡号，建议删除；入职材料再单独提供。"));
            }
        }
    }

    private static void add(List<Finding> out, Map<String, Boolean> seen, Finding f) {
        String key = f.type() + "|" + f.masked();
        if (seen.putIfAbsent(key, Boolean.TRUE) == null) out.add(f);
    }

    /** 18 位号码是否像身份证：省级代码 11~82 且第 7~14 位为合法生日。 */
    private static boolean looksLikeIdCard(String v) {
        try {
            int province = Integer.parseInt(v.substring(0, 2));
            if (province < 11 || province > 82) return false;
            LocalDate.parse(v.substring(6, 14), BIRTH);
            return true;
        } catch (NumberFormatException | DateTimeParseException e) {
            return false;
        }
    }
}
