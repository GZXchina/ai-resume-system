# -*- coding: utf-8 -*-
"""真实 AI 供应商端到端验证（使用环境变量中的密钥，不写入仓库）。

环境变量：
    AI_API_KEY   必填，供应商密钥
    AI_ENDPOINT  可选，默认 http://10.50.79.225:7864/v1
    AI_MODEL     可选，默认 cn:deepseek-v4.1-flash

用法：
    python ai_real_test.py [--skip-build]

流程：打包 jar → 启动 8081 应用（profile=ai）→ 注册测试账号 → 创建简历/岗位 →
发起分析 → 轮询结果 → 校验成功、mode=ai、分数/建议有效 → 关闭进程。
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
ACCOUNT = "real" + str(int(time.time()))[-8:]
LOG_FILE = os.path.join(ROOT, "target", "ai_real_server.log")
results = []


def http(method, url, body=None, token=None, timeout=30):
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


def wait_http(url, timeout=120):
    start = time.time()
    while time.time() - start < timeout:
        try:
            with urllib.request.urlopen(url, timeout=3) as res:
                if res.status == 200:
                    return True
        except Exception:
            time.sleep(1)
    return False


def wait_task(task_id, token, timeout=90):
    for _ in range(timeout * 2):
        _, r = http("GET", BASE + "/api/analyses/" + task_id, token=token)
        task = r.get("data", {})
        if task.get("status") in ("success", "failed"):
            return task
        time.sleep(0.5)
    return {}


def main():
    api_key = os.environ.get("AI_API_KEY", "").strip()
    endpoint = os.environ.get("AI_ENDPOINT", "http://10.50.79.225:7864/v1").strip()
    model = os.environ.get("AI_MODEL", "cn:deepseek-v4.1-flash").strip()
    if not api_key:
        print("缺少环境变量 AI_API_KEY，已中止（不会读取仓库内密钥）")
        return 2

    skip_build = "--skip-build" in sys.argv
    jar = os.path.join(ROOT, "target", "ai-resume-server-1.0.0.jar")
    if not skip_build or not os.path.exists(jar):
        print("==> mvn package -DskipTests")
        mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
        if not mvn:
            print("未找到 mvn")
            return 2
        subprocess.run([mvn, "-q", "-DskipTests", "package"], cwd=ROOT, check=True)
    java = shutil.which("java")
    if not java:
        print("未找到 java")
        return 2

    print(f"==> endpoint={endpoint} model={model}")
    env = dict(os.environ)
    env["AI_API_KEY"] = api_key
    log = open(LOG_FILE, "w", encoding="utf-8")
    app = subprocess.Popen(
        [java, "-jar", jar, "--server.port=8081", "--spring.profiles.active=ai",
         "--ai.endpoint=" + endpoint, "--ai.model=" + model],
        cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        if not wait_http(BASE + "/", 120):
            print("应用启动失败，日志见 " + LOG_FILE)
            return 2

        _, r = http("POST", BASE + "/api/auth/register",
                    {"account": ACCOUNT, "password": "123456", "name": "真实AI测试"})
        if r.get("code") != 0:
            print("注册失败：" + str(r))
            return 2
        _, r = http("POST", BASE + "/api/auth/login", {"account": ACCOUNT, "password": "123456"})
        token = r["data"]["token"]

        resume = {
            "name": "真实 AI 联调简历", "personName": "测试", "target": "Java 后端开发实习生",
            "phone": "", "email": "",
            "summary": "软件工程本科，熟悉 Java 与 Spring Boot，参与订单系统开发。",
            "education": [{"school": "江南大学", "major": "软件工程", "degree": "本科",
                           "period": "2023-2027", "gpa": ""}],
            "projects": [{"title": "订单系统", "role": "后端", "period": "2025",
                          "desc": "使用 Spring Boot 与 MySQL 实现订单模块，接口平均响应 300ms。"}],
            "skills": ["Java", "Spring Boot", "MySQL"]
        }
        _, r = http("POST", BASE + "/api/resumes", resume, token)
        check("准备简历", r.get("code") == 0, r)
        resume_id = r["data"]["id"]
        _, r = http("POST", BASE + "/api/jobs",
                    {"title": "Java 后端开发实习生", "company": "真实 AI 公司",
                     "jd": "负责后端开发，要求熟悉 Java、Spring Boot、MySQL、Redis 与消息队列。",
                     "keywords": ["Java", "Spring Boot", "MySQL", "Redis", "消息队列"]}, token)
        job_id = r["data"]["id"]

        start = time.time()
        _, r = http("POST", BASE + "/api/analyses", {"resumeId": resume_id, "jobId": job_id}, token)
        task_id = r.get("data", {}).get("id")
        check("创建分析任务", r.get("code") == 0 and bool(task_id), r)
        task = wait_task(task_id, token) if task_id else {}
        elapsed = round(time.time() - start, 1)

        check("真实 AI 任务成功", task.get("status") == "success" and task.get("mode") == "ai", task)
        score = task.get("score")
        check("返回有效匹配度", isinstance(score, int) and 0 <= score <= 100, score)
        suggestions = task.get("suggestions") or []
        check("返回结构化建议",
              len(suggestions) >= 2 and all(s.get("module") and s.get("text") for s in suggestions),
              suggestions)
        check("分析耗时在阈值内", elapsed < 30, elapsed)
        check("未走降级路径", not task.get("reason"), task.get("reason"))

        print(f"\n结果：score={score}，missing={task.get('missing')}，"
              f"suggestions={len(suggestions)} 条，耗时={task.get('duration')}s（轮询 {elapsed}s）")
        if suggestions:
            print("首条建议：" + suggestions[0].get("text", "")[:120])

        print("\n==== %d/%d 通过 ====" % (sum(1 for _, ok in results if ok), len(results)))
        failed = [n for n, ok in results if not ok]
        if failed:
            print("未通过：" + "; ".join(failed))
        return 0 if not failed else 1
    finally:
        if app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=8)
            except Exception:
                app.kill()
        log.close()
        print("日志：" + LOG_FILE)


if __name__ == "__main__":
    raise SystemExit(main())
