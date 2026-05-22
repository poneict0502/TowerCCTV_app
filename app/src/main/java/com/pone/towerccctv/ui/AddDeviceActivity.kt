package com.pone.towerccctv.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.pone.towerccctv.model.Device
import com.pone.towerccctv.model.DeviceStore
import com.pone.towerccctv.model.DeviceType

class AddDeviceActivity : AppCompatActivity() {

    private lateinit var devType: DeviceType
    private var editId: String? = null

    // 입력 필드들
    private lateinit var etName:     EditText
    private lateinit var etIp:       EditText
    private lateinit var etRtspPort: EditText
    private lateinit var etHttpPort: EditText
    private lateinit var etUser:     EditText
    private lateinit var etPass:     EditText
    private lateinit var etChCount:  EditText   // NVR 전용
    private lateinit var swPtz:      Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        devType = DeviceType.valueOf(intent.getStringExtra("type") ?: "CAMERA")
        editId  = intent.getStringExtra("editId")

        title = when {
            editId != null && devType == DeviceType.CAMERA -> "카메라 수정"
            editId != null && devType == DeviceType.NVR    -> "NVR 수정"
            devType == DeviceType.CAMERA                   -> "카메라 추가"
            else                                           -> "NVR 추가"
        }

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#0F0F12"))
        }

        // 타입 배지
        val tvType = TextView(this).apply {
            text = if (devType == DeviceType.CAMERA) "📷 카메라 (직접 접속)" else "📼 NVR"
            setTextColor(
                if (devType == DeviceType.CAMERA) Color.parseColor("#5B9BFF")
                else Color.parseColor("#4DC47A"))
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvType)

        // 필드 생성 헬퍼
        fun field(label: String, hint: String, defVal: String = "", numeric: Boolean = false): EditText {
            layout.addView(TextView(this).apply {
                text = label; setTextColor(Color.parseColor("#888888")); textSize = 11f
                setPadding(0, 8, 0, 2)
            })
            return EditText(this).apply {
                this.hint = hint; setText(defVal)
                setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#555555"))
                textSize = 14f
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setBackgroundColor(Color.parseColor("#1E1E22"))
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 4 }
                layout.addView(this)
            }
        }

        // 신규 추가 시 기본값 자동 채움
        val isNew = editId == null
        val usedIps = DeviceStore.load(this).map { it.ip }.toSet()
        val nextIp = (101..120).map { "192.168.0.$it" }.firstOrNull { it !in usedIps } ?: "192.168.0.101"
        val lastOctet = nextIp.substringAfterLast(".").toIntOrNull() ?: 101
        val chNum = lastOctet - 100

        etName     = field("장비 이름 ★ 수정 가능", "예: CH1, 타워상부, 계측계",
                        if (isNew) "CH$chNum" else "")
        etIp       = field("IP 주소 ★", "192.168.0.101",
                        if (isNew) nextIp else "")
        etRtspPort = field("RTSP 포트", "554", "554", numeric = true)
        etHttpPort = field("HTTP 포트", "80", "80",  numeric = true)
        etUser     = field("사용자명", "admin",
                        if (isNew) "admin" else "admin")
        etPass     = field("비밀번호", "1234qwer@",
                        if (isNew) "1234qwer@" else "")

        // NVR 전용: 채널 수
        if (devType == DeviceType.NVR) {
            etChCount = field("채널 수", "4  또는  16", "4", numeric = true)
        } else {
            etChCount = EditText(this)   // dummy
        }

        // PTZ 여부
        layout.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#252528"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = 16; it.bottomMargin = 12 }
        })
        val ptzRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        ptzRow.addView(TextView(this).apply {
            text = "PTZ (팬/틸트/줌) 제어 가능"
            setTextColor(Color.parseColor("#AAAAAA")); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        swPtz = Switch(this).apply { isChecked = true }
        ptzRow.addView(swPtz)
        layout.addView(ptzRow)

        // 안내 텍스트
        layout.addView(TextView(this).apply {
            text = "※ Hikvision 기본 포트: RTSP=554, HTTP=80\n" +
                   "※ NVR 채널 수: 테스트=16, 납품=4\n" +
                   "※ 카메라 SD카드 재생은 직접 접속만 지원"
            setTextColor(Color.parseColor("#555555")); textSize = 10f
            setPadding(0, 16, 0, 16)
        })

        // 저장 버튼
        val btnSave = Button(this).apply {
            text = if (editId != null) "수정 저장" else "장비 추가"
            textSize = 15f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E6FCC"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = 8 }
            setOnClickListener { save() }
        }
        layout.addView(btnSave)

        scroll.addView(layout)
        setContentView(scroll)

        // 수정 모드: 기존 값 채우기
        editId?.let { id ->
            DeviceStore.load(this).find { it.id == id }?.let { fillForm(it) }
        }
    }

    private fun fillForm(dev: Device) {
        etName.setText(dev.name)
        etIp.setText(dev.ip)
        etRtspPort.setText(dev.rtspPort.toString())
        etHttpPort.setText(dev.httpPort.toString())
        etUser.setText(dev.username)
        etPass.setText(dev.password)
        if (devType == DeviceType.NVR) etChCount.setText(dev.channelCount.toString())
        swPtz.isChecked = dev.hasPtz
    }

    private fun save() {
        val name = etName.text.toString().trim()
        val ip   = etIp.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()

        if (name.isEmpty() || ip.isEmpty() || user.isEmpty()) {
            Toast.makeText(this, "이름, IP, 사용자명은 필수입니다", Toast.LENGTH_SHORT).show()
            return
        }

        val devices = DeviceStore.load(this)

        val newDev = Device(
            id           = editId ?: System.currentTimeMillis().toString(),
            name         = name,
            type         = devType,
            ip           = ip,
            rtspPort     = etRtspPort.text.toString().toIntOrNull() ?: 554,
            httpPort     = etHttpPort.text.toString().toIntOrNull() ?: 80,
            username     = user,
            password     = pass,
            channelCount = if (devType == DeviceType.NVR)
                               etChCount.text.toString().toIntOrNull() ?: 4
                           else 1,
            hasPtz       = swPtz.isChecked,
            enabled      = true
        )

        if (editId != null) {
            val idx = devices.indexOfFirst { it.id == editId }
            if (idx >= 0) devices[idx] = newDev else devices.add(newDev)
        } else {
            devices.add(newDev)
        }

        DeviceStore.save(this, devices)
        Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show()
        finish()
    }
}
