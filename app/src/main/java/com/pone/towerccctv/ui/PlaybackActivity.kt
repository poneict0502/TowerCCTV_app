package com.pone.towerccctv.ui

import android.app.DatePickerDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pone.towerccctv.controller.PlaybackController
import com.pone.towerccctv.controller.RecordSegment
import com.pone.towerccctv.model.Channel
import com.pone.towerccctv.model.DeviceType
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.*

class PlaybackActivity : AppCompatActivity() {

    private lateinit var ctrl: PlaybackController
    private lateinit var vlcView: VLCVideoLayout
    private lateinit var lvList: ListView
    private lateinit var tvStatus: TextView
    private lateinit var btnDate: Button
    private lateinit var progress: ProgressBar

    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var segments = listOf<RecordSegment>()
    private var selectedDate = Date()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val httpBase   = intent.getStringExtra("httpBase") ?: ""
        val username   = intent.getStringExtra("username") ?: "admin"
        val password   = intent.getStringExtra("password") ?: ""
        val label      = intent.getStringExtra("label") ?: "녹화 재생"
        val nvrChannel = intent.getIntExtra("nvrChannel", 1)
        title = "▶ $label"

        ctrl = PlaybackController(Channel(0, label, "", httpBase, nvrChannel,
            username, password, false, DeviceType.CAMERA))

        val args = arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=500")
        libVLC = LibVLC(this, args)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }
        val leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f)
        }
        vlcView = VLCVideoLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.parseColor("#111114"))
        }
        tvStatus = TextView(this).apply {
            text = "← 날짜를 선택하고 구간을 탭하세요"
            setTextColor(Color.parseColor("#666666")); textSize = 12f
            setPadding(14, 8, 14, 8)
        }
        leftPanel.addView(vlcView); leftPanel.addView(tvStatus)

        val rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(Color.parseColor("#16161A"))
        }
        btnDate = Button(this).apply {
            text = "📅 날짜 선택"
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1A1A1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        lvList = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            dividerHeight = 1
        }
        val btnBack = Button(this).apply {
            text = "← 돌아가기"
            setTextColor(Color.parseColor("#888888")); setBackgroundColor(Color.parseColor("#1A1A1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { finish() }
        }
        rightPanel.addView(btnDate); rightPanel.addView(progress)
        rightPanel.addView(lvList); rightPanel.addView(btnBack)
        root.addView(leftPanel); root.addView(rightPanel)
        setContentView(root)

        btnDate.setOnClickListener { pickDate() }
        lvList.setOnItemClickListener { _, _, pos, _ -> playSegment(segments[pos]) }
    }

    private fun pickDate() {
        val cal = Calendar.getInstance().apply { time = selectedDate }
        DatePickerDialog(this, { _, y, m, d ->
            cal.set(y, m, d); selectedDate = cal.time
            btnDate.text = "%d-%02d-%02d".format(y, m+1, d)
            searchRecordings()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun searchRecordings() {
        progress.visibility = View.VISIBLE; lvList.adapter = null
        tvStatus.text = "검색 중..."
        lifecycleScope.launch {
            segments = ctrl.search(selectedDate)
            progress.visibility = View.GONE
            if (segments.isEmpty()) { tvStatus.text = "녹화된 영상이 없습니다"; return@launch }
            tvStatus.text = "${segments.size}개 구간 — 탭하여 재생"
            lvList.adapter = object : ArrayAdapter<RecordSegment>(
                this@PlaybackActivity, android.R.layout.simple_list_item_2, segments) {
                override fun getView(pos: Int, cv: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(pos, cv, parent); val seg = segments[pos]
                    v.findViewById<TextView>(android.R.id.text1).apply {
                        text = "▶  ${seg.startStr} ~ ${seg.endStr}"
                        setTextColor(Color.parseColor("#DDDDDD"))
                    }
                    v.findViewById<TextView>(android.R.id.text2).apply {
                        text = "   ${seg.durStr}"; setTextColor(Color.parseColor("#777777"))
                    }
                    v.setBackgroundColor(if (pos%2==0) Color.parseColor("#1A1A1E")
                                         else Color.parseColor("#16161A"))
                    return v
                }
            }
        }
    }

    private fun playSegment(seg: RecordSegment) {
        player?.release()
        tvStatus.text = "▶ ${seg.startStr} ~ ${seg.endStr}  (${seg.durStr})"
        val url = ctrl.playbackUrl(seg)
        val p = MediaPlayer(libVLC)
        player = p
        p.attachViews(vlcView, null, false, false)
        p.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        val media = Media(libVLC, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":rtsp-tcp")
        p.media = media
        media.release()
        p.play()
    }

    override fun onStop()    { super.onStop();    player?.stop() }
    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
        libVLC?.release(); libVLC = null
    }
}
