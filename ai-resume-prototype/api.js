/* ============================================================
   api.js — API 桩层（模拟延迟 + 本地数据）
   真实后端接入时：替换每个函数实现，保持参数与返回结构不变。
   约定返回：{ code: 0, data } 成功；{ code: 1, message } 失败。
   ============================================================ */

window.Api = (() => {
  const db = () => Store.db;
  const clone = (o) => JSON.parse(JSON.stringify(o));
  const delay = (ms) => new Promise((r) => setTimeout(r, ms));
  const ok = (data) => ({ code: 0, data });
  const fail = (message) => ({ code: 1, message });

  const pad = (n) => String(n).padStart(2, '0');
  function nowStr() {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  function todayStr() {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }
  function uid(prefix) {
    return prefix + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  }
  function sessionUser() {
    try {
      const s = JSON.parse(localStorage.getItem('ars_session') || 'null');
      return s ? db().users.find((u) => u.id === s.id) || null : null;
    } catch (e) { return null; }
  }
  function resumeCompleteness(r) {
    let s = 0;
    if (r.name) s += 10;
    if (r.target) s += 10;
    if (r.phone) s += 5;
    if (r.email) s += 5;
    if ((r.summary || '').length >= 20) s += 15;
    if ((r.education || []).length) s += 20;
    if ((r.projects || []).length) s += 25;
    if ((r.skills || []).length >= 3) s += 15;
    return Math.min(100, s);
  }

  /* ---------------- Auth ---------------- */
  const AuthAPI = {
    // TODO: replace with POST /api/auth/login
    async login({ account, password }) {
      await delay(520);
      const u = db().users.find((x) => x.account === String(account || '').trim());
      if (!u || u.password !== password) return fail('账号或密码错误');
      if (u.status === 'disabled') return fail('账号已被禁用，请联系管理员');
      return ok({ id: u.id, account: u.account, name: u.name, role: u.role });
    },
    // TODO: replace with POST /api/auth/register
    async register({ account, password, name }) {
      await delay(560);
      const acc = String(account || '').trim();
      if (!acc || !password || !name) return fail('请完整填写注册信息');
      if (db().users.some((u) => u.account === acc)) return fail('账号已存在');
      const user = { id: uid('u'), account: acc, password, name, role: 'student', status: 'active', createdAt: todayStr(), analyses: 0 };
      db().users.push(user);
      Store.save();
      return ok({ account: user.account });
    }
  };

  /* ---------------- Resume ---------------- */
  const ResumeAPI = {
    // TODO: replace with GET /api/resumes?q=
    async list({ q = '' } = {}) {
      await delay(320);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const key = q.trim().toLowerCase();
      const rows = db().resumes
        .filter((r) => r.userId === me.id)
        .filter((r) => !key || r.name.toLowerCase().includes(key) || r.target.toLowerCase().includes(key))
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
        .map((r) => ({
          ...clone(r),
          personName: r.personName || me.name,
          versionCount: db().versions.filter((v) => v.resumeId === r.id).length
        }));
      return ok(rows);
    },
    // TODO: replace with GET /api/resumes/{id}
    async get(id) {
      await delay(240);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const r = db().resumes.find((x) => x.id === id);
      if (!r) return fail('简历不存在');
      if (r.userId !== me.id) return fail('无权访问该简历');
      return ok({ ...clone(r), personName: r.personName || me.name });
    },
    // TODO: replace with POST /api/resumes (create) / PUT /api/resumes/{id} (update)
    async save(payload) {
      await delay(460);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      if (!payload.name || !payload.name.trim()) return fail('简历名称不能为空');
      if (!payload.personName || !payload.personName.trim()) return fail('姓名不能为空');
      if (!payload.target || !payload.target.trim()) return fail('求职意向不能为空');
      if (!payload.education || !payload.education.length) return fail('请至少填写一条教育经历');
      if (!payload.projects || !payload.projects.length) return fail('请至少填写一条项目/实习经历');

      if (payload.id) {
        const r = db().resumes.find((x) => x.id === payload.id);
        if (!r) return fail('简历不存在');
        if (r.userId !== me.id) return fail('无权修改该简历');
        Object.assign(r, clone(payload), { userId: me.id, updatedAt: nowStr(), completeness: resumeCompleteness(payload) });
        Store.save();
        return ok(clone(r));
      }

      const r = {
        ...clone(payload),
        id: uid('r'),
        userId: me.id,
        version: 1,
        updatedAt: nowStr(),
        completeness: resumeCompleteness(payload)
      };
      db().resumes.push(r);
      db().versions.push({
        id: uid('v'), resumeId: r.id, version: 1, createdAt: nowStr(), sourceTaskId: null,
        note: '新建简历', snapshot: { summary: r.summary || '', skills: clone(r.skills || []), projects: clone((r.projects || []).map((p) => ({ title: p.title, desc: p.desc }))) }
      });
      Store.save();
      return ok(clone(r));
    },
    // TODO: replace with GET /api/resumes/{id}/versions
    async listVersions(resumeId) {
      await delay(300);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const r = db().resumes.find((x) => x.id === resumeId);
      if (!r) return fail('简历不存在');
      if (r.userId !== me.id) return fail('无权访问该简历');
      const rows = db().versions
        .filter((v) => v.resumeId === resumeId)
        .sort((a, b) => b.version - a.version)
        .map((v) => {
          const task = db().analyses.find((a) => a.id === v.sourceTaskId);
          return { ...clone(v), sourceTask: task ? task.jobTitle : null };
        });
      return ok({ resume: clone(r), versions: rows });
    }
  };

  /* ---------------- Job ---------------- */
  const JobAPI = {
    // TODO: replace with GET /api/jobs?q=
    async list({ q = '' } = {}) {
      await delay(300);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const key = q.trim().toLowerCase();
      const rows = db().jobs
        .filter((j) => j.userId === me.id)
        .filter((j) => !key || j.title.toLowerCase().includes(key) || j.company.toLowerCase().includes(key))
        .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
      return ok(clone(rows));
    },
    // TODO: replace with GET /api/jobs/{id}
    async get(id) {
      await delay(200);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const j = db().jobs.find((x) => x.id === id);
      if (!j) return fail('岗位不存在');
      if (j.userId !== me.id) return fail('无权访问该岗位');
      return ok(clone(j));
    },
    // TODO: replace with POST /api/jobs / PUT /api/jobs/{id}
    async save(payload) {
      await delay(420);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      if (!payload.title || !payload.title.trim()) return fail('岗位名称不能为空');
      if (!payload.jd || payload.jd.trim().length < 10) return fail('JD 文本不能少于 10 字');
      const data = {
        ...clone(payload),
        userId: me.id,
        keywords: (payload.keywords || []).map((k) => String(k).trim()).filter(Boolean),
        updatedAt: nowStr()
      };
      if (payload.id) {
        const j = db().jobs.find((x) => x.id === payload.id);
        if (!j) return fail('岗位不存在');
        if (j.userId !== me.id) return fail('无权修改该岗位');
        Object.assign(j, data);
        Store.save();
        return ok(clone(j));
      }
      const j = { ...data, id: uid('j') };
      db().jobs.push(j);
      Store.save();
      return ok(clone(j));
    }
  };

  /* ---------------- Analysis ---------------- */
  function buildSuggestions(missing, mode) {
    const list = [];
    if (missing.length) {
      list.push({
        module: '技能清单', level: 'high',
        text: `JD 中的「${missing.slice(0, 3).join('、')}」未在简历中出现，建议核实是否具备，并在技能清单或项目描述中体现。`
      });
    }
    list.push({
      module: '项目经历', level: 'high',
      text: '项目描述建议按「背景—职责—技术方案—结果」组织，并补充接口耗时、QPS 或数据量等可量化结果，避免只罗列功能。'
    });
    list.push({
      module: '个人信息', level: 'low',
      text: '建议补充可实习周期、每周到岗天数与期望城市，方便 HR 安排面试。'
    });
    if (mode === 'rule') {
      list.unshift({
        module: '分析方式', level: 'medium',
        text: '本次为规则模式：仅对 JD 关键词与简历技能/描述做交集比对，未调用大模型；结果仅供参考，服务恢复后可重新分析。'
      });
    }
    return list;
  }

  function computeResult(resume, job, mode) {
    const text = [
      resume.summary || '',
      ...(resume.skills || []),
      ...(resume.projects || []).map((p) => `${p.title || ''} ${p.desc || ''}`)
    ].join(' ').toLowerCase();
    const keywords = job.keywords || [];
    const hits = keywords.filter((k) => text.includes(String(k).toLowerCase()));
    const missing = keywords.filter((k) => !text.includes(String(k).toLowerCase()));
    let score = Math.round(50 + (keywords.length ? hits.length / keywords.length : 0.5) * 40 - (mode === 'rule' ? 7 : 0));
    score = Math.max(43, Math.min(91, score));
    return { score, missing, suggestions: buildSuggestions(missing, mode) };
  }

  const ANALYSIS_WAIT = { ai: 2.8, rule: 1.5 };

  function finalize(task) {
    if (!task || task.status !== 'analyzing') return task;
    const elapsed = (Date.now() - (task.startedAt || Date.now())) / 1000;
    if (elapsed < (ANALYSIS_WAIT[task.mode] || ANALYSIS_WAIT.ai)) return task;
    task.duration = Math.round(elapsed * 10) / 10;
    if (task.demoFail) {
      task.status = 'failed';
      task.reason = 'AI 服务响应超时（演示模拟），本次分析未生成结果。';
    } else {
      const resume = db().resumes.find((r) => r.id === task.resumeId) || {};
      const job = db().jobs.find((j) => j.id === task.jobId) || {};
      const result = computeResult(resume, job, task.mode);
      task.status = 'success';
      task.score = result.score;
      task.missing = result.missing;
      task.suggestions = result.suggestions;
    }
    delete task.demoFail;
    Store.save();
    return task;
  }

  const AnalysisAPI = {
    // TODO: replace with POST /api/analyses {resumeId, jobId}
    async create({ resumeId, jobId, demo = {} }) {
      await delay(380);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const resume = db().resumes.find((r) => r.id === resumeId);
      const job = db().jobs.find((j) => j.id === jobId);
      if (!resume) return fail('请选择简历');
      if (!job) return fail('请选择岗位');
      if (resume.userId !== me.id) return fail('无权使用该简历');
      if (job.userId !== me.id) return fail('无权使用该岗位');
      const busy = db().analyses.some((a) => a.resumeId === resumeId && a.status === 'analyzing');
      if (busy) return fail('该简历已有进行中的分析任务，请稍后再试');

      const mode = demo.mode === 'rule' ? 'rule' : 'ai';
      const task = {
        id: uid('a'), userId: me.id, resumeId, jobId,
        resumeName: resume.name, jobTitle: `${job.title} · ${job.company}`,
        status: 'analyzing', mode,
        score: null, createdAt: nowStr(), duration: 0,
        missing: [], suggestions: [], feedback: null,
        startedAt: Date.now(), demoFail: !!demo.fail
      };
      db().analyses.push(task);
      Store.save();
      return ok(clone(task));
    },
    // TODO: replace with GET /api/analyses/{id}
    async get(id) {
      await delay(220);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const t = db().analyses.find((a) => a.id === id);
      if (!t) return fail('分析任务不存在');
      if (t.userId !== me.id) return fail('无权访问该分析记录');
      finalize(t);
      return ok(clone(t));
    },
    // TODO: replace with GET /api/analyses?status=&q=
    async list({ status = '', q = '' } = {}) {
      await delay(340);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      db().analyses.forEach(finalize);
      const key = q.trim().toLowerCase();
      const rows = db().analyses
        .filter((a) => a.userId === me.id)
        .filter((a) => !status || a.status === status)
        .filter((a) => !key || a.resumeName.toLowerCase().includes(key) || a.jobTitle.toLowerCase().includes(key))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      return ok(clone(rows));
    },
    // TODO: replace with POST /api/analyses/{id}/feedback
    async feedback(id, { type, note }) {
      await delay(320);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const t = db().analyses.find((a) => a.id === id);
      if (!t) return fail('分析任务不存在');
      if (t.userId !== me.id) return fail('无权操作该记录');
      if (t.status !== 'success') return fail('仅成功的分析可以反馈');
      t.feedback = { type, note: note || '', at: nowStr() };
      Store.save();
      return ok(clone(t.feedback));
    },
    // TODO: replace with POST /api/analyses/{id}/versions
    async createVersion(id, { note, summary, skills, projects }) {
      await delay(520);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const t = db().analyses.find((a) => a.id === id);
      if (!t) return fail('分析任务不存在');
      if (t.userId !== me.id) return fail('无权操作该记录');
      if (t.status !== 'success') return fail('仅成功的分析可以生成新版本');
      const r = db().resumes.find((x) => x.id === t.resumeId);
      if (!r) return fail('简历不存在');
      if (r.userId !== me.id) return fail('无权修改该简历');
      if (!note || !note.trim()) return fail('请填写本次修改说明');

      const versions = db().versions.filter((v) => v.resumeId === r.id);
      const next = Math.max(0, ...versions.map((v) => v.version)) + 1;
      const snapshot = {
        summary: summary || '',
        skills: clone(skills || []),
        projects: clone((projects || []).map((p) => ({ title: p.title, desc: p.desc })))
      };
      db().versions.push({
        id: uid('v'), resumeId: r.id, version: next, createdAt: nowStr(),
        sourceTaskId: t.id, note: note.trim(), snapshot
      });
      Object.assign(r, {
        summary: snapshot.summary, skills: snapshot.skills, projects: clone(projects || []),
        version: next, updatedAt: nowStr(), completeness: resumeCompleteness(r)
      });
      Store.save();
      return ok(clone(r));
    }
  };

  /* ---------------- Version ---------------- */
  const VersionAPI = {
    // TODO: replace with GET /api/resumes/{id}/versions/compare?a=&b=
    async compare(resumeId, a, b) {
      await delay(360);
      const me = sessionUser();
      if (!me) return fail('登录状态已失效');
      const r = db().resumes.find((x) => x.id === resumeId);
      if (!r) return fail('简历不存在');
      if (r.userId !== me.id) return fail('无权访问该简历');
      const va = db().versions.find((v) => v.resumeId === resumeId && v.version === Number(a));
      const vb = db().versions.find((v) => v.resumeId === resumeId && v.version === Number(b));
      if (!va || !vb) return fail('所选版本不存在');
      return ok({ a: clone(va), b: clone(vb) });
    }
  };

  /* ---------------- Admin ---------------- */
  const AdminAPI = {
    // TODO: replace with GET /api/admin/users?q=
    async users({ q = '' } = {}) {
      await delay(360);
      const key = q.trim().toLowerCase();
      const rows = db().users
        .filter((u) => u.role === 'student')
        .filter((u) => !key || u.account.toLowerCase().includes(key) || u.name.toLowerCase().includes(key))
        .map((u) => ({
          ...clone(u),
          analyses: db().analyses.filter((a) => a.userId === u.id).length,
          resumes: db().resumes.filter((r) => r.userId === u.id).length
        }))
        .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      return ok(rows);
    },
    // TODO: replace with PATCH /api/admin/users/{id}/status
    async toggleUser(id) {
      await delay(420);
      const me = sessionUser();
      if (!me || me.role !== 'admin') return fail('无权执行该操作');
      if (id === me.id) return fail('不能禁用当前登录的管理员账号');
      const u = db().users.find((x) => x.id === id);
      if (!u) return fail('用户不存在');
      u.status = u.status === 'active' ? 'disabled' : 'active';
      Store.save();
      return ok(clone(u));
    },
    // TODO: replace with GET /api/admin/analyses?status=
    async tasks({ status = '', q = '' } = {}) {
      await delay(360);
      const key = q.trim().toLowerCase();
      db().analyses.forEach(finalize);
      const rows = db().analyses
        .filter((a) => !status || a.status === status)
        .filter((a) => !key || a.resumeName.toLowerCase().includes(key) || a.jobTitle.toLowerCase().includes(key) || a.userId.toLowerCase().includes(key))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
        .map((a) => {
          const u = db().users.find((x) => x.id === a.userId);
          return { ...clone(a), userName: u ? u.name : '未知用户' };
        });
      return ok(rows);
    },
    // TODO: replace with GET /api/admin/stats
    async stats() {
      await delay(280);
      const all = db().analyses;
      const success = all.filter((a) => a.status === 'success');
      const failed = all.filter((a) => a.status === 'failed');
      const rule = all.filter((a) => a.mode === 'rule');
      const avg = success.length ? Math.round(success.reduce((s, a) => s + (a.score || 0), 0) / success.length) : 0;

      const dates = all.map((a) => a.createdAt.slice(0, 10)).sort();
      const end = dates.length ? dates[dates.length - 1] : todayStr();
      const endDate = new Date(`${end}T00:00:00`);
      const chart = [];
      for (let i = 6; i >= 0; i--) {
        const d = new Date(endDate.getTime() - i * 86400000);
        const key = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
        chart.push({
          day: `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`,
          count: all.filter((a) => a.createdAt.slice(0, 10) === key).length,
          success: all.filter((a) => a.createdAt.slice(0, 10) === key && a.status === 'success').length
        });
      }
      return ok({
        users: db().users.filter((u) => u.role === 'student').length,
        activeUsers: db().users.filter((u) => u.role === 'student' && u.status === 'active').length,
        analyses: all.length,
        success: success.length,
        failed: failed.length,
        analyzing: all.filter((a) => a.status === 'analyzing').length,
        rule: rule.length,
        successRate: all.length ? Math.round(success.length / all.length * 100) : 0,
        ruleShare: all.length ? Math.round(rule.length / all.length * 100) : 0,
        avgScore: avg,
        chart
      });
    }
  };

  return { Auth: AuthAPI, Resume: ResumeAPI, Job: JobAPI, Analysis: AnalysisAPI, Version: VersionAPI, Admin: AdminAPI, finalize, nowStr, todayStr, uid };
})();
