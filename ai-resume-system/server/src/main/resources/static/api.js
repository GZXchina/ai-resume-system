/* ============================================================
   api.js — 前端 API 客户端（对接 Spring Boot 后端）
   - 统一返回：{ code: 0, data } 成功；{ code: 1, message } 失败
   - 认证：登录后 token 存入 localStorage(ars_session)，请求头 X-Token
   - 凭证失效（HTTP 401）：清理本地会话并跳转登录页
   - 页面路径：/student/*.html、/admin/*.html，接口统一前缀 /api
   ============================================================ */

window.Api = (() => {
  const BASE = '/api';
  const SESSION_KEY = 'ars_session';
  const TIMEOUT = 40000;

  const ok = (data) => ({ code: 0, data });
  const fail = (message) => ({ code: 1, message });

  function session() {
    try { return JSON.parse(localStorage.getItem(SESSION_KEY) || 'null'); }
    catch (e) { return null; }
  }

  function token() {
    const s = session();
    return s && s.token ? s.token : '';
  }

  function toLogin() {
    try { localStorage.removeItem(SESSION_KEY); } catch (e) { /* 忽略隐私模式异常 */ }
    const path = location.pathname;
    if (path.endsWith('/index.html') || path === '/' || path.endsWith('/')) return;
    location.replace('/index.html');
  }

  async function request(method, path, body, options = {}) {
    const headers = {};
    const headersOverride = options.headers || {};
    Object.assign(headers, headersOverride);
    let payload;
    if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const t = token();
    if (t) headers['X-Token'] = t;

    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timeout = options.timeout || TIMEOUT;
    const timer = controller ? setTimeout(() => controller.abort(), timeout) : null;
    let res;
    try {
      res = await fetch(BASE + path, {
        method,
        headers,
        body: payload,
        signal: controller ? controller.signal : undefined
      });
    } catch (e) {
      if (e && e.name === 'AbortError') return fail('请求超时，请稍后重试');
      return fail('无法连接服务器，请确认后端已启动');
    } finally {
      if (timer) clearTimeout(timer);
    }

    if (res.status === 401) {
      toLogin();
      return fail('登录状态已失效，请重新登录');
    }

    let json = null;
    try { json = await res.json(); }
    catch (e) { return fail('服务返回内容格式异常'); }

    if (res.status === 403) return fail(json.message || '无权访问该功能');
    if (json && json.code === 0) return ok(json.data);
    return fail((json && json.message) || '请求失败，请稍后重试');
  }

  const get = (path, options) => request('GET', path, undefined, options);
  const post = (path, body, options) => request('POST', path, body === undefined ? {} : body, options);
  const put = (path, body, options) => request('PUT', path, body === undefined ? {} : body, options);
  const patch = (path, body, options) => request('PATCH', path, body === undefined ? {} : body, options);
  const del = (path, options) => request('DELETE', path, undefined, options);

  const qs = (params) => {
    const usable = Object.entries(params || {}).filter(([, v]) => v !== undefined && v !== null && String(v) !== '');
    if (!usable.length) return '';
    return '?' + usable.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
  };

  /** multipart 上传（不设置 Content-Type，交给浏览器生成 boundary） */
  async function upload(path, formData) {
    const headers = {};
    const t = token();
    if (t) headers['X-Token'] = t;
    let res;
    try {
      res = await fetch(BASE + path, { method: 'POST', headers, body: formData });
    } catch (e) {
      return fail('无法连接服务器，请确认后端已启动');
    }
    if (res.status === 401) {
      toLogin();
      return fail('登录状态已失效，请重新登录');
    }
    let json = null;
    try { json = await res.json(); }
    catch (e) { return fail('服务返回内容格式异常'); }
    if (json && json.code === 0) return ok(json.data);
    return fail((json && json.message) || '上传失败，请稍后重试');
  }

  /* ---------------- Auth ---------------- */
  const AuthAPI = {
    async login({ account, password }) {
      const res = await post('/auth/login', { account, password });
      if (res.code === 0 && res.data && res.data.token) {
        localStorage.setItem(SESSION_KEY, JSON.stringify(res.data));
      }
      return res;
    },
    async register({ account, password, name }) {
      return post('/auth/register', { account, password, name });
    },
    async logout() {
      try { await post('/auth/logout'); } catch (e) { /* 网络异常不阻塞退出 */ }
      try { localStorage.removeItem(SESSION_KEY); } catch (e) { /* 忽略 */ }
      return ok({});
    },
    async me() {
      return get('/auth/me');
    }
  };

  /* ---------------- Resume ---------------- */
  const ResumeAPI = {
    async list({ q = '' } = {}) {
      return get('/resumes' + qs({ q }));
    },
    async get(id) {
      return get('/resumes/' + encodeURIComponent(id));
    },
    async save(payload) {
      const body = Object.assign({}, payload);
      if (body.id) {
        const id = body.id;
        delete body.id;
        return put('/resumes/' + encodeURIComponent(id), body);
      }
      delete body.id;
      return post('/resumes', body);
    },
    async listVersions(resumeId) {
      return get('/resumes/' + encodeURIComponent(resumeId) + '/versions');
    },
    /** 从 PDF/DOCX/TXT 导入简历草稿（FR-RESUME-04），不直接保存 */
    async importFile(file) {
      const form = new FormData();
      form.append('file', file);
      return upload('/resumes/import', form);
    }
  };

  /* ---------------- Job ---------------- */
  const JobAPI = {
    async list({ q = '' } = {}) {
      return get('/jobs' + qs({ q }));
    },
    async get(id) {
      return get('/jobs/' + encodeURIComponent(id));
    },
    async save(payload) {
      const body = Object.assign({}, payload);
      if (body.id) {
        const id = body.id;
        delete body.id;
        return put('/jobs/' + encodeURIComponent(id), body);
      }
      delete body.id;
      return post('/jobs', body);
    },
    /** 按简历匹配度排序（FR-JOB-02）；未选择简历时等同于普通列表 */
    async match({ resumeId, q = '' } = {}) {
      if (!resumeId) return this.list({ q });
      return get('/jobs/match' + qs({ resumeId, q }));
    },
    /** 多简历对比（FR-JOB-03）：同一岗位下 2~3 份简历 */
    async compare({ jobId, resumeIds } = {}) {
      return get('/jobs/' + encodeURIComponent(jobId) + '/resumes/compare'
        + qs({ resumeIds: (resumeIds || []).join(',') }));
    }
  };

  /* ---------------- Analysis ---------------- */
  const AnalysisAPI = {
    async create({ resumeId, jobId, demo = {} }) {
      return post('/analyses', { resumeId, jobId, demo });
    },
    async get(id) {
      return get('/analyses/' + encodeURIComponent(id));
    },
    async list({ status = '', q = '' } = {}) {
      return get('/analyses' + qs({ status, q }));
    },
    async feedback(id, { type, note }) {
      return post('/analyses/' + encodeURIComponent(id) + '/feedback', { type, note });
    },
    async createVersion(id, { note, summary, skills, projects }) {
      return post('/analyses/' + encodeURIComponent(id) + '/versions', { note, summary, skills, projects });
    },
    /** 生成模拟面试题（FR-INTERVIEW-01，需成功的分析任务） */
    async interview(id) {
      return post('/analyses/' + encodeURIComponent(id) + '/interview');
    }
  };

  /* ---------------- Version ---------------- */
  const VersionAPI = {
    async compare(resumeId, a, b) {
      return get('/resumes/' + encodeURIComponent(resumeId) + '/versions/compare' + qs({ a, b }));
    }
  };

  /* ---------------- Admin ---------------- */
  const AdminAPI = {
    async users({ q = '' } = {}) {
      return get('/admin/users' + qs({ q }));
    },
    async toggleUser(id) {
      return patch('/admin/users/' + encodeURIComponent(id) + '/status');
    },
    async tasks({ status = '', q = '' } = {}) {
      return get('/admin/analyses' + qs({ status, q }));
    },
    async stats() {
      return get('/admin/stats');
    },
    async quality() {
      return get('/admin/quality');
    },
    /** AI 模型清单（服务端代理网关，密钥不出后端）；refresh=true 强制刷新缓存 */
    async aiModels({ refresh = false } = {}) {
      return get('/admin/ai/models' + (refresh ? '?refresh=true' : ''));
    },
    /** 当前分析模型设置 */
    async aiSettings() {
      return get('/admin/ai/settings');
    },
    /** 预置 AI 厂商清单（默认地址、默认模型、密钥格式与获取指引） */
    async aiVendors() {
      return get('/admin/ai/vendors');
    },
    /** 连通性测试：只探测，不保存 */
    async testAiSettings(payload) {
      return post('/admin/ai/test', payload || {});
    },
    /** 保存厂商配置（vendor/endpoint/apiKey/model，可带 skipTest/force/clearApiKey） */
    async saveAiSettings(payload) {
      return put('/admin/ai/settings', payload || {});
    },
    /** 切换模型；model 传空字符串表示恢复配置文件默认值 */
    async saveAiModel(model) {
      return put('/admin/ai/settings', { model: model || '' });
    }
  };

  /* ---------------- Custom API（v2.0） ----------------
     学生自定义 API：配置 CRUD、测试与调用。
     - 调用统一走服务端代理（避免浏览器跨域），服务端负责超时/重试/响应截断
     - 前端统一拦截：传输失败自动重试，401 照常跳登录，结果统一 {code,data|message}
     - 持久化：服务端 user_api_config 为准，localStorage 保留镜像与离线待同步队列
     ---------------------------------------------------- */
  const CUSTOM_CACHE = 'ars_custom_apis';
  const CUSTOM_PENDING = 'ars_custom_apis_pending';
  const CUSTOM_LIMIT = 100;

  function readJSON(key, fallback) {
    try {
      const raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : fallback;
    } catch (e) {
      return fallback;
    }
  }

  function writeJSON(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* 隐私模式忽略 */ }
  }

  /** 是否为网络层失败（可重试）；业务校验失败不重试。 */
  function isTransportError(res) {
    return !!res && res.code !== 0 && /请求超时|无法连接服务器/.test(res.message || '');
  }

  async function withRetry(fn, retries) {
    let res;
    for (let i = 0; i <= retries; i += 1) {
      res = await fn(i);
      if (!isTransportError(res) || i === retries) return res;
      await new Promise((resolve) => setTimeout(resolve, 200 * (i + 1)));
    }
    return res;
  }

  function matchLocal(item, q, group, tag) {
    const keyword = (q || '').trim().toLowerCase();
    if (keyword) {
      const hit = [item.name, item.url, (item.tags || []).join(',')]
        .some((v) => String(v || '').toLowerCase().includes(keyword));
      if (!hit) return false;
    }
    if (group && item.group !== group) return false;
    if (tag && !(item.tags || []).includes(tag)) return false;
    return true;
  }

  const CustomAPI = {
    /** 列表：优先云端；云端不可用时回落到本地镜像并标记 offline */
    async list({ q = '', group = '', tag = '' } = {}) {
      const res = await get('/user-apis' + qs({ q, group, tag }));
      if (res.code === 0) {
        if (!q && !group && !tag) writeJSON(CUSTOM_CACHE, res.data);
        return res;
      }
      const cache = readJSON(CUSTOM_CACHE, null);
      if (!cache) return res;
      const items = (cache.items || []).filter((it) => matchLocal(it, q, group, tag));
      return ok(Object.assign({}, cache, {
        items, total: items.length, offline: true, offlineMessage: res.message
      }));
    },

    async pendingCount() {
      return readJSON(CUSTOM_PENDING, []).length;
    },

    /** 本地镜像快照（用于「导出/查看备份」） */
    cache() {
      return readJSON(CUSTOM_CACHE, { items: [], total: 0, groups: [], tags: [] });
    },

    _upsertCache(item) {
      if (!item || !item.id) return;
      const cache = readJSON(CUSTOM_CACHE, { items: [], total: 0, groups: [], tags: [] });
      const items = cache.items || [];
      const index = items.findIndex((it) => String(it.id) === String(item.id));
      if (index >= 0) items[index] = item; else items.unshift(item);
      const groups = Array.from(new Set(items.map((it) => it.group).filter(Boolean))).sort();
      const tags = Array.from(new Set(items.flatMap((it) => it.tags || [])));
      writeJSON(CUSTOM_CACHE, { items, total: items.length, groups, tags, limit: CUSTOM_LIMIT });
    },

    async get(id) {
      const res = await get('/user-apis/' + encodeURIComponent(id));
      if (res.code === 0) return res;
      const cache = readJSON(CUSTOM_CACHE, null);
      const hit = cache && (cache.items || []).find((it) => String(it.id) === String(id));
      return hit ? ok(hit) : res;
    },

    /** 保存：有 id 走更新，否则新建；网络失败时写入本地待同步队列 */
    async save(payload) {
      const body = Object.assign({}, payload);
      const id = body.id;
      delete body.id;
      const res = id
        ? await put('/user-apis/' + encodeURIComponent(id), body)
        : await post('/user-apis', body);
      if (res.code === 0) {
        this._upsertCache(res.data);
        return res;
      }
      if (isTransportError(res)) {
        const pending = readJSON(CUSTOM_PENDING, []);
        pending.push({ op: id ? 'update' : 'create', id: id || null, payload: body, at: Date.now() });
        writeJSON(CUSTOM_PENDING, pending);
        return { code: 1, message: res.message, queued: true };
      }
      return res;
    },

    async remove(id) {
      const res = await del('/user-apis/' + encodeURIComponent(id));
      if (res.code === 0) {
        const cache = readJSON(CUSTOM_CACHE, { items: [] });
        const items = (cache.items || []).filter((it) => String(it.id) !== String(id));
        writeJSON(CUSTOM_CACHE, Object.assign({}, cache, { items, total: items.length }));
        return res;
      }
      if (isTransportError(res)) {
        const pending = readJSON(CUSTOM_PENDING, []);
        pending.push({ op: 'delete', id, at: Date.now() });
        writeJSON(CUSTOM_PENDING, pending);
        return { code: 1, message: res.message, queued: true };
      }
      return res;
    },

    async duplicate(id) {
      const res = await post('/user-apis/' + encodeURIComponent(id) + '/duplicate');
      if (res.code === 0) this._upsertCache(res.data);
      return res;
    },

    /** 测试表单当前配置（不保存） */
    async test(payload) {
      const timeout = Number(payload && payload.timeoutMs) || 15000;
      return withRetry(() => post('/user-apis/test', payload || {}, { timeout: timeout + 10000 }), 1);
    },

    /** 调用已保存配置；overrides 支持 {params, headers, body} */
    async invoke(id, overrides) {
      return withRetry(
        () => post('/user-apis/' + encodeURIComponent(id) + '/invoke', overrides || {}, { timeout: 75000 }),
        1
      );
    },

    /** 前端业务代码按名称直接调用已配置的自定义 API */
    async invokeByName(name, overrides) {
      const list = await this.list();
      if (list.code !== 0) return list;
      const item = (list.data.items || []).find((it) => it.name === name);
      if (!item) return fail('未找到名为「' + name + '」的自定义 API');
      return this.invoke(item.id, overrides);
    },

    /** 把离线暂存的增删改推送到云端 */
    async syncPending() {
      const pending = readJSON(CUSTOM_PENDING, []);
      if (!pending.length) return ok({ synced: 0, failed: 0, remaining: 0 });
      const rest = [];
      let synced = 0;
      for (const op of pending) {
        let res;
        if (op.op === 'delete') res = await del('/user-apis/' + encodeURIComponent(op.id));
        else if (op.op === 'update') res = await put('/user-apis/' + encodeURIComponent(op.id), op.payload);
        else res = await post('/user-apis', op.payload);
        if (res.code === 0) synced += 1; else rest.push(op);
      }
      writeJSON(CUSTOM_PENDING, rest);
      return ok({ synced, failed: rest.length, remaining: rest.length });
    }
  };

  /* 兼容旧页面：本地格式化工具（无网络语义） */
  const pad = (n) => String(n).padStart(2, '0');
  const nowStr = () => {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const todayStr = () => {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  };
  const uid = (prefix) => prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

  return {
    Auth: AuthAPI, Resume: ResumeAPI, Job: JobAPI,
    Analysis: AnalysisAPI, Version: VersionAPI, Admin: AdminAPI,
    Custom: CustomAPI,
    nowStr, todayStr, uid
  };
})();
