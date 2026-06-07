/* Valkeyry shared auth helpers — JWT storage, fetch interceptor, role checks.
 *
 * Pages include <script src="/assets/auth.js"></script> and then either:
 *   - call vk.requireAuth({ role: 'admin' })  before rendering admin UI
 *   - rely on the global htmx:configRequest hook (declared below) to attach
 *     the bearer token automatically to every HTMX-driven request.
 */
(function () {
  const STORAGE_KEY = 'vk.auth.v1';
  // Per-tenant connection (set from the Console's "Connect" modal). When a JWT 401
  // clears the session we MUST clear these too — otherwise the next successful login
  // immediately fires another tenant API call with the old (now-orphaned, possibly
  // bogus) X-API-Key / bearer, gets 401 again, and ejects the user straight back to
  // /login.html. Keep these in sync with the LS_KEYS map in index.html.
  const TENANT_KEYS = ['vk.tenant', 'vk.auth.mode', 'vk.auth.token'];

  const vk = (window.vk = window.vk || {});

  vk.getAuth = function () {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null'); }
    catch (e) { return null; }
  };
  vk.setAuth = function (auth) { localStorage.setItem(STORAGE_KEY, JSON.stringify(auth)); };
  vk.clearAuth = function () {
    localStorage.removeItem(STORAGE_KEY);
    // Also drop the per-tenant connection — see TENANT_KEYS comment above.
    TENANT_KEYS.forEach(k => localStorage.removeItem(k));
  };
  vk.token = function () { const a = vk.getAuth(); return a && a.token ? a.token : null; };
  vk.username = function () { const a = vk.getAuth(); return a ? a.username : null; };
  vk.role = function () { const a = vk.getAuth(); return a ? (a.role || 'reader') : null; };
  vk.isAdmin = function () { return vk.role() === 'admin'; };
  vk.isWriter = function () { return vk.role() === 'writer' || vk.role() === 'admin'; };
  vk.tenants = function () { const a = vk.getAuth(); return (a && a.tenants) || []; };

  vk.requireAuth = function (opts) {
    opts = opts || {};
    const a = vk.getAuth();
    if (!a || !a.token) { window.location.href = '/login.html'; return false; }
    if (opts.role === 'admin' && a.role !== 'admin') {
      window.location.href = '/?denied=admin';
      return false;
    }
    return true;
  };

  vk.logout = function () {
    fetch('/api/v1/auth/logout', { method: 'POST' }).catch(() => {});
    vk.clearAuth();
    window.location.href = '/login.html';
  };

  // Automatic bearer-token injection on every HTMX-driven request.
  document.addEventListener('htmx:configRequest', function (evt) {
    const t = vk.token();
    if (t) evt.detail.headers['Authorization'] = 'Bearer ' + t;
  });

  // Surface a friendly redirect on 401 (token expired / revoked).
  document.addEventListener('htmx:responseError', function (evt) {
    if (evt.detail && evt.detail.xhr && evt.detail.xhr.status === 401) {
      vk.clearAuth();
      window.location.href = '/login.html?expired=1';
    }
  });

  // Convenience fetch wrapper for hand-rolled JS that auto-attaches the bearer.
  vk.fetch = function (url, opts) {
    opts = opts || {};
    opts.headers = Object.assign({}, opts.headers || {});
    const t = vk.token();
    if (t) opts.headers['Authorization'] = 'Bearer ' + t;
    return fetch(url, opts).then(function (r) {
      if (r.status === 401) {
        vk.clearAuth();
        window.location.href = '/login.html?expired=1';
      }
      return r;
    });
  };
})();
