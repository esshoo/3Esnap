/* Pure functions used by the app and the zero-dependency Node test suite. */
(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SnapCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const completed = rows => rows.filter(r => r && r.status === 'completed');
  const normalize = value => String(value || '').normalize('NFKD').replace(/[\u064B-\u065F\u0670]/g, '').toLocaleLowerCase().trim();
  function selectFiles(rows, options = {}) {
    const query = normalize(options.query), filter = options.filter || 'all';
    const list = completed(rows).filter(r =>
      (filter === 'all' || r.format === filter) &&
      (!query || normalize([r.title, r.filename, r.sourceUrl].join(' ')).includes(query)));
    const sort = options.sort || 'newest';
    const compare = {
      newest: (a,b) => Number(b.createdAt)-Number(a.createdAt) || Number(b.id)-Number(a.id),
      oldest: (a,b) => Number(a.createdAt)-Number(b.createdAt) || Number(a.id)-Number(b.id),
      name: (a,b) => String(a.title || a.filename).localeCompare(String(b.title || b.filename),'ar') || Number(a.id)-Number(b.id),
      size: (a,b) => Number(b.size)-Number(a.size) || Number(b.id)-Number(a.id)
    }[sort] || ((a,b) => Number(b.createdAt)-Number(a.createdAt));
    return list.sort(compare);
  }
  function formatBytes(value) {
    let n = Number(value);
    if (!Number.isFinite(n) || n <= 0) return '0 B';
    const units = ['B','KB','MB','GB','TB'];
    const index = Math.min(units.length-1, Math.floor(Math.log(n)/Math.log(1024)));
    n /= 1024 ** index;
    return `${n >= 10 || index === 0 ? Math.round(n) : n.toFixed(1)} ${units[index]}`;
  }
  function formatDuration(value) {
    const seconds = Math.max(0, Math.floor(Number(value) || 0));
    if (!seconds) return '—';
    const parts = [Math.floor(seconds/60)%60,seconds%60].map(n => String(n).padStart(2,'0'));
    if(seconds >= 3600) parts.unshift(String(Math.floor(seconds/3600)));
    return parts.join(':');
  }
  function isActive(status) { return status === 'queued' || status === 'downloading'; }
  function stats(rows) {
    const files = completed(rows);
    return { count: files.length, bytes: files.reduce((sum,r) => sum + (r.missing ? 0 : Math.max(0,Number(r.size)||0)),0),
      videos: files.filter(r=>r.format==='video').length, audio: files.filter(r=>r.format==='audio').length };
  }
  function safeImage(value, fallback = 'media-placeholder.svg') {
    if(typeof value !== 'string') return fallback;
    try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password ? url.href : fallback; }
    catch (_) { return fallback; }
  }
  return { completed, normalize, selectFiles, formatBytes, formatDuration, isActive, stats, safeImage };
});
