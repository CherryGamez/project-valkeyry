/**
 * Lightweight session helper for the React Admin Console.
 *
 * Three login paths are supported:
 *   1. OIDC PKCE (production) — token + identity come from react-oidc-context.
 *   2. Local dev user — credentials sourced from `REACT_APP_DEV_USERS`
 *      (JSON: { "<username>": { "password": "...", "role": "ADMIN|OPERATOR", ... }, … }).
 *      Falls back to admin/admin + operator/operator ONLY when `NODE_ENV !== "production"`.
 *   3. Power-user JWT paste — operator pastes a real bearer JWT in the login page.
 *
 * The Bearer token (when present) is stored via `setToken()` (sessionStorage — survives
 * reloads in the same tab, wiped on tab close). Sensitive tokens belong in httpOnly cookies
 * for production; sessionStorage is a pragmatic compromise that limits XSS-replay blast radius
 * vs. localStorage. The session metadata (identity/email/role) is non-sensitive.
 */

import { setToken } from "@/lib/ipaasClient";

const SESSION_KEY = "valkeyry.session";

/**
 * Local dev users — sourced from env at build time. NEVER read in production.
 * Set `REACT_APP_DEV_USERS='{"alice":{"password":"…","role":"ADMIN"}}'` to override.
 */
function loadDevUsers() {
  const raw = process.env.REACT_APP_DEV_USERS;
  if (raw) {
    try { return JSON.parse(raw); }
    catch { /* fall through to defaults */ }
  }
  // Only ship default dev creds in non-production bundles.
  if (process.env.NODE_ENV !== "production") {
    return {
      admin:    { password: "admin",    role: "ADMIN",    displayName: "admin",    email: "admin@valkeyry.local" },
      operator: { password: "operator", role: "OPERATOR", displayName: "operator", email: "operator@valkeyry.local" },
    };
  }
  return {};
}
const LOCAL_USERS = loadDevUsers();

export function getSession() {
  try { return JSON.parse(sessionStorage.getItem(SESSION_KEY)); }
  catch { return null; }
}

export function setSession(session) {
  if (!session) {
    sessionStorage.removeItem(SESSION_KEY);
  } else {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
  }
}

export function clearSession() {
  setSession(null);
  setToken("");
}

/** Validate local username/password; create + persist a session on success. */
export function loginLocal(username, password) {
  const user = LOCAL_USERS[username?.toLowerCase()?.trim()];
  if (!user || user.password !== password) {
    throw new Error("Invalid username or password.");
  }
  const session = {
    kind: "LOCAL",
    identity: user.displayName || username,
    email: user.email,
    role: user.role,
    issuedAt: new Date().toISOString(),
  };
  setSession(session);
  // No real bearer token in local mode; backend must run with allow-anonymous=true.
  setToken("");
  return session;
}

/** Power-user path: persist a raw JWT pasted by the operator. */
export function loginWithToken(token) {
  if (!token || token.trim().length < 8) {
    throw new Error("Paste a valid bearer JWT.");
  }
  const session = {
    kind: "JWT_PASTE",
    identity: "jwt-user",
    issuedAt: new Date().toISOString(),
  };
  setSession(session);
  setToken(token.trim());
  return session;
}

/** Called by App.js when react-oidc-context reports a successful sign-in. */
export function recordOidcSession(oidcUser) {
  if (!oidcUser) return;
  const profile = oidcUser.profile || {};
  const session = {
    kind: "OIDC",
    identity: profile.preferred_username || profile.email || profile.sub || "oidc-user",
    email: profile.email,
    role: (profile.roles && profile.roles[0]) || profile.role || null,
    issuedAt: new Date().toISOString(),
  };
  setSession(session);
  if (oidcUser.access_token) setToken(oidcUser.access_token);
  return session;
}
