# -*- coding: utf-8 -*-
"""AI 模式联调测试（真实 HTTP 调用链 + 本地 Mock OpenAI 服务）。

验证内容：
1. AI 正常返回 → 任务成功、mode=ai、分数/建议来自模型；
2. 模型返回非 JSON → 任务失败（BR-ANALYSIS-05）；
3. 模型超时 → 任务失败（BR-ANALYSIS-03）；
4. 模型 HTTP 500 → 任务失败；
5. AI 服务不可达 → 自动降级规则模式，任务仍成功且标注原因（BR-ANALYSIS-04）。

运行前提：本机可执行 mvn / java；数据库 ai_resume 已初始化。
用法：python ai_e2e_test.py [--skip-build]
说明：会在 8081（应用）与 8099（Mock AI）启动临时进程，结束后自动关闭。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = "http://127.0.0.1:8081"
MOCK = "http://127.0.0.1:8099"
ACCOUNT = "ai" + str(int(time.time()))[-8:]
LOG_FILE = os.path.join(ROOT, "target", "ai_e2e_server.log")
results = []


def http(method, url, body=None, token=None, timeout=15):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ((" | " + str(detail)) if detail and not cond else ""))


def wait_http(url, timeout=90):
    start = time.time()
    while time.time() - start < timeout:
        try:
            with urllib.request.urlopen(url, timeout=3) as res:
                if res.status == 200:
                    return True
        except Exception:
            time.sleep(1)
    return False


def mock_mode(mode):
    try:
        with urllib.request.urlopen(MOCK + "/control?mode=" + mode, timeout=5) as res:
            return res.status == 200
    except Exception:
        return False


def wait_task(task_id, token, timeout=25):
    for _ in range(timeout * 2):
        _, r = http("GET", BASE + "/api/analyses/" + task_id, token=token)
        task = r.get("data", {})
        if task.get("status") in ("success", "failed"):
            return task
        time.sleep(0.5)
    return {}


def create_analysis(resume_id, job_id, token):
    _, r = http("POST", BASE + "/api/analyses", {"resumeId": resume_id, "jobId": job_id}, token)
    task_id = r.get("data", {}).get("id")
    return task_id, wait_task(task_id, token) if task_id else {}


def main():
    skip_build = "--skip-build" in sys.argv

    jar = os.path.join(ROOT, "target", "ai-resume-server-1.0.0.jar")
    if not skip_build or not os.path.exists(jar):
        print("==> mvn package -DskipTests")
        mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
        if not mvn:
            print("未找到 mvn，无法构建")
            return 2
        subprocess.run([mvn, "-q", "-DskipTests", "package"], cwd=ROOT, check=True)
    if not os.path.exists(jar):
        print("未找到 jar：" + jar)
        return 2
    java = shutil.which("java")
    if not java:
        print("未找到 java")
        return 2

    mock = subprocess.Popen(
        [sys.executable, os.path.join(ROOT, "scripts", "mock_ai_server.py"), "8099"],
        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not wait_http(MOCK + "/health", 15):
        mock.terminate()
        print("Mock AI 服务启动失败")
        return 2

    log = open(LOG_FILE, "w", encoding="utf-8")
    app = subprocess.Popen(
        [java, "-jar", jar, "--server.port=8081",
         "--ai.enabled=true", "--ai.endpoint=" + MOCK + "/v1",
         "--ai.api-key=mock-key", "--ai.model=mock-model", "--ai.timeout-seconds=2"],
        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    try:
        if not wait_http(BASE + "/", 120):
            print("应用启动失败，日志见 " + LOG_FILE)
            return 2

        _, r = http("POST", BASE + "/api/auth/register",
                    {"account": ACCOUNT, "password": "123456", "name": "AI联调测试"})
        if r.get("code") != 0:
            print("注册失败：" + str(r))
            return 2
        _, r = http("POST", BASE + "/api/auth/login", {"account": ACCOUNT, "password": "123456"})
        token = r["data"]["token"]

        resume = {
            "name": "AI 联调简历", "personName": "测试", "target": "Java 后端开发实习生",
            "phone": "", "email": "", "summary": "软件工程本科，熟悉 Java 与 Spring Boot。",
            "education": [{"school": "江南大学", "major": "软件工程", "degree": "本科",
                           "period": "2023-2027", "gpa": ""}],
            "projects": [{"title": "订单系统", "role": "后端", "period": "2025",
                          "desc": "使用 Spring Boot 与 MySQL 实现订单模块。"}],
            "skills": ["Java", "Spring Boot", "MySQL"]
        }
        _, r = http("POST", BASE + "/api/resumes", resume, token)
        check("准备简历", r.get("code") == 0, r)
        resume_id = r["data"]["id"]
        _, r = http("POST", BASE + "/api/jobs",
                    {"title": "Java 后端开发实习生", "company": "Mock 公司",
                     "jd": "负责后端开发，要求熟悉 Java、Spring Boot、MySQL 与 Kafka。",
                     "keywords": ["Java", "Spring Boot", "MySQL", "Kafka"]}, token)
        job_id = r["data"]["id"]

        # 1) AI 正常
        mock_mode("ok")
        _, t = create_analysis(resume_id, job_id, token)
        check("AI-01 正常返回任务成功", t.get("status") == "success" and t.get("mode") == "ai", t)
        check("AI-02 采用模型分数与关键词",
              t.get("score") == 88 and "Kafka" in (t.get("missing") or [])
              and len(t.get("suggestions") or []) >= 2, t)

        # 2) 返回非 JSON
        mock_mode("bad")
        _, t = create_analysis(resume_id, job_id, token)
        check("AI-03 格式异常判失败", t.get("status") == "failed" and "格式" in (t.get("reason") or ""), t)

        # 3) 超时
        mock_mode("slow")
        start = time.time()
        _, t = create_analysis(resume_id, job_id, token)
        check("AI-04 超时判失败", t.get("status") == "failed" and "超时" in (t.get("reason") or ""), t)
        check("AI-05 超时按 2 秒阈值生效", time.time() - start < 15, round(time.time() - start, 1))

        # 4) HTTP 500
        mock_mode("http500")
        _, t = create_analysis(resume_id, job_id, token)
        check("AI-06 接口 500 判失败", t.get("status") == "failed" and "HTTP 500" in (t.get("reason") or ""), t)

        # 5) 服务不可达 → 规则降级
        mock.terminate()
        try:
            mock.wait(timeout=5)
        except Exception:
            pass
        _, t = create_analysis(resume_id, job_id, token)
        check("AI-07 服务不可达自动降级",
              t.get("status") == "success" and t.get("mode") == "rule", t)
        check("AI-08 降级结果带原因与规则分数",
              "降级" in (t.get("reason") or "") and isinstance(t.get("score"), int), t)

        print("\n==== %d/%d 通过 ====" % (sum(1 for _, ok in results if ok), len(results)))
        failed = [n for n, ok in results if not ok]
        if failed:
            print("未通过：" + "; ".join(failed))
        return 0 if not failed else 1
    finally:
        for proc in (app, mock):
            if proc and proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=8)
                except Exception:
                    proc.kill()
        log.close()
        print("日志：" + LOG_FILE)


if __name__ == "__main__":
    raise SystemExit(main())
