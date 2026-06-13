// ─────────────────────────────────────────────────────────────────────────────
// Valkeyry Config preview front-end (Emergent pod only)
// ─────────────────────────────────────────────────────────────────────────────
// • Serves the production HTML/JS console from
//     /app/valkeyry-config/src/main/resources/static/
// • Reverse-proxies every backend path (/api, /v3, /swagger-ui*, /actuator,
//     /webjars) to the FastAPI mock running at http://127.0.0.1:8001
//
// This file exists *only* to make the preview pod work — it is not used in
// production. Production runs the real Java/Spring Boot service which serves
// the same HTML directly. Keep zero external deps; node's built-in `http` is
// enough.
// ─────────────────────────────────────────────────────────────────────────────
const http  = require('http');
const fs    = require('fs');
const path  = require('path');

const STATIC_ROOT  = '/app/valkeyry-config/src/main/resources/static';
const BACKEND_HOST = '127.0.0.1';
const BACKEND_PORT = 8001;
const LISTEN_PORT  = parseInt(process.env.PORT || '3000', 10);
const LISTEN_HOST  = process.env.HOST || '0.0.0.0';

// Anything starting with one of these prefixes is forwarded to the backend.
// Match either a clean prefix (e.g. /api) or a prefix immediately followed by
// `/`, `.` or `?` so `/swagger-ui.html` and `/v3/api-docs.yaml` are caught too.
const PROXY_PREFIXES = ['/api', '/v3', '/swagger-ui', '/webjars', '/actuator'];

function shouldProxy(reqUrl) {
    const pathOnly = reqUrl.split('?')[0];
    return PROXY_PREFIXES.some(p => {
        if (pathOnly === p) return true;
        if (pathOnly.startsWith(p + '/')) return true;
        if (pathOnly.startsWith(p + '.')) return true;          // /swagger-ui.html, /v3/api-docs.yaml
        return false;
    });
}

const MIME = {
    '.html':'text/html; charset=utf-8',
    '.js':'application/javascript; charset=utf-8',
    '.css':'text/css; charset=utf-8',
    '.json':'application/json',
    '.svg':'image/svg+xml',
    '.png':'image/png',
    '.ico':'image/x-icon',
    '.md':'text/markdown; charset=utf-8',
    '.map':'application/json'
};

function serveStatic(req, res) {
    // Map / to index.html. Everything else is read relative to STATIC_ROOT.
    let rel = req.url.split('?')[0];
    if (rel === '/' || rel === '') rel = '/index.html';
    // Prevent path traversal.
    if (rel.includes('..')) { res.writeHead(400); return res.end('Bad path'); }
    const file = path.join(STATIC_ROOT, rel);
    fs.readFile(file, (err, body) => {
        if (err) {
            // SPA-style fallback: any unknown path serves index.html so deep links work.
            return fs.readFile(path.join(STATIC_ROOT, 'index.html'), (e2, idx) => {
                if (e2) { res.writeHead(404); return res.end('Not found'); }
                res.writeHead(200, { 'Content-Type': MIME['.html'] });
                res.end(idx);
            });
        }
        const ext = path.extname(file).toLowerCase();
        res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
        res.end(body);
    });
}

function proxy(req, res) {
    const opts = {
        host: BACKEND_HOST,
        port: BACKEND_PORT,
        path: req.url,
        method: req.method,
        headers: { ...req.headers, host: `${BACKEND_HOST}:${BACKEND_PORT}` }
    };
    const upstream = http.request(opts, up => {
        res.writeHead(up.statusCode || 502, up.headers);
        up.pipe(res);
    });
    upstream.on('error', err => {
        res.writeHead(502, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'proxy-error', detail: String(err) }));
    });
    req.pipe(upstream);
}

const server = http.createServer((req, res) => {
    try {
        if (shouldProxy(req.url)) return proxy(req, res);
        return serveStatic(req, res);
    } catch (e) {
        res.writeHead(500); res.end(String(e));
    }
});

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
    console.log(`[preview-frontend] static=${STATIC_ROOT} → http://${LISTEN_HOST}:${LISTEN_PORT}`);
    console.log(`[preview-frontend] proxy(${PROXY_PREFIXES.join('|')}) → http://${BACKEND_HOST}:${BACKEND_PORT}`);
});
