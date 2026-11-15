# PhoneCast

Cast your Android screen to any browser (laptop, phone, smart TV).

- `app/` Android sender. Captures the screen and streams it over WebRTC.
- `worker/` Cloudflare Worker. Serves the viewer page and relays the connection handshake. Video goes peer-to-peer, not through the Worker.

## Setup (all doable from a phone)

1. Put this folder in a GitHub repo (branch `main`).
2. In the repo: Settings → Secrets and variables → Actions → add
   `CLOUDFLARE_API_TOKEN` (template "Edit Cloudflare Workers") and `CLOUDFLARE_ACCOUNT_ID`.
3. Actions tab → run **Deploy relay and viewer to Cloudflare**. Your address will be
   `https://phonecast.<your-subdomain>.workers.dev`.
4. Actions tab → run **Build Android APK**. When done, open the repo's Releases page on your phone,
   download `PhoneCast.apk` and install it (allow "install unknown apps" for your browser).
5. Open PhoneCast, paste your workers.dev address, tap **Start casting**, allow screen capture.
6. On any other device open the link shown (or the address and type the 6-character code).

## Optional: TURN for strict networks

If a viewer on another network can't connect, enable Cloudflare Calls TURN, then add the
`TURN_KEY_ID` and `TURN_API_TOKEN` secrets to the Worker (`wrangler secret put ...`).
The Worker hands the credentials to both sides automatically.

## Known limits (v1)

- No audio yet (needs a separate audio capture path).
- Anyone with the code can watch, so treat the link like a password and stop casting when done.
- Debug-signed APK, fine for personal use.
