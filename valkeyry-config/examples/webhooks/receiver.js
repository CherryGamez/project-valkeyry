#!/usr/bin/env node
/**
 * Tiny audit-webhook receiver for valkeyry-config.
 *
 * Listens on :9099 and prints + HMAC-verifies every payload published by
 * `AuditWebhookPublisher`. Used by the README in this directory and by the
 * Postman collection's "Audit webhooks" folder.
 *
 *   node receiver.js                # default port 9099 + secret from $SECRET
 *   PORT=9100 SECRET=hunter2 node receiver.js
 *
 * Verifying the signature manually matches Spring's `AuditWebhookPublisher`:
 *
 *     hmac = HMAC_SHA256(body, secret)
 *     hex  = lowercase hex of hmac
 *
 *     Expected request header: X-Valkeyry-Signature: sha256=<hex>
 */
const http  = require('http');
const crypto = require('crypto');

const PORT   = parseInt(process.env.PORT   || '9099', 10);
const SECRET = process.env.SECRET || 'local-dev-hmac-secret-32-bytes-long';

const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
        res.writeHead(405).end('only POST');
        return;
    }
    let chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
        const raw = Buffer.concat(chunks);
        const got = req.headers['x-valkeyry-signature'] || '';
        const want = 'sha256=' + crypto.createHmac('sha256', SECRET).update(raw).digest('hex');

        // Constant-time compare — but `timingSafeEqual` throws if buffers differ in length,
        // so check that first (avoids a 500 when the signature header is absent).
        const ok = !!got &&
                   got.length === want.length &&
                   crypto.timingSafeEqual(Buffer.from(got), Buffer.from(want));

        console.log('\n────────────────────────────────────────────────');
        console.log('[%s] %s %s', new Date().toISOString(), req.method, req.url);
        console.log('X-Valkeyry-Event   :', req.headers['x-valkeyry-event']    || '(missing)');
        console.log('X-Valkeyry-Tenant  :', req.headers['x-valkeyry-tenant']   || '(missing)');
        console.log('X-Valkeyry-Request :', req.headers['x-valkeyry-request-id'] || '(missing)');
        console.log('X-Valkeyry-Sig.    :', got || '(missing)');
        console.log('Signature valid?   :', ok ? 'YES' : 'NO');
        console.log('Body               :', raw.toString('utf8'));

        res.writeHead(ok ? 200 : 401, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ received: true, signatureValid: ok }));
    });
});

server.listen(PORT, () => {
    console.log(`audit-webhook receiver listening on :${PORT}`);
    console.log(`HMAC secret: ${SECRET.length} bytes  (override with $SECRET)`);
    console.log('register with the platform:');
    console.log(`  curl -X POST http://localhost:8081/api/v1/tenants/demo-tenant/webhooks \\`);
    console.log(`       -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \\`);
    console.log(`       -d '{"url":"http://host.docker.internal:${PORT}/audit","secret":"${SECRET}"}'`);
});
