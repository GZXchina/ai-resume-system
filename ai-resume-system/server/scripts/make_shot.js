/* 生成 OCR 测试素材：用 Edge CDP 将简历 HTML 截图为 PNG */
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PROFILE = path.join(process.env.TEMP || '/tmp', 'edge-cdp-shot-' + Date.now());
const PORT = 9337;
const OUT = process.argv[2];
const SRC = process.argv[3];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  if (!OUT || !SRC) throw new Error('usage: node make_shot.js <out.png> <source.html>');
  const proc = spawn(EDGE, [
    '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu',
    '--window-size=1000,560', 'about:blank'
  ], { stdio: 'ignore' });
  try {
    let ver = null;
    for (let i = 0; i < 40; i++) {
      try { ver = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break; }
      catch (e) { await sleep(300); }
    }
    if (!ver) throw new Error('Edge CDP 未就绪');
    const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?url=about:blank`, { method: 'PUT' })).json();
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    let seq = 0;
    const pending = new Map();
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
      }
    };
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = ++seq;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    });
    await send('Page.enable');
    await send('Emulation.setDeviceMetricsOverride', { width: 960, height: 540, deviceScaleFactor: 1, mobile: false });
    const url = /^https?:\/\//.test(SRC) ? SRC : 'file:///' + SRC.replace(/\\/g, '/');
    await send('Page.navigate', { url });
    await sleep(1200);
    const { data } = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(OUT, Buffer.from(data, 'base64'));
    console.log('SHOT', OUT, fs.statSync(OUT).size, 'bytes');
    ws.close();
  } finally {
    proc.kill();
  }
})().catch((e) => { console.error(e.message); process.exit(1); });
