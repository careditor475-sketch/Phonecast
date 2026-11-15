package app.phonecast

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom

class MainActivity : Activity() {

    private lateinit var serverInput: EditText
    private lateinit var mainButton: Button
    private lateinit var statusText: TextView
    private lateinit var codeText: TextView
    private lateinit var linkText: TextView
    private lateinit var shareButton: Button
    private lateinit var copyButton: Button

    private var code = ""
    private var server = ""

    private val prefs by lazy { getSharedPreferences("phonecast", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        CastService.listener = { s -> runOnUiThread { render(s) } }
        render(CastService.state)
    }

    override fun onDestroy() {
        if (CastService.listener != null) CastService.listener = null
        super.onDestroy()
    }

    // ---- UI -------------------------------------------------------------

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun label(text: String, sizeSp: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#0B0D12"))
        }

        root.addView(label("PhoneCast", 32f, bold = true))
        root.addView(label("Cast this phone's screen to any browser.", 15f, Color.parseColor("#8B93A7")).apply {
            setPadding(0, dp(4), 0, dp(24))
        })

        root.addView(label("Your server address", 13f, Color.parseColor("#8B93A7")))
        serverInput = EditText(this).apply {
            setText(prefs.getString("server", ""))
            hint = "https://phonecast.yourname.workers.dev"
            setHintTextColor(Color.parseColor("#4A5266"))
            setTextColor(Color.WHITE)
            setSingleLine()
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(serverInput, LinearLayout.LayoutParams(-1, -2))

        mainButton = Button(this).apply {
            textSize = 18f
            setOnClickListener { onMainClick() }
        }
        root.addView(mainButton, LinearLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(20) })

        statusText = label("", 15f, Color.parseColor("#8B93A7")).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        codeText = label("", 44f, Color.parseColor("#4F8CFF"), bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
        }
        root.addView(codeText, LinearLayout.LayoutParams(-1, -2))

        linkText = label("", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(linkText, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shareButton = Button(this).apply { text = "Share link"; setOnClickListener { shareLink() } }
        copyButton = Button(this).apply { text = "Copy link"; setOnClickListener { copyLink() } }
        row.addView(shareButton, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(copyButton, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D12"))
            addView(root, ViewGroup.LayoutParams(-1, -2))
        })
    }

    private fun render(state: String) {
        val casting = state != "idle"
        mainButton.text = if (casting) "Stop casting" else "Start casting"
        serverInput.isEnabled = !casting
        val show = if (casting) android.view.View.VISIBLE else android.view.View.GONE
        codeText.visibility = show; linkText.visibility = show
        shareButton.visibility = show; copyButton.visibility = show
        if (casting) {
            code = CastService.code
            server = CastService.server
            codeText.text = code
            linkText.text = "$server/$code"
        }
        statusText.text = when (state) {
            "connecting" -> "Connecting to your server…"
            "ready" -> "Live. Open the link or enter the code on any device."
            "error" -> "Can't reach the server. Retrying…"
            else -> "Tap start, then allow screen capture."
        } + if (casting && CastService.viewers > 0) "\nWatching now: ${CastService.viewers}" else ""
    }

    // ---- Flow -----------------------------------------------------------

    private fun onMainClick() {
        if (CastService.state != "idle") {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
            return
        }
        var s = serverInput.text.toString().trim().trimEnd('/')
        if (s.isEmpty()) { toast("Enter your server address first."); return }
        if (!s.startsWith("http")) s = "https://$s"
        if (!s.startsWith("https://")) { toast("The server address must start with https://"); return }
        server = s
        prefs.edit().putString("server", s).apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) requestCapture() // cast works even if notifications are denied
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { toast("Screen capture was not allowed."); return }

        code = newCode()
        val (w, h) = realSize()
        val i = Intent(this, CastService::class.java)
            .setAction(CastService.ACTION_START)
            .putExtra(CastService.EXTRA_RESULT, resultCode)
            .putExtra(CastService.EXTRA_DATA, data)
            .putExtra(CastService.EXTRA_SERVER, server)
            .putExtra(CastService.EXTRA_CODE, code)
            .putExtra(CastService.EXTRA_W, w)
            .putExtra(CastService.EXTRA_H, h)
        startForegroundService(i)
    }

    private fun realSize(): Pair<Int, Int> {
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        // Always store as portrait (short side first); the service flips it on rotation.
        return minOf(w, h) to maxOf(w, h)
    }

    private fun newCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..6).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun shareLink() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Watch my screen: $server/$code")
        startActivity(Intent.createChooser(i, "Share cast link"))
    }

    private fun copyLink() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PhoneCast", "$server/$code"))
        toast("Link copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_CAPTURE = 2
    }
}
