/* 浏览器走查：Edge headless + CDP，验证登录、工作台、岗位匹配、敏感提醒、模拟面试、导入、对比、API 控制台 */
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PROFILE = path.join(process.env.TEMP || '/tmp', 'edge-cdp-airesume-' + Date.now());
const PORT = 9333;
const BASE = 'http://localhost:8080';
const SHOT_DIR = 'C:\\Users\\Administrator\\Desktop\\软件实训课设\\docs\\screenshots';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
  const proc = spawn(EDGE, [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${PROFILE}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    '--window-size=1440,1000',
    'about:blank'
  ], { stdio: 'ignore' });

  let ver = null;
  for (let i = 0; i < 40; i++) {
    try {
      ver = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json();
      break;
    } catch (e) { await sleep(300); }
  }
  if (!ver) throw new Error('Edge CDP 未就绪');

  const target = await (await fetch(`http://127.0.0.1:${PORT}/json/new?url=about:blank`, { method: 'PUT' })).json();
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

  let seq = 0;
  const pending = new Map();
  const events = [];
  const pageErrors = [];
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
    } else if (msg.method) {
      events.push(msg.method);
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params.exceptionDetails || {};
        pageErrors.push((d.exception && d.exception.description) || d.text || '未知异常');
      }
    }
  };
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });

  await send('Page.enable');
  await send('Runtime.enable');

  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error((r.exceptionDetails.text || 'JS 异常') + ' :: ' + expression);
    return r.result ? r.result.value : undefined;
  };

  const waitFor = async (fn, desc, timeout = 20000) => {
    const start = Date.now();
    let lastErr;
    while (Date.now() - start < timeout) {
      try {
        if (await fn()) return true;
      } catch (e) { lastErr = e; }
      await sleep(300);
    }
    throw new Error(`超时等待：${desc}${lastErr ? ' / ' + lastErr.message : ''}`);
  };

  const goto = async (url) => {
    events.length = 0;
    await send('Page.navigate', { url });
    await waitFor(async () => {
      try { return await evaluate('document.readyState === "complete"'); } catch (e) { return false; }
    }, '页面加载 ' + url);
  };

  const shot = async (name, fullPage = false) => {
    const { data } = await send('Page.captureScreenshot', {
      format: 'png',
      captureBeyondViewport: fullPage
    });
    const file = path.join(SHOT_DIR, name + '.png');
    fs.writeFileSync(file, Buffer.from(data, 'base64'));
    console.log('SHOT', file);
  };

  const out = {};

  // 1) 登录
  await goto(BASE + '/index.html');
  await evaluate(`document.querySelector('#fillStudent').click(); document.querySelector('#loginBtn').click();`);
  await waitFor(async () => {
    try { return (await evaluate('location.pathname')).includes('/student/dashboard.html'); } catch (e) { return false; }
  }, '登录跳转');
  await waitFor(async () => /^\d+$/.test(String(await evaluate(`(document.querySelector('#statResumes')||{}).textContent||''`)).trim()), '工作台统计加载');
  out.dashboard = {
    resumes: await evaluate(`document.querySelector('#statResumes').textContent`),
    jobs: await evaluate(`document.querySelector('#statJobs').textContent`),
    analyses: await evaluate(`document.querySelector('#statAnalyses').textContent`),
    recentRows: await evaluate(`document.querySelectorAll('#recentAnalyses tbody tr').length`)
  };
  await sleep(1400); // 等待 reveal 入场动画结束再截图
  await shot('01-工作台');

  // 2) 岗位管理：按简历匹配度排序
  await goto(BASE + '/student/jobs.html');
  await waitFor(async () => await evaluate(`(() => { const s=document.querySelector('#matchResumeSelect'); return s && !s.disabled && s.options.length>1; })()`), '简历下拉加载');
  const resumeId = await evaluate(`(() => { const s=document.querySelector('#matchResumeSelect'); const o=[...s.options].find(x=>x.value==='1') || [...s.options].find(x=>x.value); s.value=o.value; s.dispatchEvent(new Event('change')); return o.value; })()`);
  await waitFor(async () => await evaluate(`document.querySelector('#jobsBody').textContent.includes('覆盖')`), '匹配度渲染');
  out.jobs = {
    resumeId,
    countText: await evaluate(`document.querySelector('#jobCount').innerText`),
    countHTML: await evaluate(`document.querySelector('#jobCount').outerHTML.slice(0,300)`),
    rows: await evaluate(`[...document.querySelectorAll('#jobsBody tr')].map(tr => tr.querySelector('.cell-main').textContent.trim() + '|' + (tr.children[2] ? tr.children[2].textContent.trim().replace(/\\s+/g,' ') : ''))`)
  };
  await shot('02-岗位匹配度');

  // 3) 敏感信息提醒（按名称查找演示简历，避免硬编码主键）
  const sensitiveId = await evaluate(`(async () => {
    const s = JSON.parse(localStorage.getItem('ars_session') || '{}');
    const r = await fetch('/api/resumes', { headers: { 'X-Token': s.token } }).then(x => x.json());
    const hit = (r.data || []).find(x => x.name.includes('敏感信息示例'));
    return hit ? hit.id : null;
  })()`);
  if (sensitiveId) {
    await goto(BASE + '/student/resume-edit.html?id=' + sensitiveId);
    await waitFor(async () => await evaluate(`document.querySelector('#sensitiveBox').textContent.includes('敏感信息')`), '敏感信息提示');
    out.sensitive = await evaluate(`document.querySelector('#sensitiveBox').textContent.replace(/\\s+/g,' ').slice(0,200)`);
    await shot('03-敏感信息提醒');
  } else {
    out.sensitive = 'SKIP：未找到「敏感信息示例」简历';
    console.log(out.sensitive);
  }

  // 4) 分析结果 + 模拟面试题
  await goto(BASE + '/student/analysis.html?task=a1');
  await waitFor(async () => await evaluate(`!!document.querySelector('[data-act="interview"]')`), '分析结果渲染');
  out.analysis = await evaluate(`({ score: (document.querySelector('.score-num')||{}).textContent, missing: document.querySelectorAll('.chip--missing').length })`);
  await evaluate(`document.querySelector('[data-act="interview"]').click()`);
  await waitFor(async () => await evaluate(`!!document.querySelector('.modal') && document.querySelectorAll('.modal .list-item').length > 5`), '面试题弹窗');
  out.interview = {
    count: await evaluate(`document.querySelectorAll('.modal .list-item').length`),
    first: await evaluate(`document.querySelector('.modal .list-item-title').textContent.trim().slice(0,80)`)
  };
  await shot('04-模拟面试题');

  // 5) 文件导入（FR-RESUME-04）：通过 CDP 设置文件后触发 change
  const sampleFile = path.join(process.env.TEMP || '/tmp', 'browser-import-sample.txt');
  fs.writeFileSync(sampleFile, [
    '林知远',
    '求职意向：Java 后端开发实习生',
    '电话：13812345678 邮箱：linzhiyuan@example.com',
    '个人简介',
    '软件工程本科在读，熟悉 Java、Spring Boot 与 MySQL，参与校园二手交易平台开发。',
    '技能清单',
    'Java、Spring Boot、MySQL、Redis',
    '项目经历',
    '校园二手交易平台',
    '2025.03 - 2025.09 负责订单模块，使用 Spring Boot。',
    '教育经历',
    '江南大学 软件工程 本科'
  ].join('\n'), 'utf8');
  await goto(BASE + '/student/resume-edit.html');
  await waitFor(async () => await evaluate(`!!document.querySelector('#importFile')`), '导入按钮渲染');
  await send('DOM.enable');
  const doc = await send('DOM.getDocument');
  const fileNode = await send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#importFile' });
  await send('DOM.setFileInputFiles', { files: [sampleFile], nodeId: fileNode.nodeId });
  await evaluate(`document.querySelector('#importFile').dispatchEvent(new Event('change'))`);
  await waitFor(async () => await evaluate(`!!document.querySelector('.modal') && document.querySelector('.modal').textContent.includes('导入简历草稿')`), '导入草稿弹窗');
  out.importDraft = {
    hasName: await evaluate(`document.querySelector('.modal').textContent.includes('林知远')`),
    hasProject: await evaluate(`document.querySelector('.modal').textContent.includes('校园二手交易平台')`)
  };
  await sleep(400);
  await shot('05-简历导入');
  await evaluate(`document.querySelector('.modal [data-act="apply"]').click()`);
  await waitFor(async () => await evaluate(`document.querySelector('#fullName').value === '林知远'`), '导入结果填入表单');
  out.importApplied = await evaluate(`({
    name: document.querySelector('#fullName').value,
    target: document.querySelector('#jobTarget').value,
    skills: document.querySelector('#skillsInput').value
  })`);

  // 6) 多简历对比（FR-JOB-03）
  await goto(BASE + '/student/compare.html?job=1&resumeIds=1,2');
  await waitFor(async () => await evaluate(`(() => { const s=document.querySelector('#jobSelect'); return s && !s.disabled && document.querySelectorAll('.pick-box').length >= 2; })()`), '对比页加载岗位与简历');
  await evaluate(`document.querySelector('#compareBtn').click()`);
  await waitFor(async () => await evaluate(`document.querySelectorAll('#compareResult tbody tr').length >= 2`), '对比结果渲染');
  out.compare = {
    rows: await evaluate(`[...document.querySelectorAll('#compareResult tbody tr')].map(tr => tr.querySelector('.cell-main').textContent.trim().replace(/\\s+/g,' '))`),
    summary: await evaluate(`document.querySelector('#compareResult .banner').textContent.replace(/\\s+/g,' ').slice(0,120)`)
  };
  await evaluate(`document.querySelector('#compareResult').scrollIntoView({ block: 'start' })`);
  await sleep(500);
  await shot('06-简历对比');

  // 7) API 控制台（v2.0）：新建 → 测试请求 → 保存 → 调用 → 截图 → 清理
  await goto(BASE + '/student/api-console.html');
  await waitFor(async () => await evaluate(`!!document.querySelector('#newBtn') && !!document.querySelector('#apiBody')`), 'API 控制台加载');
  await evaluate(`document.querySelector('#newBtn').click()`);
  await waitFor(async () => await evaluate(`!!document.querySelector('#edName')`), '配置弹窗打开');
  await evaluate(`(() => {
    document.querySelector('#edName').value = '走查-站点首页';
    document.querySelector('#edUrl').value = 'http://localhost:8080/index.html';
    document.querySelector('#edGroup').value = '走查';
    return true;
  })()`);
  await evaluate(`document.querySelector('.modal [data-act="test"]').click()`);
  await waitFor(async () => await evaluate(`(() => { const r = document.querySelector('#modalResult');
    return r && !r.classList.contains('hidden') && r.textContent.includes('状态码'); })()`), '测试请求结果');
  out.apiConsole = {
    tested: await evaluate(`document.querySelector('#modalResult').textContent.replace(/\\s+/g,' ').slice(0,140)`)
  };
  await evaluate(`document.querySelector('.modal [data-act="save"]').click()`);
  await waitFor(async () => await evaluate(`!document.querySelector('.modal')
    && document.querySelector('#apiBody').textContent.includes('走查-站点首页')`), '保存后列表刷新');
  await evaluate(`document.querySelector('#apiBody tr [data-act="test"]').click()`);
  await waitFor(async () => await evaluate(`(() => { const r = document.querySelector('#invokeResult');
    return r && !r.classList.contains('hidden') && r.textContent.includes('状态码'); })()`), '调用结果渲染');
  out.apiConsole.invoked = await evaluate(`document.querySelector('#invokeResult').textContent.replace(/\\s+/g,' ').slice(0,160)`);
  out.apiConsole.snippet = await evaluate(`document.querySelector('#snippet').textContent.includes('invokeByName')`);
  await evaluate(`document.querySelector('#invokeResult').scrollIntoView({ block: 'center' })`);
  await sleep(3000); // 等提示条（2.6s）淡出后再截图
  await shot('09-API控制台');
  out.apiConsole.cleaned = await evaluate(`(async () => {
    const s = JSON.parse(localStorage.getItem('ars_session') || '{}');
    const r = await fetch('/api/user-apis', { headers: { 'X-Token': s.token } }).then(x => x.json());
    const hit = (r.data.items || []).find(x => x.name === '走查-站点首页');
    if (!hit) return 'NOT_FOUND';
    const d = await fetch('/api/user-apis/' + hit.id, { method: 'DELETE', headers: { 'X-Token': s.token } })
      .then(x => x.json());
    return d.code === 0 ? 'OK' : d.message;
  })()`);

  // 8) 其余页面扫一遍，收集 JS 异常（含管理员三页）
  for (const p of ['/student/resumes.html', '/student/resume-edit.html', '/student/jobs.html',
                   '/student/analysis.html', '/student/records.html', '/student/versions.html',
                   '/student/compare.html', '/student/api-console.html']) {
    await goto(BASE + p);
    await sleep(900);
  }
  await evaluate(`(async () => {
    const r = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ account: 'admin', password: 'admin123' }) }).then(x => x.json());
    if (r.code === 0) localStorage.setItem('ars_session', JSON.stringify(r.data));
    return r.code;
  })()`);
  await goto(BASE + '/admin/dashboard.html');
  await waitFor(async () => await evaluate(`document.querySelectorAll('#qualityStats .stat-card').length === 4
    && !document.querySelector('#qualityBadge').textContent.includes('统计中')`), '管理端质量看板');
  out.adminQuality = {
    badge: await evaluate(`document.querySelector('#qualityBadge').textContent.trim()`),
    usefulRate: await evaluate(`document.querySelector('#qualityStats .stat-value').textContent.trim()`),
    topMissing: await evaluate(`document.querySelectorAll('#topMissing .chip').length`),
    system: await evaluate(`document.querySelector('#systemBody').textContent.replace(/\\s+/g,' ').slice(0,140)`)
  };
  await sleep(1400);
  await shot('07-管理端质量看板', true);

  // 9) AI 模型设置（FR-ADMIN-03）：清单加载、自定义模式切换、截图
  //    规则模式（未配置密钥 / 网关不可达）下页面会回落「自定义模型…」，此处按回落分支断言，不算失败
  await waitFor(async () => await evaluate(`(() => {
    const s = document.querySelector('#modelSelect');
    const badge = (document.querySelector('#modelBadge') || {}).textContent || '';
    return s && !s.disabled && (s.options.length > 5 || /未启用|未配置密钥|读取失败|自定义模型/.test(badge + s.textContent));
  })()`), '模型清单加载或回落');
  out.adminModel = {
    options: await evaluate(`document.querySelector('#modelSelect').options.length`),
    selected: await evaluate(`document.querySelector('#modelSelect').value`),
    badge: await evaluate(`document.querySelector('#modelBadge').textContent.trim()`),
    hint: await evaluate(`document.querySelector('#modelHint').textContent.replace(/\\s+/g,' ').slice(0,120)`)
  };
  const initialState = await evaluate(`document.querySelector('#modelSelect').value`);
  out.adminModel.customVisible = await evaluate(`(() => {
    const s = document.querySelector('#modelSelect');
    s.value = '__custom__';
    s.dispatchEvent(new Event('change'));
    return !document.querySelector('#customField').classList.contains('hidden');
  })()`);
  out.adminModel.customInputFilled = await evaluate(`document.querySelector('#customModelInput').value`);
  await evaluate(`(() => {
    const s = document.querySelector('#modelSelect');
    s.value = ${JSON.stringify(initialState)};
    s.dispatchEvent(new Event('change'));
    s.closest('.card').scrollIntoView({ block: 'center' });
  })()`);
  await sleep(700);
  await shot('08-AI模型设置');
  for (const p of ['/admin/users.html', '/admin/tasks.html']) {
    await goto(BASE + p);
    await sleep(900);
  }
  out.pageErrors = pageErrors;

  console.log('RESULT ' + JSON.stringify(out, null, 2));
  ws.close();
  proc.kill();
}

main().catch(async (e) => {
  console.error('FAILED', e.message);
  try { process.exit(1); } catch (e2) { /* ignore */ }
});
