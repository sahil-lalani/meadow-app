import WebSocket from 'ws';

// Simple WebSocket connectivity checker for your backend.
// Usage:
//   WS_URL=wss://meadow-app-production.up.railway.app npx ts-node backend/ws-check.ts
// or just run without WS_URL to use the default.

const DEFAULT_URL = 'wss://meadow-app-production.up.railway.app';
const url = process.env.WS_URL || DEFAULT_URL;

console.log(`[ws-check] Connecting to ${url} ...`);

const ws = new WebSocket(url);

let didOpen = false;
const connectTimeoutMs = Number(process.env.WS_CONNECT_TIMEOUT_MS || 8000);
const stayOpenMs = Number(process.env.WS_STAY_OPEN_MS || 5000);

const connectTimer = setTimeout(() => {
    console.error(`[ws-check] Connection timed out after ${connectTimeoutMs}ms`);
    try { ws.terminate(); } catch {}
    process.exit(2);
}, connectTimeoutMs);

ws.on('open', () => {
    didOpen = true;
    clearTimeout(connectTimer);
    console.log('[ws-check] WebSocket open');
    // Optionally send a ping to verify liveness at the protocol level
    try { ws.ping(); } catch {}
    // Stay connected briefly, then close gracefully
    setTimeout(() => {
        console.log('[ws-check] Closing after brief hold');
        try { ws.close(1000, 'ws-check done'); } catch {}
    }, stayOpenMs);
});

ws.on('message', (data) => {
    try {
        const text = typeof data === 'string' ? data : data.toString();
        console.log(`[ws-check] message: ${text}`);
    } catch {
        console.log('[ws-check] message (binary)');
    }
});

ws.on('ping', () => {
    console.log('[ws-check] <-- ping');
});

ws.on('pong', () => {
    console.log('[ws-check] --> pong');
});

ws.on('error', (err) => {
    console.error('[ws-check] error:', (err as any)?.message || err);
});

ws.on('close', (code, reason) => {
    const reasonText = reason?.toString?.() || '';
    console.log(`[ws-check] close: code=${code} reason=${reasonText}`);
    // Exit with 0 if we connected at least once, else 1
    process.exit(didOpen ? 0 : 1);
});

process.on('SIGINT', () => {
    console.log('\n[ws-check] SIGINT');
    try { ws.close(1000, 'SIGINT'); } catch {}
    setTimeout(() => process.exit(didOpen ? 0 : 1), 250);
});


