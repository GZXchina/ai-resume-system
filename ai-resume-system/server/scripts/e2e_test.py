# -*- coding: utf-8 -*-
"""AI 简历优化系统 端到端验收测试（对应需求文档 AC-USER/RESUME/JOB/ANALYSIS/VERSION/ADMIN/STAT）。
运行前提：服务已在 http://localhost:8080 启动，数据库已导入种子数据。
用法：python e2e_test.py
"""
import json
import time
import io
import threading
import zipfile
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, quote
from xml.sax.saxutils import escape
from pathlib import Path

BASE = "http://localhost:8080"
ACCOUNT = "e2e" + str(int(time.time()))[-8:]  # 每次运行使用唯一账号，保证可重复执行
results = []


def call(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=40) as res:
            return res.status, json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ((" | " + str(detail)) if detail and not cond else ""))


def raw(path):
    with urllib.request.urlopen(BASE + path, timeout=20) as res:
        return res.status, res.read().decode("utf-8")


def upload(filename, content, token=None):
    """构造 multipart/form-data 上传文件（FR-RESUME-04 测试用）。"""
    boundary = "----e2e" + str(int(time.time() * 1000))
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode("utf-8")
    body = head + content + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/api/resumes/import", data=body, method="POST")
    req.add_header("Content-Type", "multipart/form-data; boundary=" + boundary)
    if token:
        req.add_header("X-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=40) as res:
            return res.status, json.loads(res.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}


def make_docx(paras):
    xml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
           + "".join(f"<w:p><w:r><w:t>{escape(p)}</w:t></w:r></w:p>" for p in paras)
           + "</w:body></w:document>")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("word/document.xml", xml)
    return buf.getvalue()


def make_pdf(lines):
    """生成只含 Helvetica 文本的最小合法 PDF（无需第三方依赖）。"""
    def esc(s):
        return s.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")

    ops = ["BT", "/F1 12 Tf", "72 720 Td"]
    for i, line in enumerate(lines):
        if i:
            ops.append("0 -20 Td")
        ops.append(f"({esc(line)}) Tj")
    ops.append("ET")
    stream = ("\n".join(ops)).encode("latin-1")
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, obj in enumerate(objs, 1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 6\n0000000000 65535 f \n"
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + str(xref).encode() + b"\n%%EOF\n"
    return bytes(out)


def wait_done(task_id, token, timeout=40):
    for _ in range(timeout * 2):
        _, r = call("GET", "/api/analyses/" + task_id, token=token)
        t = r.get("data", {})
        if t.get("status") in ("success", "failed"):
            return t
        time.sleep(0.5)
    return {}


MOCK_PORT = 8099
MOCK_BASE = "http://127.0.0.1:%d" % MOCK_PORT
_flaky_count = {"n": 0}


class MockTarget(BaseHTTPRequestHandler):
    """自定义 API 调用用例的目标服务：覆盖 200 / 404 / 500 重试 / 慢响应 / 大响应。"""

    def log_message(self, *args):
        pass

    def _send(self, status, payload, content_type="application/json; charset=UTF-8"):
        body = payload if isinstance(payload, bytes) else payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionAbortedError):
            pass  # 客户端已超时断开

    def _handle(self):
        path = urlparse(self.path).path
        query = {k: v[0] if len(v) == 1 else v for k, v in parse_qs(urlparse(self.path).query).items()}
        if path == "/echo":
            self._send(200, json.dumps({
                "method": self.command,
                "path": path,
                "query": query,
                "authorization": self.headers.get("Authorization"),
                "contentType": self.headers.get("Content-Type"),
                "body": self._read_body(),
            }, ensure_ascii=False))
        elif path == "/flaky":
            _flaky_count["n"] += 1
            self._send(500 if _flaky_count["n"] < 3 else 200,
                       json.dumps({"attempt": _flaky_count["n"]}))
        elif path == "/slow":
            time.sleep(2)
            self._send(200, "slow ok", "text/plain; charset=UTF-8")
        elif path == "/big":
            self._send(200, b"x" * 80000, "text/plain; charset=UTF-8")
        else:
            self._send(404, json.dumps({"error": "not found"}))

    def _read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length).decode("utf-8") if length else ""

    do_GET = _handle
    do_POST = _handle
    do_PUT = _handle
    do_DELETE = _handle


def start_mock():
    """启动目标服务；端口被占用时返回 None，用例按 SKIP 处理。"""
    try:
        srv = HTTPServer(("127.0.0.1", MOCK_PORT), MockTarget)
    except OSError as e:
        print("SKIP 自定义 API 调用用例：%d 端口不可用（%s）" % (MOCK_PORT, e))
        return None
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def main():
    # ---------- 前端接入检查 ----------
    status, js = raw("/api.js")
    check("前端 API 层已对接后端（fetch，无本地桩）",
          status == 200 and "fetch(" in js and "Store.db" not in js)
    status, html = raw("/")
    check("首页可访问且加载 api.js", status == 200 and "api.js" in html)
    status, compare_page = raw("/student/compare.html")
    check("简历对比页面可访问", status == 200 and "简历对比" in compare_page and "api.js" in compare_page)
    check("前端 API 含导入与对比方法", "importFile" in js and "resumes/compare" in js)
    status, console_page = raw("/student/api-console.html")
    check("API 控制台页面可访问且加载 api.js",
          status == 200 and "API 控制台" in console_page and "api.js" in console_page)
    check("前端 API 含自定义 API 调用方法",
          "invokeByName" in js and "user-apis" in js and "syncPending" in js)

    # ---------- AC-USER-01 正常登录 ----------
    _, r = call("POST", "/api/auth/login", {"account": "linzhiyuan", "password": "123456"})
    check("AC-USER-01 学生正常登录", r.get("code") == 0 and r["data"]["role"] == "student")
    student = r["data"]["token"]

    _, r = call("POST", "/api/auth/login", {"account": "linzhiyuan", "password": "wrong"})
    check("登录：错误密码统一提示", r.get("code") == 1 and r.get("message") == "账号或密码错误", r)

    _, r = call("POST", "/api/auth/login", {"account": "heyutong", "password": "123456"})
    check("AC-USER-03 禁用账号无法登录", r.get("code") == 1 and "禁用" in r.get("message", ""), r)

    # ---------- BR-USER-01 未登录拦截 ----------
    status, r = call("GET", "/api/resumes")
    check("BR-USER-01 未登录返回 401", status == 401 and r.get("code") == 1)

    # ---------- 注册 ----------
    _, r = call("POST", "/api/auth/register", {"account": ACCOUNT, "password": "123456", "name": "端到端测试"})
    check("FR-USER-01 注册新账号", r.get("code") == 0, r)
    _, r = call("POST", "/api/auth/register", {"account": ACCOUNT, "password": "123456", "name": "重复"})
    check("FR-USER-01 账号唯一性", r.get("code") == 1 and r.get("message") == "账号已存在", r)
    _, r = call("POST", "/api/auth/register", {"account": "ab", "password": "123456", "name": "短账号"})
    check("FR-USER-01 账号长度校验", r.get("code") == 1, r)

    _, r = call("POST", "/api/auth/login", {"account": ACCOUNT, "password": "123456"})
    token = r["data"]["token"]
    new_uid = r["data"]["id"]

    # ---------- AC-RESUME-01/02 简历 ----------
    bad = {"name": "测试简历", "personName": "测试", "target": "", "summary": "", "skills": [],
           "education": [{"school": "江南大学", "major": "软件工程", "degree": "本科", "period": "2023-2027", "gpa": ""}],
           "projects": [{"title": "二手平台", "role": "后端", "period": "2025", "desc": "使用 Spring Boot 实现订单模块"}]}
    _, r = call("POST", "/api/resumes", bad, token)
    check("AC-RESUME-01 求职意向为空拒绝保存", r.get("code") == 1 and "求职意向" in r.get("message", ""), r)

    good = dict(bad, target="Java 后端开发实习生", summary="软件工程本科在读，熟悉 Java 与 Spring Boot，寻找后端实习。",
                skills=["Java", "Spring Boot", "MySQL", "Git"])
    _, r = call("POST", "/api/resumes", good, token)
    check("AC-RESUME-02 简历保存成功", r.get("code") == 0, r)
    resume_id = r["data"]["id"]
    check("新建简历完整度>0", r["data"]["completeness"] > 0)

    dup = dict(good, name="重复技能简历", skills=["Java", "Java ", "java", "MySQL"])
    _, r = call("POST", "/api/resumes", dup, token)
    saved_skills = [s.lower() for s in r.get("data", {}).get("skills", [])]
    check("DR-007 技能大小写不敏感去重",
          r.get("code") == 0 and saved_skills == ["java", "mysql"], r)

    _, r = call("GET", "/api/resumes", token=token)
    check("FR-RESUME-02 列表含版本数", r["data"][0]["versionCount"] == 1, r)

    # ---------- AC-USER-02 越权 ----------
    _, r = call("GET", "/api/resumes/1", token=token)  # 简历 1 属于 linzhiyuan
    check("AC-USER-02 越权访问他人简历被拒绝", r.get("code") == 1 and "无权" in r.get("message", ""), r)
    _, r = call("GET", "/api/resumes/1", token=student)
    check("本人可访问本人简历", r.get("code") == 0)

    # ---------- AC-JOB-01 岗位 ----------
    _, r = call("POST", "/api/jobs", {"title": "", "jd": "x" * 60}, token)
    check("岗位名称为空拒绝", r.get("code") == 1 and "岗位名称" in r.get("message", ""), r)
    _, r = call("POST", "/api/jobs", {"title": "Java 后端开发实习生", "company": "测试公司",
                                      "jd": "负责后端服务开发，要求熟悉 Java、Spring Boot、MySQL 与 Redis，了解消息队列。",
                                      "keywords": ["Java", "Spring Boot", "MySQL", "Redis", "消息队列"]}, token)
    check("AC-JOB-01 岗位保存成功", r.get("code") == 0, r)
    job_id = r["data"]["id"]

    # ---------- AC-ANALYSIS-01/04 规则模式分析 ----------
    # 固定 demo.mode=rule：无论服务是否启用 AI，本脚本都走规则路径，保证结果可重复；
    # AI 成功/失败路径由 ai_e2e_test.py（Mock）与 ai_real_test.py（真实供应商）覆盖。
    _, r = call("POST", "/api/analyses", {"resumeId": resume_id, "jobId": job_id, "demo": {"mode": "rule"}}, token)
    check("FR-ANALYSIS-01 任务创建为分析中", r.get("code") == 0 and r["data"]["status"] == "analyzing", r)
    task_id = r["data"]["id"]

    # AC-ANALYSIS-02 重复发起
    _, r = call("POST", "/api/analyses", {"resumeId": resume_id, "jobId": job_id, "demo": {"mode": "rule"}}, token)
    check("AC-ANALYSIS-02 进行中任务重复发起被拒绝", r.get("code") == 1 and "进行中" in r.get("message", ""), r)

    t = wait_done(task_id, token)
    check("AC-ANALYSIS-04 规则模式降级完成", t.get("status") == "success" and t.get("mode") == "rule", t)
    check("分析结果含匹配度/建议/缺失关键词",
          isinstance(t.get("score"), int) and len(t.get("suggestions", [])) > 0 and "Redis" in t.get("missing", []), t)

    # 简历原文未被分析修改
    _, r = call("GET", "/api/resumes/" + resume_id, token=token)
    check("分析后简历内容未改变", r["data"]["target"] == "Java 后端开发实习生" and r["data"]["version"] == 1)

    # ---------- AC-ANALYSIS-03 失败与重试 ----------
    _, r = call("POST", "/api/analyses", {"resumeId": resume_id, "jobId": job_id, "demo": {"fail": True}}, token)
    fail_id = r["data"]["id"]
    ft = wait_done(fail_id, token)
    check("AC-ANALYSIS-03 失败任务含原因", ft.get("status") == "failed" and ft.get("reason"), ft)
    _, r = call("POST", "/api/analyses/" + fail_id + "/versions", {"note": "x"}, token)
    check("失败任务不能直接采纳", r.get("code") == 1, r)
    _, r = call("POST", "/api/analyses", {"resumeId": resume_id, "jobId": job_id, "demo": {"mode": "rule"}}, token)
    check("AC-ANALYSIS-03 重试生成新任务", r.get("code") == 0 and r["data"]["id"] != fail_id)
    retry_id = r["data"]["id"]
    rt = wait_done(retry_id, token)
    check("重试任务成功", rt.get("status") == "success", rt)

    # ---------- FR-ANALYSIS-04 反馈（覆盖式） ----------
    _, r = call("POST", "/api/analyses/" + retry_id + "/feedback", {"type": "useful", "note": "有帮助"}, token)
    check("反馈提交成功", r.get("code") == 0 and r["data"]["type"] == "useful", r)
    _, r = call("POST", "/api/analyses/" + retry_id + "/feedback", {"type": "useless", "note": "一般"}, token)
    check("反馈重复提交覆盖", r.get("code") == 0 and r["data"]["type"] == "useless", r)

    # ---------- AC-ANALYSIS-05 记录仅本人可见 ----------
    _, r = call("GET", "/api/analyses", token=token)
    check("AC-ANALYSIS-05 分析记录仅本人", all(x["id"] not in ("a1", "a2") for x in r["data"]))
    _, r = call("GET", "/api/analyses/a1", token=token)
    check("越权查看他人分析被拒绝", r.get("code") == 1 and "无权" in r.get("message", ""), r)

    # ---------- AC-VERSION-01/02 采纳与对比 ----------
    _, r = call("POST", "/api/analyses/" + retry_id + "/versions",
                {"note": "", "summary": "s", "skills": [], "projects": []}, token)
    check("修改说明必填", r.get("code") == 1 and "修改说明" in r.get("message", ""), r)

    _, r = call("GET", "/api/resumes/" + resume_id, token=token)
    current = r["data"]
    new_projects = [dict(current["projects"][0], desc="使用 Spring Boot 实现订单模块；引入 Redis 缓存，接口平均响应从 420ms 降至 130ms。")]
    _, r = call("POST", "/api/analyses/" + retry_id + "/versions",
                {"note": "按 JD 补充 Redis 与量化结果", "summary": current["summary"],
                 "skills": current["skills"] + ["Redis"], "projects": new_projects}, token)
    check("AC-VERSION-01 采纳生成版本 2", r.get("code") == 0 and r["data"]["version"] == 2, r)

    _, r = call("GET", "/api/resumes/" + resume_id + "/versions", token=token)
    check("版本列表含 2 个版本且关联来源任务", len(r["data"]["versions"]) == 2 and r["data"]["versions"][0]["sourceTask"], r)

    _, r = call("GET", "/api/resumes/" + resume_id + "/versions/compare?a=1&b=2", token=token)
    a_skills = r["data"]["a"]["snapshot"]["skills"]
    b_skills = r["data"]["b"]["snapshot"]["skills"]
    check("AC-VERSION-02 版本对比可取两版本差异", r.get("code") == 0 and "Redis" not in a_skills and "Redis" in b_skills, r)

    _, r = call("GET", "/api/resumes/" + resume_id + "/versions/compare?a=1&b=9", token=token)
    check("不存在的版本提示", r.get("code") == 1 and "不存在" in r.get("message", ""), r)

    # ---------- FR-JOB-02 岗位匹配度排序 ----------
    _, r = call("POST", "/api/jobs", {"title": "Go 服务端实习生", "company": "另一家公司",
                                      "jd": "负责 Go 服务开发，要求熟悉 Kubernetes、分布式系统与 gRPC。",
                                      "keywords": ["Go", "Kubernetes", "分布式"]}, token)
    check("FR-JOB-02 第二个岗位创建", r.get("code") == 0, r)
    job2_id = r["data"]["id"]

    _, r = call("GET", "/api/jobs/match?resumeId=" + resume_id, token=token)
    jobs = r.get("data", {}).get("jobs", []) if r.get("code") == 0 else []
    by_id = {j["id"]: j for j in jobs}
    check("FR-JOB-02 匹配度按覆盖率计算并排序",
          r.get("code") == 0 and len(jobs) == 2 and jobs[0]["id"] == job_id
          and by_id.get(job_id, {}).get("matchScore") == 80, jobs)
    check("FR-JOB-02 返回命中与缺失关键词",
          "消息队列" in by_id.get(job_id, {}).get("missing", [])
          and "Java" in by_id.get(job_id, {}).get("matched", [])
          and by_id.get(job2_id, {}).get("matchScore") == 0, jobs)
    _, r = call("GET", "/api/jobs/match?resumeId=1", token=token)
    check("FR-JOB-02 不能用他人简历计算匹配度", r.get("code") == 1 and "无权" in r.get("message", ""), r)

    # ---------- FR-JOB-03 多简历对比 ----------
    second = dict(good, name="第二版简历", summary="Java 后端方向，熟悉 MySQL 与 Git，项目经验较少。",
                  skills=["Java", "MySQL", "Git"])
    _, r = call("POST", "/api/resumes", second, token)
    second_id = r["data"]["id"]
    check("FR-JOB-03 准备第二份简历", r.get("code") == 0, r)

    _, r = call("GET", "/api/jobs/" + job_id + "/resumes/compare?resumeIds=" + resume_id + "," + second_id,
                token=token)
    data = r.get("data", {})
    items = data.get("items", [])
    check("FR-JOB-03 返回两份简历的对比结果",
          r.get("code") == 0 and len(items) == 2 and data["job"]["keywordCount"] == 5, r)
    check("FR-JOB-03 按匹配度倒序并标记最匹配",
          items and items[0]["resumeId"] == resume_id and items[0]["matchScore"] == 80
          and items[0]["bestMatch"] is True and items[0]["matchScore"] > items[1]["matchScore"], items)
    check("FR-JOB-03 标记完整度最高且含缺失关键词",
          any(x["bestCompleteness"] for x in items) and all("missing" in x for x in items), items)

    _, r = call("GET", "/api/jobs/" + job_id + "/resumes/compare?resumeIds=" + resume_id, token=token)
    check("FR-JOB-03 少于 2 份拒绝", r.get("code") == 1 and "2~3" in r.get("message", ""), r)
    _, r = call("GET", "/api/jobs/" + job_id + "/resumes/compare?resumeIds=1,2,3,4", token=token)
    check("FR-JOB-03 超过 3 份拒绝", r.get("code") == 1 and "3 份" in r.get("message", ""), r)
    _, r = call("GET", "/api/jobs/" + job_id + "/resumes/compare?resumeIds=" + resume_id + "," + resume_id,
                token=token)
    check("FR-JOB-03 重复简历拒绝", r.get("code") == 1 and "重复" in r.get("message", ""), r)
    _, r = call("GET", "/api/jobs/" + job_id + "/resumes/compare?resumeIds=" + resume_id + ",1", token=token)
    check("FR-JOB-03 不能用他人简历对比", r.get("code") == 1 and "无权" in r.get("message", ""), r)

    # ---------- FR-RESUME-03 敏感信息脱敏提醒 ----------
    _, r = call("GET", "/api/resumes/" + resume_id, token=token)
    check("FR-RESUME-03 无敏感信息时不误报",
          r.get("code") == 0 and r["data"].get("sensitiveWarnings") == [], r)

    risky = dict(good, name="含敏感信息简历", phone="13812345678",
                 summary="身份证 110101199003071234，银行卡 6222020200112233445，联系电话 13900001111。")
    _, r = call("POST", "/api/resumes", risky, token)
    warnings = r.get("data", {}).get("sensitiveWarnings", [])
    types = {w["type"] for w in warnings}
    check("FR-RESUME-03 命中手机号/身份证号/银行卡号",
          r.get("code") == 0 and {"手机号", "身份证号", "银行卡号"} <= types, r)
    masked_text = " ".join(w["masked"] for w in warnings)
    check("FR-RESUME-03 只返回脱敏结果",
          "110101199003071234" not in masked_text and "6222020200112233445" not in masked_text
          and "13812345678" not in masked_text, masked_text)
    check("FR-RESUME-03 提醒不阻断保存", r.get("code") == 0 and bool(r["data"].get("id")), r)

    # ---------- FR-RESUME-04 文件导入 ----------
    txt = ("林知远\n求职意向：Java 后端开发实习生\n电话：13812345678 邮箱：linzhiyuan@example.com\n"
           "个人简介\n软件工程本科在读，熟悉 Java、Spring Boot 与 MySQL。\n"
           "技能清单\nJava、Spring Boot、MySQL、Redis\n"
           "项目经历\n校园二手交易平台\n2025.03 - 2025.09 负责订单模块，使用 Spring Boot。\n"
           "实验室设备预约系统\n2025.10 - 2026.01 负责权限模块，使用 Redis。\n"
           "教育经历\n江南大学 软件工程 本科")
    _, r = upload("导入测试.txt", txt.encode("utf-8"), token)
    d = r.get("data", {})
    check("FR-RESUME-04 TXT 解析姓名/意向/联系方式",
          r.get("code") == 0 and d.get("personName") == "林知远"
          and "Java" in d.get("target", "") and d.get("phone") == "13812345678", r)
    check("FR-RESUME-04 TXT 解析技能与项目",
          r.get("code") == 0 and "Spring Boot" in d.get("skills", [])
          and len(d.get("projects", [])) == 2, d)
    check("FR-RESUME-04 返回草稿提示与文本预览",
          r.get("code") == 0 and bool(d.get("warnings")) and bool(d.get("textPreview")), d)

    # Windows 记事本保存的 UTF-8 文本带 BOM，首个字段不应残留不可见字符
    _, r = upload("bom.txt", ("\ufeff" + txt).encode("utf-8"), token)
    d = r.get("data", {})
    check("FR-RESUME-04 TXT 带 BOM 时姓名不含不可见字符",
          r.get("code") == 0 and d.get("personName") == "林知远", d)

    docx = make_docx(["林知远", "求职意向：Java 后端开发实习生", "电话：13812345678 邮箱：linzhiyuan@example.com",
                      "个人简介", "软件工程本科在读，熟悉 Java、Spring Boot 与 MySQL。",
                      "技能清单", "Java、Spring Boot、MySQL、Redis",
                      "项目经历", "校园二手交易平台", "负责订单模块，使用 Spring Boot 与 MySQL。"])
    _, r = upload("导入测试.docx", docx, token)
    check("FR-RESUME-04 DOCX 解析",
          r.get("code") == 0 and r["data"]["personName"] == "林知远"
          and "MySQL" in r["data"]["skills"], r)

    pdf = make_pdf(["Lin Zhiyuan", "Java Spring Boot MySQL Redis", "Phone 13812345678"])
    _, r = upload("导入测试.pdf", pdf, token)
    check("FR-RESUME-04 PDF 解析",
          r.get("code") == 0 and "Java" in r["data"]["skills"]
          and r["data"]["phone"] == "13812345678", r)

    # .doc（Word 97-2003 二进制）：真实文件解析 + 损坏文件拒绝
    doc_path = Path(__file__).parent / "fixtures" / "sample.doc"
    if doc_path.exists():
        _, r = upload("陈明远简历.doc", doc_path.read_bytes(), token)
        d = r.get("data", {})
        check("FR-RESUME-04 DOC 解析姓名/意向/联系方式",
              r.get("code") == 0 and d.get("personName") == "陈明远"
              and "Java" in d.get("target", "") and d.get("phone") == "13912345678", d)
        check("FR-RESUME-04 DOC 解析技能",
              r.get("code") == 0 and "Spring Boot" in d.get("skills", []), d.get("skills"))
    else:
        check("FR-RESUME-04 DOC 测试素材存在", False, "fixtures/sample.doc 缺失")

    _, r = upload("损坏.doc", b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 200, token)
    check("FR-RESUME-04 损坏的 DOC 被拒绝", r.get("code") == 1, r)

    # ---------- FR-RESUME-05 扫描件与图片 OCR ----------
    png_path = Path(__file__).parent / "fixtures" / "resume_photo.png"
    if png_path.exists():
        _, r = upload("张伟简历.png", png_path.read_bytes(), token)
        msg = r.get("message", "") or ""
        ocr_skip = r.get("code") == 1 and ("扫描件识别需要视觉模型" in msg or "扫描件识别已关闭" in msg)
        if ocr_skip:
            print("SKIP FR-RESUME-05 OCR 用例：服务未以 AI 模式启动（" + msg[:40] + "）")
        else:
            d = r.get("data", {})
            check("FR-RESUME-05 图片简历 OCR 识别基本信息",
                  r.get("code") == 0 and d.get("personName") == "张伟"
                  and d.get("phone") == "13800138000", d)
            check("FR-RESUME-05 图片 OCR 识别技能",
                  "Spring Boot" in d.get("skills", []) and "消息队列" in d.get("skills", []),
                  d.get("skills"))
            check("FR-RESUME-05 图片 OCR 提示已识别",
                  any("OCR" in w for w in d.get("warnings", [])), d.get("warnings"))

            # JPG 走独立分支：MIME 必须是 image/jpeg，不能被当作 PNG 发送
            jpg_path = Path(__file__).parent / "fixtures" / "resume_photo.jpg"
            if jpg_path.exists():
                _, r = upload("张伟简历.jpg", jpg_path.read_bytes(), token)
                d = r.get("data", {})
                check("FR-RESUME-05 JPG 图片 OCR 识别（MIME 与格式一致）",
                      r.get("code") == 0 and d.get("personName") == "张伟", d)

            scan_path = Path(__file__).parent / "fixtures" / "scan_resume.pdf"
            _, r = upload("扫描简历.pdf", scan_path.read_bytes(), token)
            d = r.get("data", {})
            check("FR-RESUME-05 扫描件 PDF OCR 识别",
                  r.get("code") == 0 and d.get("personName") == "张伟"
                  and any("扫描件" in w for w in d.get("warnings", [])), d)
    else:
        check("FR-RESUME-05 OCR 测试素材存在", False, "fixtures/resume_photo.png 缺失")

    _, r = upload("伪造.png", b"\x89ABC" + b"x" * 100, token)
    check("FR-RESUME-05 伪造 PNG 文件头被拒绝", r.get("code") == 1, r)

    _, r = upload("简历.exe", b"MZ" + b"0" * 100, token)
    check("FR-RESUME-05 不支持的扩展名被拒绝",
          r.get("code") == 1 and "格式" in r.get("message", ""), r)

    _, r = upload("伪造.pdf", b"not a pdf" * 100, token)
    check("FR-RESUME-04 伪造文件头被拒绝", r.get("code") == 1, r)

    status, r = upload("未登录.txt", txt.encode("utf-8"))
    check("FR-RESUME-04 未登录上传被拒绝", status == 401 and r.get("code") == 1, r)

    _, r = upload("超大文件.txt", b"a" * (5 * 1024 * 1024 + 10), token)
    check("FR-RESUME-04 超过 5MB 拒绝", r.get("code") == 1 and "5MB" in r.get("message", ""), r)

    # ---------- FR-INTERVIEW-01 模拟面试题 ----------
    _, r = call("POST", "/api/analyses/" + retry_id + "/interview", {}, token)
    qs = r.get("data", {}).get("questions", [])
    cats = {q["category"] for q in qs}
    check("FR-INTERVIEW-01 生成结构化面试题",
          r.get("code") == 0 and len(qs) >= 6
          and {"项目经历", "技术深挖", "岗位匹配", "反问环节"} <= cats, r)
    check("FR-INTERVIEW-01 每题含问题与提示",
          bool(qs) and all(q.get("question") and q.get("tip") for q in qs), qs)
    _, r = call("POST", "/api/analyses/" + fail_id + "/interview", {}, token)
    check("FR-INTERVIEW-01 失败任务不能生成", r.get("code") == 1, r)
    _, r = call("POST", "/api/analyses/a1/interview", {}, token)
    check("FR-INTERVIEW-01 他人任务不可用", r.get("code") == 1 and "无权" in r.get("message", ""), r)

    # ---------- DR-001 并发发起分析（FOR UPDATE 串行化） ----------
    concurrent = []

    def fire():
        concurrent.append(call("POST", "/api/analyses",
                               {"resumeId": resume_id, "jobId": job_id, "demo": {"mode": "rule"}}, token))

    threads = [threading.Thread(target=fire) for _ in range(2)]
    for th in threads:
        th.start()
    for th in threads:
        th.join()
    codes = sorted(item[1].get("code") for item in concurrent)
    check("DR-001 并发发起仅一个成功", codes == [0, 1], concurrent)
    winners = [item for item in concurrent if item[1].get("code") == 0]
    if winners:
        wait_done(winners[0][1]["data"]["id"], token)
    _, r = call("GET", "/api/analyses", token=token)
    running = sum(1 for x in r.get("data", [])
                  if x.get("status") == "analyzing" and str(x.get("resumeId")) == str(resume_id))
    check("DR-001 同一简历最多 1 条进行中", running <= 1, running)

    # ---------- 管理端 ----------
    _, r = call("GET", "/api/admin/users", token=token)
    status_student_admin, _ = (r.get("code"), r)
    check("AC-ADMIN-01 学生访问后台被拒绝(403 或业务拒绝)", r.get("code") == 1, r)

    _, r = call("POST", "/api/auth/login", {"account": "admin", "password": "admin123"})
    check("管理员登录", r.get("code") == 0 and r["data"]["role"] == "admin")
    admin = r["data"]["token"]
    admin_id = r["data"]["id"]

    _, r = call("GET", "/api/admin/users", token=admin)
    check("FR-ADMIN-01 用户列表", r.get("code") == 0 and any(u["account"] == ACCOUNT for u in r["data"]), r)

    _, r = call("PATCH", "/api/admin/users/" + admin_id + "/status", token=admin)
    check("BR-ADMIN-02 不能禁用自身", r.get("code") == 1 and "不能" in r.get("message", ""), r)

    _, r = call("PATCH", "/api/admin/users/" + new_uid + "/status", token=admin)
    check("禁用学生账号", r.get("code") == 0 and r["data"]["status"] == "disabled", r)
    _, r = call("POST", "/api/auth/login", {"account": ACCOUNT, "password": "123456"})
    check("禁用后立即无法登录", r.get("code") == 1 and "禁用" in r.get("message", ""), r)
    _, r = call("GET", "/api/resumes", token=token)
    check("禁用后旧 Token 立即失效", r.get("code") == 1, r)
    _, r = call("PATCH", "/api/admin/users/" + new_uid + "/status", token=admin)
    check("重新启用账号", r.get("code") == 0 and r["data"]["status"] == "active", r)

    _, r = call("GET", "/api/admin/analyses", token=admin)
    check("FR-ADMIN-02 任务列表仅元信息（无建议全文）",
          r.get("code") == 0 and all("suggestions" not in x and "missing" not in x for x in r["data"]), r)

    _, stats = call("GET", "/api/admin/stats", token=admin)
    s = stats["data"]
    check("AC-STAT-01 统计一致性", s["analyses"] == s["success"] + s["failed"] + s["analyzing"], s)
    check("统计含规则占比与近7天趋势", "ruleShare" in s and len(s["chart"]) == 7, s)

    # ---------- FR-STAT-02 质量与反馈统计 ----------
    _, r = call("POST", "/api/auth/login", {"account": ACCOUNT, "password": "123456"})
    student_token2 = r["data"]["token"]
    status, r = call("GET", "/api/admin/quality", token=student_token2)
    check("FR-STAT-02 学生无权访问质量统计",
          status == 403 and r.get("code") == 1, r)

    _, q = call("GET", "/api/admin/quality", token=admin)
    d = q.get("data", {})
    fb = d.get("feedback", {})
    check("FR-STAT-02 反馈口径自洽",
          q.get("code") == 0 and fb.get("total") == fb.get("useful", 0) + fb.get("useless", 0)
          and fb.get("none") == fb.get("successTotal", 0) - fb.get("total", 0)
          and 0 <= fb.get("usefulRate", -1) <= 100, fb)
    modes = d.get("modes", [])
    check("FR-STAT-02 方式分布与成功任务一致",
          sum(m.get("count", 0) for m in modes) == s["success"]
          and {m.get("mode") for m in modes} == {"ai", "rule"}, modes)
    check("FR-STAT-02 缺失关键词聚合有效",
          bool(d.get("topMissing")) and all(x.get("keyword") and x.get("count", 0) > 0
                                            for x in d["topMissing"]), d.get("topMissing"))
    _, tasks = call("GET", "/api/admin/analyses", token=admin)
    expected_fallback = sum(1 for x in tasks["data"] if "降级" in (x.get("reason") or ""))
    check("FR-STAT-02 降级次数与任务原因一致",
          d.get("fallbackCount") == expected_fallback, (d.get("fallbackCount"), expected_fallback))
    system = d.get("system", {})
    check("FR-STAT-02 系统信息来自真实配置",
          system.get("dbVersion", "").startswith("5.5") and system.get("storage") == "本机 MySQL"
          and isinstance(system.get("aiEnabled"), bool)
          and (not system.get("aiEnabled") or isinstance(system.get("aiUsable"), bool)), system)
    check("TOKEN-01 登录凭证已持久化到数据库",
          isinstance(system.get("tokenActive"), int) and system["tokenActive"] >= 1, system)

    # ---------- FR-ADMIN-03 AI 模型设置 ----------
    status, r = call("GET", "/api/admin/ai/settings", token=student_token2)
    check("FR-ADMIN-03 学生无权访问模型设置", status == 403 and r.get("code") == 1, r)

    _, r = call("GET", "/api/admin/ai/settings", token=admin)
    ds = r.get("data", {})
    check("FR-ADMIN-03 读取当前模型设置",
          r.get("code") == 0 and bool(ds.get("model")) and ds.get("source") in ("config", "db"), ds)

    _, r = call("PUT", "/api/admin/ai/settings", {"model": "cn:e2e-temp-model"}, token=admin)
    check("FR-ADMIN-03 自定义模型可保存",
          r.get("code") == 0 and r["data"].get("model") == "cn:e2e-temp-model"
          and r["data"].get("source") == "db", r)
    _, r = call("GET", "/api/admin/ai/settings", token=admin)
    check("FR-ADMIN-03 切换后立即生效且持久化",
          r.get("code") == 0 and r["data"].get("model") == "cn:e2e-temp-model", r)

    _, r = call("PUT", "/api/admin/ai/settings", {"model": "bad model!"}, token=admin)
    check("FR-ADMIN-03 非法模型 ID 被拒绝", r.get("code") == 1, r)

    _, r = call("PUT", "/api/admin/ai/settings", {"model": ""}, token=admin)
    check("FR-ADMIN-03 恢复默认模型",
          r.get("code") == 0 and r["data"].get("source") == "config"
          and r["data"].get("model") == r["data"].get("defaultModel"), r)

    _, r = call("GET", "/api/admin/ai/models", token=admin)
    if r.get("code") == 0:
        model_list = r.get("data", {}).get("models", [])
        check("FR-ADMIN-03 模型清单来自网关",
              len(model_list) > 10 and all(m.get("id") for m in model_list), len(model_list))
    else:
        print("SKIP FR-ADMIN-03 模型清单用例：AI 网关不可用（"
              + (r.get("message") or "")[:40] + "）")

    # ---------- v2.0 学生端自定义 API ----------
    status, r = call("GET", "/api/user-apis")
    check("CUSTOM-API-01 未登录返回 401", status == 401 and r.get("code") == 1, r)

    _, r = call("GET", "/api/user-apis", token=student)
    check("CUSTOM-API-01 列表可用且返回上限",
          r.get("code") == 0 and r["data"]["limit"] == 100 and isinstance(r["data"]["items"], list), r)

    mock = start_mock()
    draft = {
        "name": "e2e-回声接口", "method": "GET", "url": MOCK_BASE + "/echo",
        "headers": [{"name": "Authorization", "value": "Bearer e2e-secret-token", "enabled": True},
                    {"name": "X-Disabled", "value": "1", "enabled": False}],
        "params": [{"name": "q", "type": "string", "value": "Java 后端", "enabled": True},
                   {"name": "page", "type": "number", "defaultValue": "2", "enabled": True}],
        "group": "端到端", "tags": ["冒烟", "回声"], "timeoutMs": 8000, "retries": 0, "enabled": True,
    }
    _, r = call("POST", "/api/user-apis", draft, student)
    api_id = (r.get("data") or {}).get("id")
    created_api_ids = [api_id] if api_id else []
    check("CUSTOM-API-01 新建配置成功", r.get("code") == 0 and bool(api_id), r)
    check("CUSTOM-API-01 分组、标签、请求头正确落库",
          (r.get("data") or {}).get("group") == "端到端"
          and (r.get("data") or {}).get("tags") == ["冒烟", "回声"]
          and len((r.get("data") or {}).get("headers") or []) == 2, r.get("data"))
    saved_headers = (r.get("data") or {}).get("headers") or []
    auth_display = saved_headers[0].get("value") if saved_headers else ""
    check("CUSTOM-API-01 敏感请求头回显为掩码",
          "****" in auth_display and "e2e-secret-token" not in auth_display, auth_display)
    check("CUSTOM-API-01 敏感头标记 masked、普通头不标记",
          saved_headers[0].get("masked") is True and saved_headers[1].get("masked") is False,
          saved_headers)

    _, r = call("POST", "/api/user-apis", draft, student)
    check("CUSTOM-API-01 同一学生不允许重名", r.get("code") == 1 and "同名" in (r.get("message") or ""), r)

    for label, bad_payload, keyword in [
        ("非法请求方法", dict(draft, name="e2e-a", method="TRACE"), "请求方法"),
        ("非 http 地址", dict(draft, name="e2e-b", url="ftp://127.0.0.1/x"), "http"),
        ("系统托管请求头", dict(draft, name="e2e-c",
                          headers=[{"name": "Host", "value": "x"}]), "系统管理"),
        ("number 参数非数字", dict(draft, name="e2e-d",
                               params=[{"name": "n", "type": "number", "value": "abc"}]), "必须是数字"),
        ("JSON 请求体非法", dict(draft, name="e2e-e", method="POST", bodyType="json",
                             body="{bad json"), "合法 JSON"),
        ("标签超过 10 个", dict(draft, name="e2e-f", tags=["t%d" % i for i in range(11)]), "标签最多"),
    ]:
        _, r = call("POST", "/api/user-apis", bad_payload, student)
        check("CUSTOM-API-01 校验拒绝：" + label,
              r.get("code") == 1 and keyword in (r.get("message") or ""), r)

    _, r = call("GET", "/api/user-apis?q=" + quote("回声"), token=student)
    hit_ids = [it["id"] for it in r.get("data", {}).get("items", [])]
    check("CUSTOM-API-01 关键字过滤", r.get("code") == 0 and str(api_id) in hit_ids, r.get("data"))
    _, r = call("GET", "/api/user-apis?group=" + quote("端到端"), token=student)
    hit_ids = [it["id"] for it in r.get("data", {}).get("items", [])]
    check("CUSTOM-API-01 分组过滤与分组清单",
          r.get("code") == 0 and str(api_id) in hit_ids and "端到端" in r["data"]["groups"], r.get("data"))
    _, r = call("GET", "/api/user-apis?tag=" + quote("冒烟"), token=student)
    hit_ids = [it["id"] for it in r.get("data", {}).get("items", [])]
    check("CUSTOM-API-01 标签过滤（FIND_IN_SET）",
          r.get("code") == 0 and str(api_id) in hit_ids and "回声" in r["data"]["tags"], r.get("data"))

    if mock:
        _, before = call("GET", "/api/user-apis", token=student)
        _, r = call("POST", "/api/user-apis/test", draft, student)
        d = r.get("data") or {}
        check("CUSTOM-API-02 草稿测试返回状态码与耗时",
              r.get("code") == 0 and d.get("ok") and d.get("status") == 200
              and isinstance(d.get("durationMs"), int), d)
        check("CUSTOM-API-02 查询参数按 UTF-8 编码拼接",
              "Java 后端" in (d.get("body") or ""), (d.get("body") or "")[:120])
        _, after = call("GET", "/api/user-apis", token=student)
        check("CUSTOM-API-02 草稿测试不落库",
              before.get("data", {}).get("total") == after.get("data", {}).get("total"),
              (before.get("data", {}).get("total"), after.get("data", {}).get("total")))

        _, r = call("POST", "/api/user-apis/%s/invoke" % api_id, {}, student)
        d = r.get("data") or {}
        check("CUSTOM-API-02 调用已保存配置成功",
              r.get("code") == 0 and d.get("ok") and d.get("status") == 200 and d.get("attempts") == 1, d)
        check("CUSTOM-API-02 默认值参与拼接",
              '"page": "2"' in (d.get("body") or ""), (d.get("body") or "")[:160])
        auth_header = (d.get("requestHeaders") or {}).get("Authorization") or ""
        check("CUSTOM-API-02 敏感请求头回显已掩码",
              "****" in auth_header and "e2e-secret-token" not in auth_header, auth_header)
        check("CUSTOM-API-02 调用时解密并真实发送敏感头",
              "Bearer e2e-secret-token" in (d.get("body") or ""), (d.get("body") or "")[:160])

        # 掩码沿用：仅提交 keep=true，服务端解密原密文后重新加密，值不丢失
        _, kept = call("PUT", "/api/user-apis/%s" % api_id,
                       dict(draft, headers=[{"name": "Authorization", "value": "", "keep": True,
                                             "enabled": True}]), student)
        kept_headers = (kept.get("data") or {}).get("headers") or []
        check("CUSTOM-API-01 掩码留空沿用原值（keep）",
              kept.get("code") == 0 and bool(kept_headers)
              and kept_headers[0].get("masked") is True
              and kept_headers[0].get("hasValue") is True, kept.get("data"))
        _, r2 = call("POST", "/api/user-apis/%s/invoke" % api_id, {}, student)
        check("CUSTOM-API-02 沿用后调用仍带原敏感头",
              "Bearer e2e-secret-token" in ((r2.get("data") or {}).get("body") or ""), r2.get("data"))

        cases = [
            ("404 不重试", {"name": "e2e-404", "url": MOCK_BASE + "/nope", "retries": 2},
             lambda x: x.get("status") == 404 and x.get("attempts") == 1 and not x.get("ok")),
            ("500 重试后成功", {"name": "e2e-500", "url": MOCK_BASE + "/flaky", "retries": 2},
             lambda x: x.get("ok") and x.get("attempts") == 3),
            ("超时按配置中断", {"name": "e2e-慢", "url": MOCK_BASE + "/slow", "timeoutMs": 1000},
             lambda x: x.get("status") == 0 and "超时" in (x.get("message") or "")),
            ("大响应截断至 64KB", {"name": "e2e-大", "url": MOCK_BASE + "/big"},
             lambda x: x.get("ok") and x.get("bodyTruncated") is True),
        ]
        for label, cfg, judge in cases:
            _, c = call("POST", "/api/user-apis", dict(draft, tags=[], **cfg), student)
            cid = (c.get("data") or {}).get("id")
            if cid:
                created_api_ids.append(cid)
            _, r = call("POST", "/api/user-apis/%s/invoke" % cid, {}, student)
            check("CUSTOM-API-02 " + label, r.get("code") == 0 and judge(r.get("data") or {}), r)
    else:
        print("SKIP CUSTOM-API-02 调用类用例（目标 Mock 未启动）")

    _, r = call("PUT", "/api/user-apis/%s" % api_id,
                dict(draft, name="e2e-回声接口改", group="端到端改", enabled=False), student)
    check("CUSTOM-API-01 更新配置生效",
          r.get("code") == 0 and r["data"]["name"] == "e2e-回声接口改"
          and r["data"]["group"] == "端到端改" and r["data"]["enabled"] is False, r)

    _, r = call("POST", "/api/user-apis/%s/invoke" % api_id, {}, student)
    check("CUSTOM-API-02 停用配置调用被拒绝",
          r.get("code") == 1 and "停用" in (r.get("message") or ""), r)

    _, r = call("POST", "/api/user-apis/%s/duplicate" % api_id, None, student)
    dup_id = (r.get("data") or {}).get("id")
    if dup_id:
        created_api_ids.append(dup_id)
    check("CUSTOM-API-01 复制配置自动命名",
          r.get("code") == 0 and (r.get("data") or {}).get("name", "").endswith("副本"), r)

    _, r = call("GET", "/api/user-apis/%s" % api_id, token=student_token2)
    check("CUSTOM-API-03 他人配置不可读取",
          r.get("code") == 1 and "无权" in (r.get("message") or ""), r)
    _, r = call("PUT", "/api/user-apis/%s" % api_id, {"name": "hack", "url": MOCK_BASE}, student_token2)
    check("CUSTOM-API-03 他人配置不可修改", r.get("code") == 1 and "无权" in (r.get("message") or ""), r)
    _, r = call("DELETE", "/api/user-apis/%s" % api_id, None, student_token2)
    check("CUSTOM-API-03 他人配置不可删除", r.get("code") == 1 and "无权" in (r.get("message") or ""), r)
    _, r = call("GET", "/api/user-apis", token=student_token2)
    check("CUSTOM-API-03 列表按账号隔离", r.get("code") == 0 and r["data"]["total"] == 0, r.get("data"))

    left = 0
    for cid in created_api_ids:
        _, r = call("DELETE", "/api/user-apis/%s" % cid, None, student)
        if r.get("code") != 0:
            left += 1
    _, r = call("GET", "/api/user-apis", token=student)
    remain = [it["id"] for it in r.get("data", {}).get("items", [])]
    check("CUSTOM-API-01 删除后列表同步清理",
          left == 0 and not (set(created_api_ids) & set(remain)), (left, remain))
    if mock:
        mock.shutdown()

    print("\n==== %d/%d 通过 ====" % (sum(1 for _, ok in results if ok), len(results)))
    failed = [n for n, ok in results if not ok]
    if failed:
        print("未通过：" + "; ".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
