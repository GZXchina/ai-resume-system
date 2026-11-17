/* ============================================================
   store.js — 本地数据持久化（仅在原型中使用）
   真实后端接入后，删除本文件，由 api.js 改为请求服务端即可。
   ============================================================ */

window.Store = (() => {
  const KEY = 'ars_db_v2';
  const clone = (o) => JSON.parse(JSON.stringify(o));

  function load() {
    try {
      const raw = localStorage.getItem(KEY);
      if (raw) return JSON.parse(raw);
    } catch (e) { /* 忽略损坏数据，重新播种 */ }
    const seed = clone(window.MOCK);
    localStorage.setItem(KEY, JSON.stringify(seed));
    return seed;
  }

  let db = load();

  return {
    get db() { return db; },
    save() { localStorage.setItem(KEY, JSON.stringify(db)); },
    reset() {
      localStorage.removeItem(KEY);
      localStorage.removeItem('ars_session');
      db = load();
      return db;
    },
    clone
  };
})();
