export { Room } from "./room.js";

const STUN = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];

async function iceServers(env) {
  if (!env.TURN_KEY_ID || !env.TURN_API_TOKEN) return STUN;
  try {
    const r = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.TURN_API_TOKEN}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ttl: 86400 }),
      }
    );
    if (!r.ok) return STUN;
    const data = await r.json();
    return Array.isArray(data.iceServers) ? data.iceServers : STUN;
  } catch {
    return STUN;
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/api/ice") {
      return new Response(JSON.stringify({ iceServers: await iceServers(env) }), {
        headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
      });
    }

    const m = url.pathname.match(/^\/ws\/([A-Za-z0-9]{4,12})$/);
    if (m) {
      const id = env.ROOM.idFromName(m[1].toUpperCase());
      return env.ROOM.get(id).fetch(request);
    }

    return env.ASSETS.fetch(request);
  },
};
