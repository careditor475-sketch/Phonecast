// One Durable Object per cast code. It only relays small signaling messages
// (offer / answer / ICE) between the phone ("sender") and the watchers ("viewer").
// Video never passes through here; it flows peer-to-peer over WebRTC.

const ALLOWED = new Set(["offer", "answer", "ice"]);
const MAX_BYTES = 64 * 1024;

export class Room {
  constructor(state) {
    this.state = state;
  }

  sender(except) {
    return this.state
      .getWebSockets("sender")
      .find((w) => w !== except && w.readyState === 1);
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }
    const url = new URL(request.url);
    const role = url.searchParams.get("role") === "sender" ? "sender" : "viewer";
    const id = crypto.randomUUID().slice(0, 8);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    if (role === "sender") {
      for (const old of this.state.getWebSockets("sender")) {
        try { old.close(1000, "replaced"); } catch {}
      }
    }

    this.state.acceptWebSocket(server, [role, "id:" + id]);
    server.serializeAttachment({ id, role });

    if (role === "viewer") {
      const s = this.sender();
      if (s) s.send(JSON.stringify({ type: "viewer-join", id }));
      else server.send(JSON.stringify({ type: "waiting" }));
    } else {
      for (const v of this.state.getWebSockets("viewer")) {
        const a = v.deserializeAttachment();
        server.send(JSON.stringify({ type: "viewer-join", id: a.id }));
        v.send(JSON.stringify({ type: "sender-ready" }));
      }
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, raw) {
    if (typeof raw !== "string" || raw.length > MAX_BYTES) return;
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (!msg || !ALLOWED.has(msg.type)) return;

    const att = ws.deserializeAttachment();
    if (!att) return;

    if (att.role === "sender") {
      if (typeof msg.to !== "string") return;
      const [target] = this.state.getWebSockets("id:" + msg.to);
      if (target) target.send(JSON.stringify({ ...msg, from: "sender" }));
    } else {
      const s = this.sender();
      if (s) s.send(JSON.stringify({ ...msg, from: att.id }));
    }
  }

  async webSocketClose(ws) {
    this.gone(ws);
  }

  async webSocketError(ws) {
    this.gone(ws);
  }

  gone(ws) {
    const att = ws.deserializeAttachment();
    if (!att) return;
    if (att.role === "viewer") {
      const s = this.sender(ws);
      if (s) s.send(JSON.stringify({ type: "viewer-leave", id: att.id }));
    } else if (!this.sender(ws)) {
      for (const v of this.state.getWebSockets("viewer")) {
        if (v !== ws) v.send(JSON.stringify({ type: "sender-left" }));
      }
    }
  }
}
