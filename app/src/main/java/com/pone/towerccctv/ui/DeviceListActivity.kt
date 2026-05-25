package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pone.towerccctv.model.Device
import com.pone.towerccctv.model.DeviceStore
import com.pone.towerccctv.model.DeviceType

class DeviceListActivity : AppCompatActivity() {

    private val devices = mutableListOf<Device>()
    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "장치 관리"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F12"))
        }

        // 상단 버튼 행
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 8)
        }

        // + 추가 드롭다운 버튼
        val btnAdd = Button(this).apply {
            text = "+ 장치 추가"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E6FCC"))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = 8 }
            setOnClickListener { v ->
                val popup = PopupMenu(this@DeviceListActivity, v)
                popup.menu.add(0, 1, 0, "📷  카메라 추가")
                popup.menu.add(0, 2, 1, "📼  NVR 추가 (재생 전용)")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> openAdd(DeviceType.CAMERA)
                        2 -> openAdd(DeviceType.NVR)
                    }
                    true
                }
                popup.show()
            }
        }

        val btnBack = Button(this).apply {
            text = "← 돌아가기"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.parseColor("#252528"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { finish() }
        }

        btnRow.addView(btnAdd)
        btnRow.addView(btnBack)
        root.addView(btnRow)

        // 구분 안내
        val tvHint = TextView(this).apply {
            text = "  카메라: 실시간 영상   NVR: 녹화 재생 전용   탭=수정   길게=삭제"
            setTextColor(Color.parseColor("#556677"))
            textSize = 10f
            setPadding(12, 0, 12, 10)
        }
        root.addView(tvHint)

        // 장치 목록
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DeviceListActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        adapter = DeviceAdapter(devices,
            onEdit   = { openAdd(it.type, it) },
            onDelete = { confirmDelete(it) },
            onToggle = { dev, enabled ->
                val idx = devices.indexOfFirst { it.id == dev.id }
                if (idx >= 0) {
                    devices[idx] = devices[idx].copy(enabled = enabled)
                    DeviceStore.save(this, devices)
                }
            }
        )
        rv.adapter = adapter
        root.addView(rv)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        devices.clear()
        devices.addAll(DeviceStore.load(this))
        adapter.notifyDataSetChanged()
    }

    private fun openAdd(type: DeviceType, edit: Device? = null) {
        startActivity(Intent(this, AddDeviceActivity::class.java).apply {
            putExtra("type", type.name)
            edit?.let { putExtra("editId", it.id) }
        })
    }

    private fun confirmDelete(dev: Device) {
        AlertDialog.Builder(this)
            .setTitle("장치 삭제")
            .setMessage("'${dev.name}' 를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                devices.removeAll { it.id == dev.id }
                DeviceStore.save(this, devices)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("취소", null).show()
    }
}

// ─── 어댑터 ───
class DeviceAdapter(
    private val list: List<Device>,
    private val onEdit:   (Device) -> Unit,
    private val onDelete: (Device) -> Unit,
    private val onToggle: (Device, Boolean) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val tvBadge:  TextView = v.findViewById(100)
        val tvName:   TextView = v.findViewById(101)
        val tvInfo:   TextView = v.findViewById(102)
        val tvStatus: TextView = v.findViewById(103)  // ON/OFF 드롭다운 대체
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 18, 16, 18)
            setBackgroundColor(Color.parseColor("#16161A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 배지
        val badge = TextView(ctx).apply {
            id = 100; textSize = 11f
            setPadding(10, 5, 10, 5)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = 14 }
        }

        // 이름 + 정보
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(ctx).apply {
            id = 101; textSize = 15f
            setTextColor(Color.parseColor("#EEEEEE"))
        }
        val tvInfo = TextView(ctx).apply {
            id = 102; textSize = 11f
            setTextColor(Color.parseColor("#667788"))
            setPadding(0, 3, 0, 0)
        }
        col.addView(tvName); col.addView(tvInfo)

        // ON/OFF 버튼 (드롭다운 방식 - 큰 터치 영역)
        val tvStatus = TextView(ctx).apply {
            id = 103
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(20, 12, 20, 12)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        row.addView(badge); row.addView(col); row.addView(tvStatus)

        val div = android.view.View(ctx).apply {
            setBackgroundColor(Color.parseColor("#222226"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        wrap.addView(row); wrap.addView(div)
        return VH(wrap)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val dev = list[pos]
        val ctx = h.itemView.context

        // 배지
        val isCam = dev.type == DeviceType.CAMERA
        h.tvBadge.text = if (isCam) "CAM" else "NVR"
        h.tvBadge.setBackgroundColor(
            if (isCam) Color.parseColor("#1E3A6E") else Color.parseColor("#1A4A2E"))
        h.tvBadge.setTextColor(
            if (isCam) Color.parseColor("#5B9BFF") else Color.parseColor("#4DC47A"))

        h.tvName.text = dev.name
        h.tvInfo.text = buildString {
            append(dev.ip)
            if (!isCam) append("  ${dev.channelCount}ch")
            if (isCam && !dev.hasPtz) append("  · PTZ없음")
        }

        // ON/OFF 상태 버튼 (드롭다운으로 선택)
        updateStatusBtn(h.tvStatus, dev.enabled)

        h.tvStatus.setOnClickListener { v ->
            val popup = PopupMenu(ctx, v)
            popup.menu.add(0, 1, 0, "✅  사용 (ON)")
            popup.menu.add(0, 2, 1, "⛔  미사용 (OFF)")
            popup.setOnMenuItemClickListener { item ->
                val enabled = item.itemId == 1
                updateStatusBtn(h.tvStatus, enabled)
                onToggle(dev, enabled)
                true
            }
            popup.show()
        }

        // 탭 = 수정, 길게 = 삭제
        h.itemView.setOnClickListener { onEdit(dev) }
        h.itemView.setOnLongClickListener { onDelete(dev); true }
    }

    private fun updateStatusBtn(tv: TextView, enabled: Boolean) {
        if (enabled) {
            tv.text = "ON"
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.parseColor("#1A6A2A"))
        } else {
            tv.text = "OFF"
            tv.setTextColor(Color.parseColor("#AAAAAA"))
            tv.setBackgroundColor(Color.parseColor("#333333"))
        }
    }

    override fun getItemCount() = list.size
}
