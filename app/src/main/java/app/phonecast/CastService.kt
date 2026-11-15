package app.phonecast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground service that captures the screen with MediaProjection and
 * streams it to every connected viewer over WebRTC. The Cloudflare relay is
 * only used for the offer/answer/ICE handshake.
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "app.phonecast.START"
        const val ACTION_STOP = "app.phonecast.STOP"
        const val EXTRA_RESULT = "result"
        const val EXTRA_DATA = "data"
        const val EXTRA_SERVER = "server"
        const val EXTRA_CODE = "code"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"

        private const val CHANNEL = "cast"
        private const val NOTIF_ID = 1
        private const val MAX_SIDE = 1280
        private const val FPS = 30

        // Tiny shared state so the Activity can show status without binding.
        @Volatile var state = "idle"      // idle | connecting | ready | error
        @Volatile var viewers = 0
        @Volatile var code = ""
        @Volatile var server = ""
        @Volatile var listener: ((String) -> Unit)? = null

        private fun publish(s: String) {
            state = s
            val l = listener
            if (l != null) Handler(Looper.getMainLooper()).post { l(s) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var socket: WebSocket? = null
    private var iceServers: List<PeerConnection.IceServer> = emptyList()
    private val peers = ConcurrentHashMap<String, PeerConnection>()

    private var baseW = 720   // portrait short side
    private var baseH = 1280  // portrait long side
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(start: Intent?, flags: Int, startId: Int): Int {
        val intent = start ?: return START_NOT_STICKY
        if (intent.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_START || running) return START_NOT_STICKY

        val data: Intent? = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        val (w, h) = fit(intent.getIntExtra(EXTRA_W, 720), intent.getIntExtra(EXTRA_H, 1280))
        baseW = w; baseH = h

        // Must be foreground (type mediaProjection) before the projection is created.
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        running = true
        publish("connecting")

        Thread {
            try {
                setupWebRtc(data)
                fetchIceThenConnect()
            } catch (e: Exception) {
                e.printStackTrace()
                publish("error")
            }
        }.start()
        return START_NOT_STICKY
    }

    // ---- WebRTC setup ---------------------------------------------------

    private fun setupWebRtc(projectionData: Intent) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        val eglBase = EglBase.create().also { egl = it }
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { main.post { shutdown() } }
        })
        capturer = cap
        val th = SurfaceTextureHelper.create("capture", eglBase.eglBaseContext)
        helper = th
        val src = factory!!.createVideoSource(true)
        source = src
        cap.initialize(th, applicationContext, src.capturerObserver)
        val (w, h) = currentSize()
        cap.startCapture(w, h, FPS)
        track = factory!!.createVideoTrack("screen", src).also { it.setEnabled(true) }
    }

    private fun fit(w: Int, h: Int): Pair<Int, Int> {
        val scale = min(1.0, MAX_SIDE.toDouble() / max(w, h))
        val fw = ((w * scale).toInt() / 2) * 2
        val fh = ((h * scale).toInt() / 2) * 2
        return fw to fh
    }

    private fun currentSize(): Pair<Int, Int> =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) baseH to baseW
        else baseW to baseH

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = currentSize()
        try { capturer?.changeCaptureFormat(w, h, FPS) } catch (_: Exception) {}
    }

    // ---- Signaling ------------------------------------------------------

    private fun fetchIceThenConnect() {
        try {
            val req = Request.Builder().url("$server/api/ice").build()
            http.newCall(req).execute().use { r ->
                val arr: JSONArray = JSONObject(r.body?.string().orEmpty()).getJSONArray("iceServers")
                val list = ArrayList<PeerConnection.IceServer>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val urls = o.get("urls").let { u ->
                        if (u is JSONArray) (0 until u.length()).map { u.getString(it) } else listOf(u.toString())
                    }
                    val b = PeerConnection.IceServer.builder(urls)
                    if (o.has("username")) b.setUsername(o.getString("username"))
                    if (o.has("credential")) b.setPassword(o.getString("credential"))
                    list.add(b.createIceServer())
                }
                iceServers = list
            }
        } catch (_: Exception) {
            iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        connectSocket()
    }

    private fun connectSocket() {
        if (!running) return
        val wsUrl = server.replaceFirst("https://", "wss://") + "/ws/$code?role=sender"
        socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { publish("ready") }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try { onSignal(JSONObject(text)) } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnect()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                publish("error"); reconnect()
            }
        })
    }

    private fun reconnect() {
        if (!running) return
        main.postDelayed({ if (running) connectSocket() }, 2000)
    }

    private fun send(o: JSONObject) { socket?.send(o.toString()) }

    private fun onSignal(m: JSONObject) {
        when (m.getString("type")) {
            "viewer-join" -> startPeer(m.getString("id"))
            "viewer-leave" -> closePeer(m.getString("id"))
            "answer" -> peers[m.getString("from")]?.setRemoteDescription(
                Noop(), SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp"))
            )
            "ice" -> {
                val c = m.getJSONObject("candidate")
                peers[m.getString("from")]?.addIceCandidate(
                    IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate"))
                )
            }
        }
    }

    private fun startPeer(id: String) {
        val f = factory ?: return
        val t = track ?: return
        closePeer(id)

        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                send(JSONObject().put("type", "ice").put("to", id).put(
                    "candidate", JSONObject()
                        .put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                ))
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (s == PeerConnection.PeerConnectionState.FAILED ||
                    s == PeerConnection.PeerConnectionState.CLOSED
                ) main.post { closePeer(id) }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return

        peers[id] = pc
        viewers = peers.size
        publish(state)

        val sender = pc.addTrack(t, listOf("screen-stream"))
        try {
            val p = sender.parameters
            for (e in p.encodings) e.maxBitrateBps = 4_000_000
            sender.parameters = p
        } catch (_: Exception) {}

        pc.createOffer(object : Noop() {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc.setLocalDescription(Noop(), desc)
                send(JSONObject().put("type", "offer").put("to", id).put("sdp", desc.description))
            }
        }, MediaConstraints())
    }

    private fun closePeer(id: String) {
        peers.remove(id)?.let { try { it.close() } catch (_: Exception) {} }
        viewers = peers.size
        publish(state)
    }

    // ---- Shutdown -------------------------------------------------------

    private fun shutdown() {
        if (!running && state == "idle") { stopSelf(); return }
        running = false
        main.removeCallbacksAndMessages(null)
        Thread {
            try { socket?.close(1000, "bye") } catch (_: Exception) {}
            peers.values.forEach { try { it.close() } catch (_: Exception) {} }
            peers.clear()
            try { capturer?.stopCapture() } catch (_: Exception) {}
            try { capturer?.dispose() } catch (_: Exception) {}
            try { track?.dispose() } catch (_: Exception) {}
            try { source?.dispose() } catch (_: Exception) {}
            try { helper?.dispose() } catch (_: Exception) {}
            try { factory?.dispose() } catch (_: Exception) {}
            try { egl?.release() } catch (_: Exception) {}
            capturer = null; track = null; source = null; helper = null; factory = null; egl = null
            viewers = 0
            publish("idle")
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }.start()
    }

    override fun onDestroy() {
        if (running) shutdown()
        super.onDestroy()
    }

    // ---- Helpers --------------------------------------------------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen casting", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Casting your screen")
            .setContentText("Code: $code")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop
                ).build()
            )
            .build()
    }

    /** SdpObserver that ignores everything unless overridden. */
    private open class Noop : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
