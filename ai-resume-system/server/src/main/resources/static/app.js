/* ============================================================
   app.js — 共享 UI 工具：图标、会话、Toast、Modal、页面初始化
   ============================================================ */

window.App = (() => {
  const ICONS = {
    'layout-dashboard': '<rect width="7" height="9" x="3" y="3" rx="1"/><rect width="7" height="5" x="14" y="3" rx="1"/><rect width="7" height="9" x="14" y="12" rx="1"/><rect width="7" height="5" x="3" y="16" rx="1"/>',
    'file-text': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
    'briefcase': '<rect width="20" height="14" x="2" y="7" rx="2" ry="2"/><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>',
    'sparkles': '<path d="m12 3-1.9 5.8a2 2 0 0 1-1.3 1.3L3 12l5.8 1.9a2 2 0 0 1 1.3 1.3L12 21l1.9-5.8a2 2 0 0 1 1.3-1.3L21 12l-5.8-1.9a2 2 0 0 1-1.3-1.3Z"/>',
    'history': '<path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>',
    'git-compare': '<circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M13 6h3a2 2 0 0 1 2 2v7"/><path d="M11 18H8a2 2 0 0 1-2-2V9"/>',
    'gauge': '<path d="m12 14 4-4"/><path d="M3.34 19a10 10 0 1 1 17.32 0"/>',
    'users': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    'activity': '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
    'log-out': '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" x2="9" y1="12" y2="12"/>',
    'shield': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
    'plus': '<path d="M5 12h14"/><path d="M12 5v14"/>',
    'search': '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
    'chevron-right': '<path d="m9 18 6-6-6-6"/>',
    'chevron-down': '<path d="m6 9 6 6 6-6"/>',
    'check': '<path d="M20 6 9 17l-5-5"/>',
    'check-circle': '<path d="M21.801 10A10 10 0 1 1 17 3.335"/><path d="m9 11 3 3L22 4"/>',
    'x': '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
    'alert-triangle': '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    'refresh-cw': '<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
    'clock': '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>',
    'user': '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
    'edit-3': '<path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    'copy': '<rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/>',
    'eye': '<path d="M2.06 12.35a1 1 0 0 1 0-.7 10.75 10.75 0 0 1 19.88 0 1 1 0 0 1 0 .7 10.75 10.75 0 0 1-19.88 0"/><circle cx="12" cy="12" r="3"/>',
    'trash-2': '<path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/><line x1="10" x2="10" y1="11" y2="17"/><line x1="14" x2="14" y1="11" y2="17"/>',
    'arrow-right': '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
    'arrow-up-right': '<path d="M7 7h10v10"/><path d="M7 17 17 7"/>',
    'target': '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>',
    'tag': '<path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z"/><circle cx="7.5" cy="7.5" r=".5" fill="currentColor"/>',
    'calendar': '<path d="M8 2v4"/><path d="M16 2v4"/><rect width="18" height="18" x="3" y="4" rx="2"/><path d="M3 10h18"/>',
    'loader': '<path d="M21 12a9 9 0 1 1-6.219-8.56"/>',
    'star': '<path d="M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"/>',
    'info': '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
    'circle': '<circle cx="12" cy="12" r="10"/>',
    'phone': '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
    'mail': '<rect width="20" height="16" x="2" y="4" rx="2"/><path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>',
    'building-2': '<path d="M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z"/><path d="M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2"/><path d="M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2"/><path d="M10 6h4"/><path d="M10 10h4"/><path d="M10 14h4"/><path d="M10 18h4"/>',
    'filter': '<path d="M22 3H2l8 9.46V19l4 2v-8.54L22 3z"/>',
    'file-plus': '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M9 15h6"/><path d="M12 12v6"/>',
    'pen-line': '<path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
    'server': '<rect width="20" height="8" x="2" y="2" rx="2"/><rect width="20" height="8" x="2" y="14" rx="2"/><line x1="6" x2="6.01" y1="6" y2="6"/><line x1="6" x2="6.01" y1="18" y2="18"/>',
    'play': '<polygon points="6 3 20 12 6 21 6 3"/>',
    'save': '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>',
    'code': '<polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>',
    'inbox': '<polyline points="22 12 16 12 14 15 10 15 8 12 2 12"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>'
  };

  function svg(name) {
    const body = ICONS[name] || ICONS.circle;
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
  }
  function renderIcons(root = document) {
    root.querySelectorAll('[data-icon]').forEach((el) => {
      if (el.dataset.rendered === '1') return;
      el.innerHTML = svg(el.dataset.icon);
      el.dataset.rendered = '1';
    });
  }

  /* ---------- Session ---------- */
  const Auth = {
    current() {
      try { return JSON.parse(localStorage.getItem('ars_session') || 'null'); }
      catch (e) { return null; }
    },
    set(session) { localStorage.setItem('ars_session', JSON.stringify(session)); },
    clear() { localStorage.removeItem('ars_session'); }
  };

  /* ---------- Toast ---------- */
  function toast(text, type = 'info') {
    let wrap = document.querySelector('.toast-wrap');
    if (!wrap) {
      wrap = document.createElement('div');
      wrap.className = 'toast-wrap';
      document.body.appendChild(wrap);
    }
    const icon = type === 'success' ? 'check-circle' : type === 'error' ? 'alert-triangle' : 'info';
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.innerHTML = `<span data-icon="${icon}"></span><span>${text}</span>`;
    wrap.appendChild(el);
    renderIcons(el);
    setTimeout(() => {
      el.style.transition = 'opacity .2s ease, transform .2s ease';
      el.style.opacity = '0';
      el.style.transform = 'translateY(6px)';
      setTimeout(() => el.remove(), 220);
    }, 2600);
  }

  /* ---------- Modal ---------- */
  function modal({ title = '', sub = '', body = '', footer = '', wide = false } = {}) {
    const back = document.createElement('div');
    back.className = 'modal-backdrop';
    back.innerHTML = `
      <div class="modal ${wide ? 'modal--wide' : ''}" role="dialog" aria-modal="true">
        <div class="modal-head">
          <div>
            <div class="modal-title">${title}</div>
            ${sub ? `<div class="modal-sub">${sub}</div>` : ''}
          </div>
          <button class="modal-close" type="button" aria-label="关闭"><span data-icon="x"></span></button>
        </div>
        <div class="modal-body">${body}</div>
        ${footer ? `<div class="modal-foot">${footer}</div>` : ''}
      </div>`;
    document.body.appendChild(back);
    renderIcons(back);
    const close = () => { back.remove(); document.removeEventListener('keydown', onKey); };
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', onKey);
    back.addEventListener('click', (e) => { if (e.target === back) close(); });
    back.querySelector('.modal-close').addEventListener('click', close);
    return { el: back, dialog: back.querySelector('.modal'), body: back.querySelector('.modal-body'), close };
  }

  function confirm({ title = '确认操作', text = '', okText = '确认', danger = false } = {}) {
    return new Promise((resolve) => {
      const m = modal({
        title,
        body: `<p class="muted" style="line-height:1.75">${text}</p>`,
        footer: `<button class="btn btn-ghost" type="button" data-act="cancel">取消</button>
                 <button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" type="button" data-act="ok">${okText}</button>`
      });
      m.el.querySelector('[data-act="cancel"]').addEventListener('click', () => { m.close(); resolve(false); });
      m.el.querySelector('[data-act="ok"]').addEventListener('click', () => { m.close(); resolve(true); });
    });
  }

  /* ---------- Helpers ---------- */
  const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const param = (name) => new URLSearchParams(location.search).get(name);
  const fmtDate = (v) => (v || '').slice(0, 10);
  const fmtShort = (v) => (v || '').slice(5, 16);
  const initials = (name) => (name || '?').slice(0, 1);

  function statusTag(status) {
    if (status === 'success') return '<span class="tag tag-success"><span data-icon="check"></span>成功</span>';
    if (status === 'failed') return '<span class="tag tag-danger"><span data-icon="alert-triangle"></span>失败</span>';
    if (status === 'analyzing') return '<span class="tag tag-info"><span data-icon="loader"></span>分析中</span>';
    return '<span class="tag tag-neutral">未知</span>';
  }
  function modeTag(mode) {
    return mode === 'rule'
      ? '<span class="tag tag-rule">规则模式</span>'
      : '<span class="tag tag-info">AI 模式</span>';
  }
  function levelTag(level) {
    if (level === 'high') return '<span class="tag tag-danger">高优先级</span>';
    if (level === 'medium') return '<span class="tag tag-warn">中优先级</span>';
    return '<span class="tag tag-neutral">低优先级</span>';
  }

  /* ---------- 修改密码（所有页面共用） ---------- */
  function changePasswordDialog() {
    const body = `
      <div class="field">
        <label class="field-label" for="pwdOld">原密码</label>
        <input class="input" id="pwdOld" type="password" autocomplete="current-password" placeholder="请输入当前密码">
      </div>
      <div class="field" style="margin-top:12px">
        <label class="field-label" for="pwdNew">新密码</label>
        <input class="input" id="pwdNew" type="password" autocomplete="new-password" placeholder="至少 6 位">
      </div>
      <div class="field" style="margin-top:12px">
        <label class="field-label" for="pwdConfirm">确认新密码</label>
        <input class="input" id="pwdConfirm" type="password" autocomplete="new-password" placeholder="再次输入新密码">
      </div>
      <div class="field-hint" style="margin-top:12px">修改成功后，其他设备上的登录会失效，当前页面保持登录。</div>`;
    const footer = `
      <button class="btn btn-outline" type="button" data-cancel="1">取消</button>
      <button class="btn btn-primary" type="button" data-ok="1">确认修改</button>`;
    const m = modal({ title: '修改密码', sub: '下次登录请使用新密码', body, footer });
    const oldEl = m.el.querySelector('#pwdOld');
    const newEl = m.el.querySelector('#pwdNew');
    const againEl = m.el.querySelector('#pwdConfirm');
    const okBtn = m.el.querySelector('[data-ok]');
    m.el.querySelector('[data-cancel]').addEventListener('click', m.close);

    okBtn.addEventListener('click', async () => {
      const oldPwd = oldEl.value;
      const newPwd = newEl.value;
      if (!oldPwd) { toast('请输入原密码', 'error'); oldEl.focus(); return; }
      if (newPwd.length < 6) { toast('新密码至少 6 位', 'error'); newEl.focus(); return; }
      if (newPwd !== againEl.value) { toast('两次输入的新密码不一致', 'error'); againEl.focus(); return; }
      if (newPwd === oldPwd) { toast('新密码不能与原密码相同', 'error'); newEl.focus(); return; }

      okBtn.disabled = true;
      const res = await Api.Auth.changePassword({ oldPassword: oldPwd, newPassword: newPwd });
      okBtn.disabled = false;
      if (res.code !== 0) { toast(res.message || '修改失败，请稍后重试', 'error'); return; }
      const s = Auth.current();
      if (s && res.data && res.data.token) Auth.set(Object.assign({}, s, { token: res.data.token }));
      m.close();
      toast('密码已修改', 'success');
    });
    oldEl.focus();
  }

  function init({ role, page }) {
    const s = Auth.current();
    if (!s) { location.replace('../index.html'); return; }
    if (s.role !== role) {
      location.replace(s.role === 'admin' ? '../admin/dashboard.html' : '../student/dashboard.html');
      return;
    }
    document.body.dataset.page = page;
    document.querySelectorAll('.nav-item[data-nav]').forEach((a) => {
      const on = a.dataset.nav === page;
      a.classList.toggle('active', on);
      if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    const nameEl = document.getElementById('navUserName');
    if (nameEl) nameEl.textContent = s.name;
    const av = document.getElementById('navAvatar');
    if (av) av.textContent = initials(s.name);
    const out = document.getElementById('logoutBtn');
    if (out) {
      // 在「退出」旁边插入「修改密码」入口，所有页面共用同一实现
      if (!document.getElementById('pwdBtn')) {
        const pwdBtn = document.createElement('button');
        pwdBtn.className = 'icon-btn';
        pwdBtn.id = 'pwdBtn';
        pwdBtn.type = 'button';
        pwdBtn.title = '修改密码';
        pwdBtn.setAttribute('aria-label', '修改密码');
        pwdBtn.innerHTML = svg('shield');
        out.parentNode.insertBefore(pwdBtn, out);
        pwdBtn.addEventListener('click', changePasswordDialog);
      }
      out.addEventListener('click', () => {
        if (window.Api && Api.Auth.logout) { try { Api.Auth.logout(); } catch (e) { /* 忽略网络异常 */ } }
        Auth.clear();
        location.replace('../index.html');
      });
    }
    renderIcons();
  }

  return {
    init, renderIcons, svg, Auth, toast, modal, confirm,
    esc, param, fmtDate, fmtShort, initials,
    statusTag, modeTag, levelTag,
    qs: (sel, root = document) => root.querySelector(sel),
    qsa: (sel, root = document) => Array.from(root.querySelectorAll(sel))
  };
})();
