import express from "express";
import { PrismaClient } from "./generated/prisma";
import http from "http";
import { WebSocketServer } from "ws";
import cors from "cors";

const app = express();
const prisma = new PrismaClient();

// Permissive CORS (allow all origins). This is safe for this demo API.
app.use(cors({
    origin: true,
    methods: ["GET", "POST", "PATCH", "DELETE", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization"],
    credentials: true,
}));
app.options("*", cors());

app.use(express.json());

app.get('/', (_req, res) => {
    res.send('Hello World');
});

app.get("/health", (_req, res) => {
    res.json({ status: "ok" });
});

// Create HTTP server so we can attach WebSocket server to the same port
const server = http.createServer(app);

type ContactEvent =
    | { type: "contact.created"; payload: { id: string; firstName: string; lastName: string; phoneNumber: string } }
    | { type: "contact.updated"; payload: { id: string; firstName: string; lastName: string; phoneNumber: string; editedAt: string } }
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
            select: {
                id: true,
                firstName: true,
                lastName: true,
                phoneNumber: true,
                isSynced: true,
                isSoftDeleted: true,
                pendingChange: true,
                editedAt: true,
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
            data: { id, firstName, lastName, phoneNumber, isSynced: false, pendingChange: 'created' },
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

// Update and broadcast
app.patch("/contacts/:id", async (req, res) => {
    const id = req.params.id;
    const { firstName, lastName, phoneNumber, editedAt } = req.body || {};
    if (!id || typeof id !== "string") {
        return res.status(400).json({ error: "invalid id" });
    }
    const incomingTs = editedAt ? new Date(editedAt).getTime() : 0;
    try {
        const existing = await prisma.contact.findUnique({ where: { id } });
        if (!existing) throw { code: 'P2025' };
        const currentTs = existing.editedAt ? new Date(existing.editedAt as any).getTime() : 0;
        const shouldApply = !existing.editedAt || (incomingTs && incomingTs > currentTs);
        const updated = await prisma.contact.update({
            where: { id },
            data: shouldApply ? {
                ...(firstName ? { firstName } : {}),
                ...(lastName ? { lastName } : {}),
                ...(phoneNumber ? { phoneNumber } : {}),
                isSynced: false,
                pendingChange: 'updated',
                editedAt: incomingTs ? new Date(incomingTs) : new Date(),
            } : {},
        });
        res.status(200).json(updated);
        broadcast({ type: 'contact.updated', payload: { id: updated.id, firstName: updated.firstName, lastName: updated.lastName, phoneNumber: updated.phoneNumber, editedAt: (updated as any).editedAt?.toISOString?.() || new Date().toISOString() } } as ContactEvent);
    } catch (e: any) {
        // Only fallback to create when the record doesn't exist (P2025)
        if (e?.code === 'P2025') {
            // For upsert via PATCH, require full data to create
            if (!firstName || !lastName || !phoneNumber) {
                return res.status(400).json({ error: 'missing fields for upsert create' });
            }
            try {
                const created = await prisma.contact.create({
                    data: { id, firstName, lastName, phoneNumber, isSynced: false, pendingChange: 'created', editedAt: null },
                });
                res.status(201).json(created);
                broadcast({ type: 'contact.created', payload: { id: created.id, firstName: created.firstName, lastName: created.lastName, phoneNumber: created.phoneNumber } } as ContactEvent);
            } catch (ce: any) {
                // If another request created it concurrently, return the existing row (idempotent)
                if (ce?.code === 'P2002') {
                    const existing = await prisma.contact.findUnique({ where: { id } });
                    if (existing) return res.status(200).json(existing);
                    return res.status(409).json({ error: 'duplicate id' });
                }
                return res.status(500).json({ error: 'failed to create on update fallback' });
            }
        } else {
            return res.status(500).json({ error: 'failed to update' });
        }
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
        await prisma.contact.update({ where: { id }, data: { isSoftDeleted: true, pendingChange: 'deleted', isSynced: false } });
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
            await prisma.contact.update({ where: { id }, data: { isSynced: true, pendingChange: null } });
            console.log('THESE ARE ALL THE CONTACTS AFTER CREATED ACK: \n\n')
            console.log(await prisma.contact.findMany());
            return res.status(204).send();
        } catch (e: any) {
            if (e?.code === 'P2025') return res.status(404).json({ error: 'not found' });
            return res.status(500).json({ error: 'failed to ack created' });
        }
    } else if (type === 'updated') {
        try {
            await prisma.contact.update({ where: { id }, data: { isSynced: true, pendingChange: null } });
            console.log('THESE ARE ALL THE CONTACTS AFTER UPDATED ACK: \n\n')
            console.log(await prisma.contact.findMany());
            return res.status(204).send();
        } catch (e: any) {
            if (e?.code === 'P2025') return res.status(404).json({ error: 'not found' });
            return res.status(500).json({ error: 'failed to ack updated' });
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
