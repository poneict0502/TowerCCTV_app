package com.pone.towerccctv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.pone.towerccctv.model.DeviceStore
import com.pone.towerccctv.ocr.OcrSettings
import com.pone.towerccctv.ocr.RoiOverlayView

class OcrSettingsDialog(
    private val context: Context,
    private val onChanged: (() -> Unit)? = null
) {
    // ★ 등록된 마지막 채널(보통 CH4 = OCR 대상)의 인증정보를 동적으로 사용
    private fun resolveOcrTarget(): Triple<String, String, String>? {
        val devs = DeviceStore.load(context).filter { it.enabled }
        if (devs.isEmpty()) return null
        // OCR 대상 카메라 = 등록된 마지막 카메라 (관행)
        val dev = devs.last()
        // 서브 스트림(102) picture - 부하 감소 + 4분할과 다른 스트림으로 충돌 회피
        val url = "http://${dev.ip}:${dev.httpPort}/ISAPI/Streaming/channels/101/picture"
        return Triple(url, dev.username, dev.password)
    }

    fun show() {
        val dialog = Dialog(context)
        dialog.setTitle("OCR / 계측 설정")
        dialog.setCancelable(true)

        val scroll = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#0F1520"))
        }
        scroll.addView(layout)

        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
            setPadding(0, 16, 0, 4)
        }

        fun input(default: String, hint: String = "") = EditText(context).apply {
            setText(default); this.hint = hint
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E2530"))
            textSize = 16f; setPadding(16, 10, 16, 10)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        // OCR 모드 스위치
        val ocrRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val ocrLabel = TextView(context).apply {
            text = "OCR 모드 ON/OFF"
            setTextColor(Color.WHITE); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ocrSwitch = Switch(context).apply { isChecked = OcrSettings.isOcrEnabled(context) }
        ocrRow.addView(ocrLabel); ocrRow.addView(ocrSwitch)
        layout.addView(ocrRow)

        fun divider() = View(context).apply {
            setBackgroundColor(Color.parseColor("#253545"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 12; it.bottomMargin = 4 }
        }
        layout.addView(divider())

        layout.addView(label("⚖  중량 한계 (ton)"))
        val weightInput = input(OcrSettings.getWeightLimit(context).toString(), "예: 3.0")
        layout.addView(weightInput)

        // 중량 단위
        val unitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 4)
        }
        unitRow.addView(TextView(context).apply {
            text = "중량 단위:"; setTextColor(Color.parseColor("#AAAAAA")); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val btnTon = ToggleButton(context).apply {
            textOn = "TON"; textOff = "TON"; textSize = 11f
            isChecked = OcrSettings.isWeightTon(context)
        }
        val btnKg = ToggleButton(context).apply {
            textOn = "KG"; textOff = "KG"; textSize = 11f
            isChecked = !OcrSettings.isWeightTon(context)
        }
        btnTon.setOnCheckedChangeListener { _, on -> if (on) btnKg.isChecked = false }
        btnKg.setOnCheckedChangeListener  { _, on -> if (on) btnTon.isChecked = false }
        unitRow.addView(btnTon); unitRow.addView(btnKg)
        layout.addView(unitRow)

        layout.addView(divider())

        // ROI 영역 설정
        layout.addView(TextView(context).apply {
            text = "📐  ROI 영역 설정"
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(0, 8, 0, 4)
        })
        layout.addView(TextView(context).apply {
            text = "스냅샷 받기 → 드래그로 영역 지정\n(등록된 마지막 카메라의 스냅샷 사용)"
            setTextColor(Color.parseColor("#778899")); textSize = 11f
        })

        val btnRoi = Button(context).apply {
            text = "📸  스냅샷 받고 ROI 설정"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A3A8F"))
            setPadding(0, 12, 0, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = 8 }
        }
        btnRoi.setOnClickListener {
            val target = resolveOcrTarget()
            if (target == null) {
                Toast.makeText(context, "등록된 카메라가 없습니다. 먼저 장치를 등록해 주세요.",
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            // 설정값 먼저 저장
            OcrSettings.setOcrEnabled(context, ocrSwitch.isChecked)
            weightInput.text.toString().toFloatOrNull()?.let { OcrSettings.setWeightLimit(context, it) }
            OcrSettings.setWeightTon(context, btnTon.isChecked)
            showRoiScreen(target.first, target.second, target.third)
            onChanged?.invoke()
        }
        layout.addView(btnRoi)

        layout.addView(divider())

        // 저장/취소
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val btnCancel = Button(context).apply {
            text = "취소"; setTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.parseColor("#1E2530"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = 8 }
            setOnClickListener { dialog.dismiss() }
        }
        val btnSave = Button(context).apply {
            text = "저장"; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E5FAA"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                OcrSettings.setOcrEnabled(context, ocrSwitch.isChecked)
                weightInput.text.toString().toFloatOrNull()?.let { OcrSettings.setWeightLimit(context, it) }
                OcrSettings.setWeightTon(context, btnTon.isChecked)
                Toast.makeText(context, "설정 저장됨", Toast.LENGTH_SHORT).show()
                onChanged?.invoke()
                dialog.dismiss()
            }
        }
        btnRow.addView(btnCancel); btnRow.addView(btnSave)
        layout.addView(btnRow)

        dialog.setContentView(scroll)
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.65).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    // ── ROI 전체화면 설정 ──
    private fun showRoiScreen(snapUrl: String, snapUser: String, snapPass: String) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(false)

        val root = android.widget.FrameLayout(context)

        val imageContainer = android.widget.FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).also { it.bottomMargin = 130 }
        }
        root.addView(imageContainer)

        val imgView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
        }
        imageContainer.addView(imgView)

        val roiView = RoiOverlayView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            loadSavedRois(context)
        }
        imageContainer.addView(roiView)

        val tvGuide = TextView(context).apply {
            text = "버튼 선택 후 화면에서 드래그"
            setTextColor(Color.WHITE); textSize = 14f
            setBackgroundColor(Color.parseColor("#CC000000"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 10, 0, 10)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(tvGuide)

        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EE0D1520"))
            setPadding(12, 10, 12, 10)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 130
            ).also { it.gravity = android.view.Gravity.BOTTOM }
        }

        fun makeBtn(text: String, color: String, action: () -> Unit) = Button(context).apply {
            this.text = text; textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(color))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = 6 }
            setOnClickListener { action() }
        }

        // ★ 동적으로 받은 인증정보 사용
        val httpClient = com.pone.towerccctv.camHttpClient(snapUser, snapPass, connectSec = 6, readSec = 6)
        val mainHandler = Handler(Looper.getMainLooper())

        fun loadSnapshot() {
            tvGuide.text = "📡  스냅샷 수신 중... (${snapUrl.substringAfter("//").substringBefore("/")})"
            Thread {
                var errorMsg = ""
                val bmp = try {
                    val req = Request.Builder().url(snapUrl).get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        } else {
                            errorMsg = "HTTP ${resp.code}"
                            null
                        }
                    }
                } catch (e: Exception) {
                    errorMsg = e.message ?: "연결 실패"
                    null
                }

                mainHandler.post {
                    if (bmp != null) {
                        imgView.setImageBitmap(bmp)
                        tvGuide.text = "버튼 선택 후 드래그 | 🔄 새로고침으로 이미지 업데이트"
                    } else {
                        tvGuide.text = "⚠ 스냅샷 수신 실패: $errorMsg - 🔄 다시 시도"
                    }
                }
            }.start()
        }

        val btnWeight = makeBtn("⚖ 중량", "#8F4A00") {
            roiView.currentMode = RoiOverlayView.RoiMode.WEIGHT
            roiView.onRoiSet = { _, roi ->
                OcrSettings.setWeightRoi(context, roi)
                Toast.makeText(context, "중량 ROI 저장됨", Toast.LENGTH_SHORT).show()
            }
            tvGuide.text = "⚖ 중량 영역을 드래그하세요"
        }
        val btnReset = makeBtn("↺ 초기화", "#4A4A00") {
            OcrSettings.setWeightRoi(context, OcrSettings.Roi(0.0f, 0.0f, 1.0f, 1.0f))
            roiView.loadSavedRois(context)
            Toast.makeText(context, "ROI 초기화됨 (전체화면)", Toast.LENGTH_SHORT).show()
        }
        val btnRefresh = makeBtn("🔄", "#2A4A2A") { loadSnapshot() }
        val btnDone = makeBtn("✅ 완료", "#1A6A2A") {
            roiView.currentMode = RoiOverlayView.RoiMode.NONE
            dialog.dismiss()
            Toast.makeText(context, "ROI 설정 완료!", Toast.LENGTH_SHORT).show()
        }

        btnBar.addView(btnWeight)
        btnBar.addView(btnReset); btnBar.addView(btnRefresh); btnBar.addView(btnDone)
        root.addView(btnBar)

        dialog.setContentView(root)
        dialog.show()

        loadSnapshot()
    }
}