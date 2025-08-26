import express from "express";
import { PrismaClient } from "./generated/prisma";
import http from "http";
import { WebSocketServer } from "ws";

const app = express();
const prisma = new PrismaClient();

app.use(express.json());

app.get("/health", (_req, res) => {
    res.json({ status: "ok" });
});

// Create HTTP server so we can attach WebSocket server to the same port
const server = http.createServer(app);

type ContactEvent =
    | { type: "contact.created"; payload: { id: string; firstName: string; lastName: string; phoneNumber: string } }
    | { type: "contact.deleted"; payload: { id: string } };

const wss = new WebSocketServer({ server });

function broadcast(event: ContactEvent) {
    const message = JSON.stringify(event);
    const numClients = wss.clients.size;
    console.log(`[ws] broadcasting ${event.type} to ${numClients} client(s)`);
    wss.clients.forEach((client: any) => {
        if (client.readyState === 1) {
            try {
                client.send(message);
            } catch (err) {
                console.error('[ws] send error', err);
            }
        }
    });
}

wss.on('connection', (socket: any, req: any) => {
    console.log('[ws] client connected', req?.socket?.remoteAddress);
    try {
        const hello = { type: 'server.hello', payload: { ts: Date.now() } };
        socket.send(JSON.stringify(hello));
    } catch (e) {
        console.error('[ws] hello send failed', e);
    }
    socket.on('close', () => console.log('[ws] client disconnected'));
    socket.on('error', (e: any) => console.error('[ws] client error', e));
});

// Simple debug endpoint to trigger a broadcast manually
app.get('/debug/broadcast', (_req, res) => {
    const event = { type: 'contact.created', payload: { id: 'debug-id', firstName: 'Debug', lastName: 'User', phoneNumber: '000' } } as ContactEvent;
    broadcast(event);
    res.json({ ok: true });
});

// Return server-side backlog: items needing client processing
app.get('/contacts/pending', async (_req, res) => {
    try {
        const pending = await prisma.contact.findMany({
            where: {
                OR: [
                    { isSynced: false },
                    { isSoftDeleted: true },
                ],
            },
        });
        res.json(pending);
    } catch (e: any) {
        res.status(500).json({ error: 'failed to load pending' });
    }
});

// Broadcast on create
app.post("/contacts", async (req, res) => {
    const { id, firstName, lastName, phoneNumber } = req.body || {};
    if (!firstName || !lastName || !phoneNumber) {
        return res.status(400).json({ error: "firstName, lastName, phoneNumber required" });
    }
    try {
        const created = await prisma.contact.create({
            // For server->client semantics, mark isSynced=false until client ACKs receipt
            data: { id, firstName, lastName, phoneNumber, isSynced: false },
        });
        res.status(201).json(created);
        console.log('THESE ARE ALL THE CONTACTS RIGHT NOW: \n\n')
        console.log(await prisma.contact.findMany());
        console.log('BROADCASTING THE CONTACT CREATED EVENT: \n\n', created)
        broadcast({ type: "contact.created", payload: { id: created.id, firstName: created.firstName, lastName: created.lastName, phoneNumber: created.phoneNumber } });
    } catch (e: any) {
        if (e?.code === "P2002") return res.status(409).json({ error: "duplicate id" });
        res.status(500).json({ error: "failed to create" });
    }
});

// Soft delete and broadcast
app.delete("/contacts/:id", async (req, res) => {
    const id = req.params.id;
    if (!id || typeof id !== "string") {
        return res.status(400).json({ error: "invalid id" });
    }
    try {
        // Mark as soft-deleted first so we can wait for client ACK before final removal
        await prisma.contact.update({ where: { id }, data: { isSoftDeleted: true } });
        console.log('THESE ARE ALL THE CONTACTS AFTER SOFT DELETED: \n\n')
        console.log(await prisma.contact.findMany());
        broadcast({ type: "contact.deleted", payload: { id } });
        // Do not hard-delete here; the client will ACK and we will finalize then
        res.status(204).send();
    } catch (e: any) {
        if (e?.code === "P2025") return res.status(404).json({ error: "not found" });
        res.status(500).json({ error: "failed to delete" });
    }
});

// ACK endpoint to confirm client processed events
app.post('/contacts/:id/ack', async (req, res) => {
    const id = req.params.id;
    const { type } = req.body || {};
    if (!id || typeof id !== 'string') {
        return res.status(400).json({ error: 'invalid id' });
    }
    if (type === 'created') {
        try {
            await prisma.contact.update({ where: { id }, data: { isSynced: true } });
            console.log('THESE ARE ALL THE CONTACTS AFTER CREATED ACK: \n\n')
            console.log(await prisma.contact.findMany());
            return res.status(204).send();
        } catch (e: any) {
            if (e?.code === 'P2025') return res.status(404).json({ error: 'not found' });
            return res.status(500).json({ error: 'failed to ack created' });
        }
    } else if (type === 'deleted') {
        try {
            await prisma.contact.delete({ where: { id } });
            console.log('THESE ARE ALL THE CONTACTS AFTER DELETED ACK: \n\n')
            console.log(await prisma.contact.findMany());
            return res.status(204).send();
        } catch (e: any) {
            if (e?.code === 'P2025') return res.status(204).send(); // idempotent
            return res.status(500).json({ error: 'failed to ack deleted' });
        }
    } else {
        return res.status(400).json({ error: 'invalid type' });
    }
});

const PORT = Number(process.env.PORT) || 3000;
server.listen(PORT, () => {
    console.log(`listening on http://localhost:${PORT}`);
});
