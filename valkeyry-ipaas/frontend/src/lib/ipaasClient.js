/** Single source of truth for the Spring iPaaS base URL + auth headers. */
const BASE_URL = process.env.REACT_APP_IPAAS_BASE_URL || "http://localhost:8080";

const tokenKey = "valkeyry.bearer-token";
// sessionStorage (not localStorage) — tokens are erased on tab close, limiting XSS replay
// window. For production, prefer httpOnly cookies issued by the OIDC IdP.
export const getToken = () => sessionStorage.getItem(tokenKey) || "";
export const setToken = (t) => sessionStorage.setItem(tokenKey, t || "");

const ctxKey = "valkeyry.context";
// Non-sensitive tenant/project selection — localStorage is fine so it survives tab close.
export const getContext = () => {
  try { return JSON.parse(localStorage.getItem(ctxKey)) || { tenantId: "", projectId: "" }; }
  catch { return { tenantId: "", projectId: "" }; }
};
export const setContext = (ctx) => localStorage.setItem(ctxKey, JSON.stringify(ctx));

const headers = (extra = {}) => {
  const h = { "Content-Type": "application/json", ...extra };
  const t = getToken();
  if (t) h["Authorization"] = `Bearer ${t}`;
  return h;
};

/** Wraps a fetch() promise to normalize errors: network failures become
 *  `Error("<label>: network unreachable")`; non-2xx becomes `Error("<label>: <status>")`. */
const call = async (label, fetchPromise) => {
  let r;
  try { r = await fetchPromise; }
  catch { throw new Error(`${label}: network unreachable`); }
  if (!r.ok) throw new Error(`${label}: ${r.status}`);
  return r.json();
};

export const api = {
  base: BASE_URL,

  declareQueue: (t, p, body) => call("declareQueue",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/queues/declare`,
      { method: "POST", headers: headers(), body: JSON.stringify(body) })),

  listQueues: (t, p) => call("listQueues",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/queues`, { headers: headers() })),

  listTopologies: (t, p) => call("listTopologies",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/topologies`, { headers: headers() })),

  upsertTopology: (t, p, body) => call("upsertTopology",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/topologies`,
      { method: "POST", headers: headers(), body: JSON.stringify(body) })),

  deployTopology: (t, p, name) => call("deploy",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/topologies/${name}/deploy`,
      { method: "POST", headers: headers() })),

  undeployTopology: (t, p, name) => call("undeploy",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/topologies/${name}/undeploy`,
      { method: "POST", headers: headers() })),

  browseDlq: (t, p, queue, limit = 20) => call("browseDlq",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/dlq/${queue}/messages?limit=${limit}`,
      { headers: headers() })),

  bulkRetry: (t, p, queue, items) => call("bulkRetry",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/dlq/${queue}/bulk-retry`,
      { method: "POST", headers: headers(), body: JSON.stringify(items) })),

  multiPublish: (targets, payload) => call("multiPublish",
    fetch(`${BASE_URL}/api/v1/multi-publish`,
      { method: "POST", headers: headers(), body: JSON.stringify({ targets, payload }) })),

  /** Returns an EventSource for the SSE metric stream (token attached as query for SSE). */
  openMetricsStream(t, p) {
    // EventSource cannot set Authorization headers, so we pass the token as a query param
    // for local-dev convenience when `ipaas.security.allow-anonymous=true`.
    const url = `${BASE_URL}/api/v1/${t}/${p}/metrics/stream`;
    return new EventSource(url, { withCredentials: false });
  },

  // -------------------- DLQ summary (Kafka AdminClient) --------------------
  dlqSummary: (t, p, queue) => call("dlqSummary",
    fetch(`${BASE_URL}/api/v1/${t}/${p}/dlq/${queue}/summary`, { headers: headers() })),

  // -------------------- Operator Copilot --------------------
  copilotProviders: () => call("copilotProviders",
    fetch(`${BASE_URL}/api/v1/copilot/providers`, { headers: headers() })),

  copilotTools: () => call("copilotTools",
    fetch(`${BASE_URL}/api/v1/copilot/tools`, { headers: headers() })),

  copilotHistory: (sid) => call("copilotHistory",
    fetch(`${BASE_URL}/api/v1/copilot/session/${encodeURIComponent(sid)}/history`,
      { headers: headers() })),

  copilotReset: (sid) => call("copilotReset",
    fetch(`${BASE_URL}/api/v1/copilot/session/${encodeURIComponent(sid)}/reset`,
      { method: "POST", headers: headers() })),

  /**
   * Streams Copilot replies via fetch ReadableStream (SSE text/event-stream).
   * Calls `onChunk(text)` per content fragment; resolves when the stream ends.
   */
  async copilotStream({ sessionId, message, provider }, { onChunk, onDone, onError } = {}) {
    try {
      const r = await fetch(`${BASE_URL}/api/v1/copilot/stream`, {
        method: "POST",
        headers: headers({ "Accept": "text/event-stream" }),
        body: JSON.stringify({ sessionId, message, provider }),
      });
      if (!r.ok || !r.body) {
        onError && onError(new Error(`copilotStream: ${r.status}`));
        return;
      }
      await pumpSseStream(r.body, { onChunk, onDone, onError });
    } catch (e) {
      onError && onError(e);
    }
  },
};

/**
 * Reads SSE `event:`/`data:` frames from a ReadableStream and dispatches into callbacks.
 * Extracted from copilotStream() to keep cyclomatic complexity bounded.
 */
async function pumpSseStream(body, { onChunk, onDone, onError }) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const consumed = drainFrames(buf, { onChunk, onDone, onError });
    if (consumed.terminate) return;
    buf = consumed.remaining;
  }
  onDone && onDone();
}

/** Drain whole `event\n\n` frames from {@code buf}; return tail + terminate flag. */
function drainFrames(buf, { onChunk, onDone, onError }) {
  let idx;
  while ((idx = buf.indexOf("\n\n")) !== -1) {
    const block = buf.slice(0, idx);
    buf = buf.slice(idx + 2);
    const { event, data } = parseSseBlock(block);
    if (event === "chunk" && data) onChunk && onChunk(data);
    else if (event === "done")    { onDone  && onDone();  return { remaining: buf, terminate: true }; }
    else if (event === "error")   { onError && onError(new Error(data)); return { remaining: buf, terminate: true }; }
  }
  return { remaining: buf, terminate: false };
}

/** Parses a single SSE block into `{event, data}` (data may span multiple `data:` lines). */
function parseSseBlock(block) {
  let event = "chunk", data = "";
  for (const line of block.split("\n")) {
    if      (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:"))  data += line.slice(5).trimStart();
  }
  return { event, data };
}
