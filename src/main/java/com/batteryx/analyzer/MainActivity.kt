package com.batteryx.analyzer

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

class App(val id: String, val tot: Double) {
    var fg = 0.0; var bg = 0.0; var fgs = 0.0
    val b = HashMap<String, Double>()
}
class Res {
    var cap = 0.0; var drain = 0.0
    val glob = LinkedHashMap<String, Double>()
    val apps = ArrayList<App>()
}

class MainActivity : Activity() {
    private lateinit var box: LinearLayout
    private val capRe = Regex("""Capacity: (\d+).*Computed drain: ([\d.]+)""")
    private val uidRe = Regex("""^\s+UID (\S+): ([\d.]+)(.*)$""")
    private val hdrRe = Regex("""\b(fg|bg|fgs|cached): ([\d.]+)""")
    private val globRe = Regex("""^\s{4}([a-z_]+): ([\d.]+)""")
    private val kvRe = Regex("""(?:^|\s)([a-z_]+)=([\d.]+)""")
    private val names = mapOf(
        "screen" to "صفحه‌نمایش", "cpu" to "پردازنده", "mobile_radio" to "اینترنت موبایل",
        "audio" to "صدا", "video" to "ویدیو", "sensors" to "سنسور", "wakelock" to "ویک‌لاک",
        "bluetooth" to "بلوتوث", "camera" to "دوربین", "flashlight" to "چراغ‌قوه",
        "wifi" to "وای‌فای", "gps" to "GPS"
    )
    private val cmd = "RISH_APPLICATION_ID=com.termux ./rish -c \"pm grant com.batteryx.analyzer android.permission.DUMP\""

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Color.parseColor("#0F1115")
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#0F1115"))
        sv.layoutDirection = View.LAYOUT_DIRECTION_RTL
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(36), dp(16), dp(24))
        sv.addView(box)
        setContentView(sv)
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(x: Double): String =
        if (x >= 100) "%.0f".format(Locale.US, x) else if (x >= 10) "%.1f".format(Locale.US, x) else "%.2f".format(Locale.US, x)

    private fun tv(p: LinearLayout, t: String, size: Float, color: String, bold: Boolean = false, top: Int = 0): TextView {
        val x = TextView(this)
        x.text = t
        x.textSize = size
        x.setTextColor(Color.parseColor(color))
        if (bold) x.setTypeface(null, Typeface.BOLD)
        x.setPadding(0, dp(top), 0, 0)
        p.addView(x)
        return x
    }

    private fun load() {
        box.removeAllViews()
        tv(box, "🔋 تحلیل‌گر باتری", 22f, "#FFFFFF", true)
        val btn = Button(this)
        btn.text = "تازه‌سازی"
        btn.setOnClickListener { load() }
        box.addView(btn)
        val st = tv(box, "در حال خواندن…", 13f, "#9AA1AD")
        thread {
            var r: Res? = null
            var err = ""
            try {
                val o = read()
                r = o.first
                err = o.second
            } catch (e: Exception) { err = e.toString() }
            runOnUiThread { show(st, r, err) }
        }
    }

    private fun read(): Pair<Res?, String> {
        val p = ProcessBuilder("dumpsys", "batterystats").redirectErrorStream(true).start()
        val r = Res()
        val head = StringBuilder()
        var started = false
        var inG = true
        var cur: App? = null
        p.inputStream.bufferedReader().useLines { seq ->
            for (s in seq) {
                if (!started) {
                    if (s.contains("Estimated power use (mAh)")) started = true
                    else if (head.length < 300) head.append(s).append('\n')
                    continue
                }
                if (s.isNotEmpty() && !s[0].isWhitespace()) break
                if (s.isBlank() && r.apps.isNotEmpty()) break
                val cm = capRe.find(s)
                if (cm != null) {
                    r.cap = cm.groupValues[1].toDouble()
                    r.drain = cm.groupValues[2].toDouble()
                    continue
                }
                val um = uidRe.find(s)
                if (um != null) {
                    inG = false
                    val a = App(um.groupValues[1], um.groupValues[2].toDouble())
                    for (m in hdrRe.findAll(um.groupValues[3])) {
                        val v = m.groupValues[2].toDouble()
                        when (m.groupValues[1]) { "fg" -> a.fg = v; "bg" -> a.bg = v; "fgs" -> a.fgs = v }
                    }
                    r.apps.add(a)
                    cur = a
                    continue
                }
                val t = s.trimStart()
                if (inG) {
                    if (t.startsWith("(")) { inG = false; continue }
                    val gm = globRe.find(s)
                    if (gm != null) r.glob[gm.groupValues[1]] = gm.groupValues[2].toDouble()
                    continue
                }
                val c = cur
                if (c != null && !t.startsWith("(")) {
                    for (m in kvRe.findAll(s)) c.b[m.groupValues[1]] = m.groupValues[2].toDouble()
                }
            }
        }
        p.destroy()
        if (!started) return Pair(null, head.toString())
        return Pair(r, "")
    }

    private fun label(id: String): Pair<String, String> {
        val sys = mapOf("1000" to "سیستم اندروید", "1001" to "رادیو / تلفن", "0" to "هسته (root)", "1041" to "سرویس صدا")
        val s = sys[id]
        if (s != null) return Pair(s, "")
        val m = Regex("^u(\\d+)a(\\d+)$").find(id)
        val uid: Int = if (m != null) m.groupValues[1].toInt() * 100000 + 10000 + m.groupValues[2].toInt()
                       else id.toIntOrNull() ?: return Pair("UID $id", "")
        val pk = packageManager.getPackagesForUid(uid)
        if (pk == null || pk.isEmpty()) {
            return Pair("UID $id", if (m != null && m.groupValues[1] != "0") "پروفایل دوم" else "")
        }
        return try {
            val n = packageManager.getApplicationInfo(pk[0], 0).loadLabel(packageManager).toString()
            Pair(n, if (pk.size > 1) "+" + (pk.size - 1) else "")
        } catch (e: Exception) { Pair(pk[0], "") }
    }

    private fun row(p: LinearLayout, title: String, value: String, frac: Double, detail: String?) {
        val w = LinearLayout(this)
        w.orientation = LinearLayout.VERTICAL
        w.setPadding(0, dp(10), 0, dp(10))
        tv(w, title, 14f, "#ECEEF2", true)
        tv(w, value, 12f, "#9AA1AD")
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pb.max = 1000
        pb.progress = (frac * 1000).toInt()
        pb.progressTintList = ColorStateList.valueOf(Color.parseColor("#4ADE80"))
        pb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
        w.addView(pb)
        if (detail != null) {
            val d = tv(w, detail, 12f, "#9AA1AD", false, 6)
            d.visibility = View.GONE
            w.setOnClickListener { d.visibility = if (d.visibility == View.GONE) View.VISIBLE else View.GONE }
        }
        p.addView(w)
    }

    private fun show(st: TextView, r: Res?, err: String) {
        if (r == null) {
            st.setTextColor(Color.parseColor("#F87171"))
            if (err.contains("Permission Denial")) {
                st.text = "برنامه هنوز اجازه‌ی DUMP نگرفته. Shizuku رو روشن کن و این دستور رو توی Termux بزن:"
                val c = tv(box, cmd, 13f, "#4ADE80", false, 8)
                c.textDirection = View.TEXT_DIRECTION_LTR
                c.setTextIsSelectable(true)
            } else {
                st.text = "داده‌ای خوانده نشد:\n$err"
            }
            return
        }
        st.text = "ظرفیت ${r.cap.toInt()} mAh · مصرف از آخرین شارژ کامل ${fmt(r.drain)} mAh"
        tv(box, "بر اساس سخت‌افزار", 16f, "#FFFFFF", true, 16)
        val gmax = r.glob.values.maxOrNull() ?: 1.0
        for ((k, v) in r.glob.entries.sortedByDescending { it.value }) {
            if (v < 0.5) continue
            row(box, names[k] ?: k, fmt(v) + " mAh", v / gmax, null)
        }
        tv(box, "برنامه‌ها (برای جزئیات بزن)", 16f, "#FFFFFF", true, 16)
        val tot = if (r.drain > 0) r.drain else r.apps.sumOf { it.tot }
        val sorted = r.apps.sortedByDescending { it.tot }
        val mx = sorted.firstOrNull()?.tot ?: 1.0
        for (a in sorted.take(40)) {
            val (nm, sub) = label(a.id)
            val d = StringBuilder("پیش‌زمینه ${fmt(a.fg)} · پس‌زمینه ${fmt(a.bg)}")
            if (a.fgs > 0) d.append(" · سرویس پیش‌زمینه ${fmt(a.fgs)}")
            for ((k, v) in a.b.entries.sortedByDescending { it.value }) {
                if (v >= 0.01) d.append("\n").append(names[k] ?: k).append(": ").append(fmt(v))
            }
            val pct = "%.1f".format(Locale.US, a.tot / tot * 100)
            row(box, nm + (if (sub.isNotEmpty()) " ($sub)" else ""), fmt(a.tot) + " mAh · " + pct + "%", a.tot / mx, d.toString())
        }
    }
}
