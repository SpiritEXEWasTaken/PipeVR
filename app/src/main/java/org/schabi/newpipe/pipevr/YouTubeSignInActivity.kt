package org.schabi.newpipe.pipevr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.schabi.newpipe.R
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.ThemeHelper

/**
 * PipeVR: shows a code to enter at yt.be/activate (or a QR code to scan with a phone), then
 * waits for the user to approve the sign-in, like the YouTube TV app.
 */
class YouTubeSignInActivity : AppCompatActivity() {
    @Volatile
    private var running = true

    private lateinit var codeView: TextView
    private lateinit var qrView: ImageView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        Localization.assureCorrectAppLanguage(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.setTheme(this)
        title = getString(R.string.pipevr_sign_in_title)
        setContentView(buildLayout())
        Thread(::signInLoop, "PipeVR-sign-in").start()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun buildLayout(): ViewGroup {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        left.addView(text(28f, bold = true).apply { setText(R.string.pipevr_sign_in_title) })
        left.addView(text(18f).apply {
            setText(R.string.pipevr_sign_in_step1)
            setPadding(0, dp(24), 0, 0)
        })
        left.addView(text(30f, bold = true).apply {
            setText(YouTubeTvAuth.ACTIVATE_DISPLAY)
            setTextColor(Color.parseColor("#4FC3F7"))
        })
        left.addView(text(18f).apply {
            setText(R.string.pipevr_sign_in_step2)
            setPadding(0, dp(16), 0, 0)
        })
        codeView = text(44f, bold = true).apply {
            text = "…"
            letterSpacing = 0.1f
            setTextIsSelectable(true)
        }
        left.addView(codeView)
        statusView = text(16f).apply {
            setText(R.string.pipevr_sign_in_getting_code)
            setPadding(0, dp(24), 0, dp(16))
        }
        left.addView(statusView)
        left.addView(Button(this).apply {
            setText(R.string.cancel)
            setOnClickListener { finish() }
        })
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, 0, 0)
        }
        qrView = ImageView(this)
        right.addView(qrView, LinearLayout.LayoutParams(dp(260), dp(260)))
        right.addView(text(14f).apply {
            setText(R.string.pipevr_sign_in_scan)
            setPadding(0, dp(8), 0, 0)
        })
        row.addView(right)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(row)
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing) block() }

    private fun signInLoop() {
        var errors = 0
        while (running) {
            val code = try {
                YouTubeTvAuth.requestDeviceCode(this)
            } catch (e: Exception) {
                ui { statusView.text = getString(R.string.pipevr_sign_in_error, e.message) }
                if (++errors > 5) return
                sleep(5_000)
                continue
            }
            val qr = makeQr(code.qrUrl)
            ui {
                codeView.text = code.userCode
                qrView.setImageBitmap(qr)
                statusView.setText(R.string.pipevr_sign_in_waiting)
            }
            var interval = code.intervalSeconds * 1000L
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            while (running && System.currentTimeMillis() < deadline) {
                sleep(interval)
                if (!running) return
                when (val result = YouTubeTvAuth.pollToken(this, code)) {
                    is YouTubeTvAuth.TokenResult.Success -> {
                        finishSignIn(result)
                        return
                    }
                    YouTubeTvAuth.TokenResult.Pending -> Unit
                    YouTubeTvAuth.TokenResult.SlowDown -> interval += 5_000
                    YouTubeTvAuth.TokenResult.Expired -> break // get a new code
                    YouTubeTvAuth.TokenResult.Denied -> {
                        ui { statusView.setText(R.string.pipevr_sign_in_denied) }
                        return
                    }
                    is YouTubeTvAuth.TokenResult.Error -> {
                        if (++errors > 20) {
                            ui {
                                statusView.text =
                                    getString(R.string.pipevr_sign_in_error, result.message)
                            }
                            return
                        }
                    }
                }
            }
        }
    }

    private fun finishSignIn(token: YouTubeTvAuth.TokenResult.Success) {
        ui { statusView.setText(R.string.pipevr_sign_in_loading_channels) }
        try {
            YouTubeAccountManager.addSignedInGoogleAccount(token)
            ui {
                setResult(RESULT_OK)
                finish()
            }
        } catch (e: Exception) {
            ui { statusView.text = getString(R.string.pipevr_sign_in_error, e.message) }
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            running = false
        }
    }

    private fun makeQr(content: String): Bitmap? = try {
        val size = 512
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        null
    }
}
