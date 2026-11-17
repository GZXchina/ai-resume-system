#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
模型效果评测（v1.7）：
  1) 评分一致性 —— 同一用例对生产模型重复采样，统计均值/标准差/极差/一致率；
  2) 建议质量 —— 结构合法率、条数、缺失关键词提及率、动作词覆盖、重复度、长度合规 6 项客观指标；
  3) 多模型对比 —— 多个模型跑同一批用例，比较分数、耗时、token、失败率与质量分。

用法：
  python model_eval.py                       # 默认 4 个候选模型 × 3 用例，一致性采样 3 次
  python model_eval.py --models "cn:a,cn:b"  # 指定模型
  python model_eval.py --runs 5              # 一致性采样次数
  python model_eval.py --list                # 仅列出可用模型

依赖：仅 Python 标准库；需环境变量 AI_API_KEY（AI_ENDPOINT 可选，默认内网网关）。
输出：scripts/eval_results/model_eval_<时间戳>.json（结论汇总到 docs/模型评测报告-AI简历优化系统.md）。
"""
import argparse
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
PROJECT_ROOT = ROOT.parent
RESULT_DIR = ROOT / "scripts" / "eval_results"
KEY = os.environ.get("AI_API_KEY", "")
BASE = os.environ.get("AI_ENDPOINT", "http://10.50.79.225:7864/v1").rstrip("/")

# 与 AiClient.buildPrompt / SYSTEM_PROMPT 保持一致；脚本启动时做一致性守卫
SYSTEM_PROMPT = (
    "你是简历优化助手。只输出 JSON，不要输出任何其他文字。JSON 结构："
    '{"score":0-100整数,"missing":["缺失关键词"],'
    '"suggestions":[{"module":"模块名","level":"high|medium|low","text":"建议内容"}]}'
)

ACTION_WORDS = ["补充", "添加", "改为", "改写", "量化", "突出", "增加", "完善", "调整",
                "优化", "明确", "列出", "加强", "加入", "替换", "精简", "展开", "说明",
                "补充说明", "重写", "强调", "合并", "细化", "提现", "体现"]

# ---------------- 测试用例（覆盖：高匹配 / 部分匹配 / 跨领域低匹配） ----------------
CASES = [
    {
        "id": "case1",
        "label": "Java 后端 · 高匹配",
        "jd": "负责电商订单系统后端开发。要求：熟悉 Java、Spring Boot、MySQL、Redis，"
              "了解消息队列与分布式事务，具备高并发场景优化经验。",
        "keywords": ["Java", "Spring Boot", "MySQL", "Redis", "消息队列"],
        "resume": "陈明远，软件工程本科。技能：Java、Spring Boot、MySQL、Redis、Docker。"
                  "项目一：在线考试系统，负责题库与组卷模块，使用 Spring Boot 与 MySQL，"
                  "QPS 峰值 300，接口平均耗时 120ms。项目二：校园二手交易平台，"
                  "负责订单模块，引入 Redis 缓存热点数据。",
    },
    {
        "id": "case2",
        "label": "前端方向 · 部分匹配",
        "jd": "负责数据可视化大屏前端开发。要求：熟练掌握 Vue3、TypeScript、ECharts，"
              "熟悉 CSS 动画与性能优化，了解 Webpack/Vite 构建。",
        "keywords": ["Vue3", "TypeScript", "ECharts", "CSS 动画", "Vite"],
        "resume": "李思远，计算机科学与技术本科。技能：JavaScript、Vue、CSS、HTML、Node.js。"
                  "项目：社团活动管理小程序，负责活动列表与报名表单页面，"
                  "使用 Vue 组件化开发，完成移动端适配。",
    },
    {
        "id": "case3",
        "label": "数据方向 · 跨领域低匹配",
        "jd": "负责用户增长数据分析。要求：精通 Python 与 SQL，熟练使用 Hive/Spark 做离线分析，"
              "掌握 A/B 实验设计与埋点体系，有 Tableau 或 Superset 报表经验。",
        "keywords": ["Python", "SQL", "Hive", "A/B 实验", "Tableau"],
        "resume": "王远航，软件工程本科。技能：Java、Spring Boot、MySQL、Linux。"
                  "项目：图书管理系统，负责借阅流程后端接口，使用 Spring Boot 与 MySQL，"
                  "编写过常用查询 SQL 与统计页面。",
    },
]

DEFAULT_MODELS = ["cn:deepseek-v4.1-flash", "cn:glm-5.3-flash", "cn:kimi-k2.5", "cn:hy4-preview"]
PROBE_TIMEOUT = 60


def check_prompt_consistency():
    """守卫：确认评测 prompt 与 AiClient.java 中的生产 prompt 一致，避免评错对象。"""
    src = (ROOT / "src" / "main" / "java" / "cn" / "course" / "airesume" / "analysis" / "AiClient.java").read_text(encoding="utf-8")
    checks = [
        ("你是简历优化助手。只输出 JSON", "SYSTEM_PROMPT"),
        ("【岗位 JD】", "buildPrompt"),
        ("【JD 关键词】", "buildPrompt"),
        ("【简历内容】", "buildPrompt"),
        ("score 为匹配度（0-100）", "buildPrompt"),
        ("suggestions 按模块给出 2-5 条可执行修改建议", "buildPrompt"),
    ]
    missing = [name for frag, name in checks if frag not in src]
    return missing


def build_user_prompt(case):
    return ("请分析以下简历与岗位 JD 的匹配程度。\n\n【岗位 JD】\n" + case["jd"]
            + "\n\n【JD 关键词】\n" + "、".join(case["keywords"])
            + "\n\n【简历内容】\n" + case["resume"]
            + "\n\n要求：score 为匹配度（0-100）；missing 列出 JD 关键词中简历未覆盖的项；"
              "suggestions 按模块给出 2-5 条可执行修改建议。")


def call_model(model, case, timeout=90):
    """调用一次分析；返回 dict(ok, score, missing, suggestions, seconds, usage, error)。"""
    body = {
        "model": model,
        "temperature": 0.3,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(case)},
        ],
    }
    req = urllib.request.Request(BASE + "/chat/completions",
                                 data=json.dumps(body).encode(), method="POST")
    req.add_header("Authorization", "Bearer " + KEY)
    req.add_header("Content-Type", "application/json")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode())
        seconds = round(time.time() - t0, 2)
        content = d["choices"][0]["message"]["content"]
        content = re.sub(r"(?s)```json\s*", "", content).replace("```", "").strip()
        obj = json.loads(content)
        if "score" not in obj or "suggestions" not in obj:
            return {"ok": False, "error": "missing score/suggestions", "seconds": seconds}
        sugg = [s for s in obj.get("suggestions", []) if s.get("text")]
        if not sugg:
            return {"ok": False, "error": "no valid suggestions", "seconds": seconds}
        return {
            "ok": True, "score": int(obj["score"]), "seconds": seconds,
            "missing": [str(m) for m in obj.get("missing", [])],
            "suggestions": sugg,
            "usage": {k: d.get("usage", {}).get(k) for k in ("prompt_tokens", "completion_tokens", "total_tokens")},
        }
    except urllib.error.HTTPError as e:
        return {"ok": False, "error": f"HTTP {e.code}: {e.read().decode('utf-8', 'ignore')[:150]}", "seconds": round(time.time() - t0, 2)}
    except Exception as e:
        return {"ok": False, "error": f"{type(e).__name__}: {e}", "seconds": round(time.time() - t0, 2)}


# ---------------- 建议质量指标 ----------------

def bigrams(t):
    return {t[i:i + 2] for i in range(len(t) - 1)}


def jaccard(a, b):
    A, B = bigrams(a), bigrams(b)
    return len(A & B) / max(1, len(A | B))


def expected_missing(case):
    """用例的期望缺失关键词：JD 关键词中未出现在简历原文里的项。"""
    text = case["resume"].lower()
    return [k for k in case["keywords"] if k.lower() not in text]


def quality_metrics(run, case):
    sugg = run.get("suggestions", [])
    n = max(1, len(sugg))
    all_text = " ".join(s.get("text", "") for s in sugg)
    struct_ok = all(s.get("module") and s.get("level") in ("high", "medium", "low") and s.get("text") for s in sugg)
    count_ok = 2 <= len(sugg) <= 5
    exp_missing = expected_missing(case)
    mentioned = [k for k in exp_missing if k in all_text]
    cover = len(mentioned) / max(1, len(exp_missing))
    action = sum(1 for s in sugg if any(w in s.get("text", "") for w in ACTION_WORDS)) / n
    len_ok = sum(1 for s in sugg if 10 <= len(s.get("text", "")) <= 300) / n
    sims = [jaccard(sugg[i]["text"], sugg[j]["text"]) for i in range(len(sugg)) for j in range(i + 1, len(sugg))]
    max_sim = max(sims) if sims else 0.0
    return {
        "structOk": struct_ok, "countOk": count_ok, "suggestionCount": len(sugg),
        "expectedMissing": exp_missing, "missingMentioned": mentioned,
        "keywordCover": round(cover, 3), "actionRatio": round(action, 3),
        "lenOkRatio": round(len_ok, 3), "maxDuplicate": round(max_sim, 3),
    }


def score_of_metrics(m):
    """质量综合分（0-100）：六项指标加权，权重合计 1。"""
    parts = [
        (1.0 if m["structOk"] else 0.0, 0.20),
        (1.0 if m["countOk"] else 0.0, 0.15),
        (m["keywordCover"], 0.25),
        (m["actionRatio"], 0.20),
        (m["lenOkRatio"], 0.10),
        (1.0 - m["maxDuplicate"], 0.10),
    ]
    return round(sum(v * w for v, w in parts) * 100)


# ---------------- 主流程 ----------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", default=",".join(DEFAULT_MODELS))
    ap.add_argument("--runs", type=int, default=3, help="一致性采样次数")
    ap.add_argument("--list", action="store_true", help="仅列出可用模型")
    args = ap.parse_args()

    if not KEY:
        print("缺少 AI_API_KEY 环境变量"); sys.exit(1)

    miss = check_prompt_consistency()
    if miss:
        print(f"[警告] 评测 prompt 与 AiClient.java 不一致的部分：{miss}，结果不能代表生产行为")
        sys.exit(2)
    print("[OK] prompt 与 AiClient.java 一致")

    models = [m.strip() for m in args.models.split(",") if m.strip()]

    if args.list:
        req = urllib.request.Request(BASE + "/models")
        req.add_header("Authorization", "Bearer " + KEY)
        with urllib.request.urlopen(req, timeout=30) as r:
            d = json.loads(r.read().decode())
        for m in d["data"]:
            print(f"{m['id']:<42} {m.get('name',''):<16} {m.get('credits','')}")
        return

    # 1) 探测模型可用性（便宜的一次调用，case1）
    print("\n== 1/3 探测模型可用性 ==")
    usable = []
    probe = {}
    for m in models:
        r = call_model(m, CASES[0], timeout=PROBE_TIMEOUT)
        probe[m] = {"ok": r["ok"], "error": r.get("error", ""), "seconds": r.get("seconds")}
        print(f"  {m:<28} {'可用' if r['ok'] else '不可用: ' + r.get('error','')[:80]}"
              + (f"（{r['seconds']}s，score={r['score']}）" if r["ok"] else ""))
        if r["ok"]:
            usable.append(m)
    if not usable:
        print("没有可用模型，终止"); sys.exit(1)

    # 2) 评分一致性：生产模型（第一个可用）× case1 × N 次
    prod = usable[0]
    print(f"\n== 2/3 评分一致性（{prod} × case1 × {args.runs} 次）==")
    runs = []
    for i in range(args.runs):
        r = call_model(prod, CASES[0])
        runs.append(r)
        if r["ok"]:
            print(f"  第{i + 1}次：score={r['score']} {r['seconds']}s")
        else:
            print(f"  第{i + 1}次：失败 {r.get('error','')[:60]}")
        time.sleep(1)
    ok_scores = [r["score"] for r in runs if r["ok"]]
    consistency = {
        "model": prod, "case": "case1", "runs": args.runs,
        "okCount": len(ok_scores),
        "scores": ok_scores,
        "mean": round(statistics.mean(ok_scores), 1) if ok_scores else None,
        "stdev": round(statistics.stdev(ok_scores), 2) if len(ok_scores) > 1 else 0,
        "range": (max(ok_scores) - min(ok_scores)) if ok_scores else None,
        "consistentWithin10": (max(ok_scores) - min(ok_scores)) <= 10 if ok_scores else None,
        "avgSeconds": round(statistics.mean([r["seconds"] for r in runs if r["ok"]]), 2) if ok_scores else None,
        "failures": [r.get("error") for r in runs if not r["ok"]],
    }
    print(f"  均值 {consistency['mean']} · 标准差 {consistency['stdev']} · "
          f"极差 {consistency['range']} · 极差≤10: {consistency['consistentWithin10']}")

    # 3) 多模型对比：可用模型 × 3 用例 × 1 次
    print(f"\n== 3/3 多模型对比（{len(usable)} 模型 × {len(CASES)} 用例）==")
    comparison = []
    for m in usable:
        rows = []
        for c in CASES:
            r = call_model(m, c)
            q = quality_metrics(r, c) if r["ok"] else None
            rows.append({"case": c["id"], "label": c["label"], "run": r, "quality": q})
            state = f"score={r['score']} {r['seconds']}s 质量分={score_of_metrics(q) if q else '—'}" if r["ok"] else "失败"
            print(f"  {m:<28} {c['id']} {state}")
            time.sleep(1)
        ok_rows = [x for x in rows if x["run"]["ok"]]
        qs = [score_of_metrics(x["quality"]) for x in ok_rows]
        comparison.append({
            "model": m,
            "okCount": len(ok_rows), "total": len(rows),
            "failRate": round(1 - len(ok_rows) / len(rows), 3),
            "avgScore": round(statistics.mean([x["run"]["score"] for x in ok_rows]), 1) if ok_rows else None,
            "scoreSpread": [x["run"]["score"] for x in ok_rows],
            "avgSeconds": round(statistics.mean([x["run"]["seconds"] for x in ok_rows]), 2) if ok_rows else None,
            "avgTokens": round(statistics.mean([x["run"].get("usage", {}).get("total_tokens") or 0 for x in ok_rows])) if ok_rows else None,
            "avgQuality": round(statistics.mean(qs), 1) if qs else None,
            "missingHit": round(statistics.mean([x["quality"]["keywordCover"] for x in ok_rows]), 3) if ok_rows else None,
            "cases": [{"case": x["case"], "score": x["run"].get("score"), "quality": x["quality"],
                       "suggestions": x["run"].get("suggestions")} for x in rows],
        })
        print(f"  -> {m}: 均分 {comparison[-1]['avgScore']} · 平均耗时 {comparison[-1]['avgSeconds']}s · "
              f"质量分 {comparison[-1]['avgQuality']} · 失败率 {comparison[-1]['failRate']}")

    # 输出 JSON
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    result = {
        "timestamp": datetime.now().isoformat(), "endpoint": BASE,
        "promptConsistent": not miss, "probe": probe,
        "consistency": consistency, "comparison": comparison,
        "cases": [{k: v for k, v in c.items()} for c in CASES],
        "metricDefinition": {
            "structOk": "suggestions 每条含 module/level/text 且 level 合法",
            "countOk": "建议条数 2~5",
            "keywordCover": "建议文本中提到的「期望缺失关键词」比例（期望缺失 = JD 关键词中未出现在简历原文的项）",
            "actionRatio": "含可执行动作词的建议比例",
            "maxDuplicate": "建议两两字符二元组 Jaccard 相似度最大值（越低越好）",
            "lenOkRatio": "建议长度 10~300 字的比例",
            "qualityScore": "以上六项加权（0.20/0.15/0.25/0.20/0.10/0.10）×100",
        },
    }
    out_json = RESULT_DIR / f"model_eval_{ts}.json"
    out_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[OK] 原始数据已写入 {out_json}")


if __name__ == "__main__":
    main()
