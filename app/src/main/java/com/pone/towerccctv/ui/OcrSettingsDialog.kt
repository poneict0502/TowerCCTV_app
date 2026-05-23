package com.pone.towerccctv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.view.View
import android.widget.*
import com.pone.towerccctv.ocr.OcrSettings

/**
 * OCR 설정 다이얼로그
 * - OCR 모드 ON/OFF
 * - 풍속 한계치 입력
 * - 중량 한계치 입력
 * - 어디서나 호출 가능
 */
class OcrSettingsDialog(
    private val context: Context,
    private val onChanged: (() -> Unit)? = null
) {
    fun show() {
        val dialog = Dialog(context)
        dialog.setTitle("OCR 계측 설정")
        dialog.setCancelable(true)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#0F1520"))
        }

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
            text = "OCR 모드 (0.5초 간격 인식)"
            setTextColor(Color.WHITE); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ocrSwitch = Switch(context).apply {
            isChecked = OcrSettings.isOcrEnabled(context)
        }
        ocrRow.addView(ocrLabel); ocrRow.addView(ocrSwitch)
        layout.addView(ocrRow)

        // 구분선
        layout.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#253545"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 12; it.bottomMargin = 4 }
        })

        // 풍속 한계치
        layout.addView(label("🌬️  풍속 한계 (m/s)"))
        val windInput = input(OcrSettings.getWindLimit(context).toString(), "예: 15.0")
        layout.addView(windInput)
        layout.addView(TextView(context).apply {
            text = "※ 이 값 이상이면 경보 발생"
            setTextColor(Color.parseColor("#556677")); textSize = 10f
        })

        // 중량 한계치
        layout.addView(label("⚖️  중량 한계 (kg)"))
        val weightInput = input(OcrSettings.getWeightLimit(context).toString(), "예: 5000")
        layout.addView(weightInput)
        layout.addView(TextView(context).apply {
            text = "※ 이 값 이상이면 경보 발생"
            setTextColor(Color.parseColor("#556677")); textSize = 10f
        })

        // 중량 단위 선택
        val unitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 4)
        }
        unitRow.addView(TextView(context).apply {
            text = "중량 단위:"; setTextColor(Color.parseColor("#AAAAAA")); textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val btnTon = android.widget.ToggleButton(context).apply {
            textOn = "TON"; textOff = "TON"; textSize = 11f
            isChecked = OcrSettings.isWeightTon(context)
        }
        val btnKg = android.widget.ToggleButton(context).apply {
            textOn = "KG"; textOff = "KG"; textSize = 11f
            isChecked = !OcrSettings.isWeightTon(context)
        }
        btnTon.setOnCheckedChangeListener { _, on -> if (on) btnKg.isChecked = false }
        btnKg.setOnCheckedChangeListener  { _, on -> if (on) btnTon.isChecked = false }
        unitRow.addView(btnTon); unitRow.addView(btnKg)
        layout.addView(unitRow)

        // 구분선
        layout.addView(View(context).apply {
            setBackgroundColor(Color.parseColor("#253545"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 16; it.bottomMargin = 8 }
        })

        // ROI 안내
        layout.addView(TextView(context).apply {
            text = "📐  ROI 영역 설정\nCH4 단독모드 → 화면에서 드래그로 영역 지정"
            setTextColor(Color.parseColor("#778899")); textSize = 11f
            setPadding(0, 0, 0, 16)
        })

        // 버튼 행
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val btnCancel = Button(context).apply {
            text = "취소"
            setTextColor(Color.parseColor("#888888"))
            setBackgroundColor(Color.parseColor("#1E2530"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = 8 }
            setOnClickListener { dialog.dismiss() }
        }
        val btnSave = Button(context).apply {
            text = "저장"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E5FAA"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                OcrSettings.setOcrEnabled(context, ocrSwitch.isChecked)
                windInput.text.toString().toFloatOrNull()?.let {
                    OcrSettings.setWindLimit(context, it)
                }
                weightInput.text.toString().toFloatOrNull()?.let {
                    OcrSettings.setWeightLimit(context, it)
                }
                OcrSettings.setWeightTon(context, btnTon.isChecked)
                Toast.makeText(context, "설정 저장됨", Toast.LENGTH_SHORT).show()
                onChanged?.invoke()
                dialog.dismiss()
            }
        }
        btnRow.addView(btnCancel); btnRow.addView(btnSave)
        layout.addView(btnRow)

        dialog.setContentView(layout)
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.6).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
