package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pone.towerccctv.data.AlertDatabase
import com.pone.towerccctv.data.AlertRecord
import java.io.File

class AlertHistoryActivity : AppCompatActivity() {

    private lateinit var db: AlertDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AlertDatabase(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F12"))
        }

        // 상단 버튼 행
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 8)
        }

        val tvTitle = TextView(this).apply {
            text = "📊 경보 이력"
            setTextColor(Color.WHITE); textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnExport = Button(this).apply {
            text = "📤 CSV"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A5A2A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = 8 }
            setOnClickListener { exportCsv() }
        }

        val btnDelete = Button(this).apply {
            text = "🗑 전체삭제"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#5A1A1A"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = 8 }
            setOnClickListener { confirmDelete() }
        }

        val btnBack = Button(this).apply {
            text = "← 닫기"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.parseColor("#252528"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { finish() }
        }

        btnRow.addView(tvTitle)
        btnRow.addView(btnExport)
        btnRow.addView(btnDelete)
        btnRow.addView(btnBack)
        root.addView(btnRow)

        // 헤더
        root.addView(makeHeader())

        // 목록
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val records = db.loadRecent(200)
        if (records.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "경보 이력이 없습니다"
                setTextColor(Color.parseColor("#556677"))
                textSize = 14f; gravity = android.view.Gravity.CENTER
                setPadding(0, 60, 0, 0)
            })
        } else {
            records.forEach { listLayout.addView(makeRow(it)) }
        }

        scroll.addView(listLayout)
        root.addView(scroll)
        setContentView(root)
    }

    private fun makeHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.parseColor("#1A2530"))
        setPadding(12, 10, 12, 10)

        fun cell(text: String, weight: Float) = TextView(this@AlertHistoryActivity).apply {
            this.text = text
            setTextColor(Color.parseColor("#AABBCC"))
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
        addView(cell("일시", 2.5f))
        addView(cell("중량(t)", 1f))
        addView(cell("풍속(m/s)", 1f))
        addView(cell("경보유형", 1.5f))
    }

    private fun makeRow(r: AlertRecord) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val row = LinearLayout(this@AlertHistoryActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 12)
            val isAlert = r.weightAlert || r.windAlert
            setBackgroundColor(
                if (isAlert) Color.parseColor("#1A0A0A") else Color.parseColor("#12181E")
            )
        }

        fun cell(text: String, weight: Float, color: String = "#DDDDDD") =
            TextView(this@AlertHistoryActivity).apply {
                this.text = text
                setTextColor(Color.parseColor(color))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            }

        val alertColor = when {
            r.weightAlert && r.windAlert -> "#FF6666"
            r.weightAlert || r.windAlert -> "#FFAA44"
            else -> "#44BB66"
        }

        row.addView(cell(r.dateTime, 2.5f, "#AABBCC"))
        row.addView(cell(r.weight?.let { "%.2f".format(it) } ?: "--", 1f))
        row.addView(cell(r.windSpeed?.let { "%.1f".format(it) } ?: "--", 1f))
        row.addView(cell(r.alertType, 1.5f, alertColor))

        val div = android.view.View(this@AlertHistoryActivity).apply {
            setBackgroundColor(Color.parseColor("#1E2530"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        addView(row); addView(div)
    }

    private fun exportCsv() {
        try {
            val csv = db.exportCsv()
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA)
            val fileName = "경보이력_${sdf.format(java.util.Date())}.csv"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/AIVION")
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(csv.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "저장 완료! 다운로드/AIVION/$fileName", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("전체 삭제")
            .setMessage("경보 이력을 모두 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                db.deleteAll()
                Toast.makeText(this, "삭제 완료", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("취소", null).show()
    }
}