package com.example.hitachiremote

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class IrBridge(private val ir: ConsumerIrManager) {
    @JavascriptInterface
    fun sendIr(json: String) {
        val o = JSONObject(json)
        val state = AcState(
            o.getBoolean("power"), o.getString("mode"), o.getInt("temp"),
            o.getString("fan"), o.getBoolean("swing"), o.optString("proto", "AC")
        )
        val frame = HitachiEncoder.encode(state) ?: return
        ir.transmit(frame.carrierHz, frame.pattern)
    }
}

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ir = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir == null || !ir.hasIrEmitter()) {
            setContentView(TextView(this).apply {
                text = "This phone has no IR blaster."
                setPadding(48, 48, 48, 48)
            })
            return
        }
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(IrBridge(ir), "Android")
        web.loadUrl("file:///android_asset/hitachi-remote.html")
        setContentView(web)
    }
}
