/**
 * P2P Call — сигнальный релей на Cloudflare Workers (Durable Object).
 *
 * Дозвон и обмен SDP между двумя экземплярами приложения напрямую
 * (без Telegram и без своего сервера). Медиа (WebRTC) идёт P2P мимо воркера.
 *
 *   Телефон A ──wss://<worker>/ws?room=КОД──► Durable Object (комната)
 *   Телефон B ──wss://<worker>/ws?room=КОД──► Durable Object (комната)
 *
 * Роли: первый подключившийся — «caller» (инициатор), второй — «callee».
 * Durable Object ретранслирует все JSON-сообщения между двумя сокетами и
 * рассылает служебные события (welcome / peer-joined / peer-left / error).
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/ws") {
      return new Response("P2P Call signaling worker. Connect via WebSocket: /ws?room=CODE", {
        status: 200,
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }
    const room = sanitize(url.searchParams.get("room"));
    const id = env.CALLS.idFromName(room);
    const stub = env.CALLS.get(id);
    return stub.fetch(request);
  },
};

function sanitize(room) {
  if (typeof room !== "string") return "default";
  const r = room.trim();
  return r.length > 0 && r.length <= 64 ? r : "default";
}

export class CallRelay {
  constructor(state, env) {
    this.state = state;
    this.sockets = []; // [{ ws, role }]
  }

  async fetch(request) {
    try {
      const upgrade = request.headers.get("Upgrade");
      if (upgrade !== "websocket") {
        return new Response("Expected Upgrade: websocket", { status: 426 });
      }

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);

      if (this.sockets.length >= 2) {
        server.accept();
        server.send(JSON.stringify({ type: "error", message: "room full" }));
        server.close(1000, "room full");
        return new Response(null, { status: 101, webSocket: client });
      }

      // Хибернация: регистрируем сокет через acceptWebSocket (только он,
      // без server.accept() — вместе они несовместимы).
      this.state.acceptWebSocket(server);

      const role = this.sockets.length === 0 ? "caller" : "callee";
      this.sockets.push({ ws: server, role });

      server.send(JSON.stringify({ type: "welcome", role, peers: this.sockets.length }));
      for (const s of this.sockets) {
        if (s.ws !== server) {
          s.ws.send(JSON.stringify({ type: "peer-joined", peers: this.sockets.length }));
        }
      }

      return new Response(null, { status: 101, webSocket: client });
    } catch (e) {
      return new Response("DO fetch error: " + (e && e.stack ? e.stack : e), { status: 500 });
    }
  }

  async webSocketMessage(ws, message) {
    for (const s of this.sockets) {
      if (s.ws !== ws) {
        try {
          s.ws.send(message);
        } catch (_) {}
      }
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    this.removeSocket(ws);
  }

  async webSocketError(ws, error) {
    this.removeSocket(ws);
  }

  removeSocket(ws) {
    this.sockets = this.sockets.filter((s) => s.ws !== ws);
    for (const s of this.sockets) {
      try {
        s.ws.send(JSON.stringify({ type: "peer-left" }));
      } catch (_) {}
    }
  }
}