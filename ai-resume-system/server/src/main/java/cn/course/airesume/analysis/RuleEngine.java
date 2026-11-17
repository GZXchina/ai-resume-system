package cn.course.airesume.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 规则模式分析器（BR-ANALYSIS-04）：
 * 对 JD 关键词与简历技能/描述做交集与差集，输出匹配度、缺失关键词与模板化建议。
 * 不依赖任何外部服务，保证 AI 不可用时核心流程仍可完成。
 */
@Component
public class RuleEngine {

    public record RuleResult(int score, List<String> missing, List<Map<String, String>> suggestions) {}

    /** resumeText：summary + skills + 项目描述拼接；keywords：JD 关键词列表。 */
    public RuleResult compute(String resumeText, List<String> keywords) {
        String text = resumeText == null ? "" : resumeText.toLowerCase();
        List<String> hits = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String k : keywords) {
            if (k == null || k.isBlank()) continue;
            if (text.contains(k.trim().toLowerCase())) hits.add(k.trim());
            else missing.add(k.trim());
        }
        int score = (int) Math.round(50 + (keywords.isEmpty() ? 0.5 : (double) hits.size() / keywords.size()) * 40 - 7);
        score = Math.max(43, Math.min(91, score));
        return new RuleResult(score, missing, buildSuggestions(missing));
    }

    private List<Map<String, String>> buildSuggestions(List<String> missing) {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(suggestion("分析方式", "medium",
                "本次为规则模式：仅对 JD 关键词与简历技能/描述做交集比对，未调用大模型；结果仅供参考，服务恢复后可重新分析。"));
        if (!missing.isEmpty()) {
            list.add(suggestion("技能清单", "high",
                    "JD 中的「" + String.join("、", missing.subList(0, Math.min(3, missing.size())))
                            + "」未在简历中出现，建议核实是否具备，并在技能清单或项目描述中体现。"));
        }
        list.add(suggestion("项目经历", "high",
                "项目描述建议按「背景—职责—技术方案—结果」组织，并补充接口耗时、QPS 或数据量等可量化结果，避免只罗列功能。"));
        list.add(suggestion("个人信息", "low",
                "建议补充可实习周期、每周到岗天数与期望城市，方便 HR 安排面试。"));
        return list;
    }

    private static Map<String, String> suggestion(String module, String level, String text) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("module", module);
        m.put("level", level);
        m.put("text", text);
        return m;
    }

    /** 岗位未填写关键词时，从 JD 中抽取中英文技术词作为兜底。 */
    public static List<String> extractKeywords(String jd) {
        List<String> result = new ArrayList<>();
        if (jd == null) return result;
        var matcher = Pattern.compile("[A-Za-z][A-Za-z0-9+#.\\-]{1,}").matcher(jd);
        while (matcher.find() && result.size() < 12) {
            String w = matcher.group();
            if (!result.contains(w)) result.add(w);
        }
        return result;
    }
}
