# -*- coding: utf-8 -*-
"""本地 Mock OpenAI 兼容服务（仅用于联调测试，不参与生产运行）。

用法：
    python mock_ai_server.py [port]

控制接口：
    GET /health              查看状态
    GET /control?mode=ok    正常返回符合约定的 JSON
    GET /control?mode=bad   返回非 JSON 内容（验证 BR-ANALYSIS-05）
    GET /control?mode=slow  延迟 5 秒（配合 timeout-seconds=2 验证 BR-ANALYSIS-03）
    GET /control?mode=http500 返回 HTTP 500（验证接口失败路径）
"""
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MODE = {"value": "ok"}
LOCK = threading.Lock()


def ai_content():
    return {
        "score": 88,
        "missing": ["Kafka"],
        "suggestions": [
            {"module": "技能清单", "level": "high", "text": "建议补充 Kafka 的实际使用场景与消息可靠性处理。"},
            {"module": "项目经历", "level": "medium", "text": "为订单模块补充 QPS、耗时等量化结果。"},
        ],
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # 静默日志，避免干扰测试输出
        return

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            return self._json(200, {"ok": True, "mode": MODE["value"]})
        if parsed.path == "/control":
            mode = parse_qs(parsed.query).get("mode", ["ok"])[0]
            if mode not in ("ok", "bad", "slow", "http500"):
                return self._json(400, {"error": "unknown mode"})
            with LOCK:
                MODE["value"] = mode
            return self._json(200, {"mode": mode})
        return self._json(404, {"error": "not found"})

    def do_POST(self):
        if urlparse(self.path).path != "/v1/chat/completions":
            return self._json(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        mode = MODE["value"]
        if mode == "http500":
            return self._json(500, {"error": "mock upstream error"})
        if mode == "slow":
            time.sleep(5)
        content = "这不是 JSON" if mode == "bad" else json.dumps(ai_content(), ensure_ascii=False)
        return self._json(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": "mock-model",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
        })


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"mock AI server listening on http://127.0.0.1:{port}", flush=True)
    server.serve_forever()
