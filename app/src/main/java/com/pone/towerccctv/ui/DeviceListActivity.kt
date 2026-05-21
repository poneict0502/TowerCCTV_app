package com.pone.towerccctv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F12"))
        }

        // 상단 버튼 행
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 12, 12, 8)
        }
        val btnAddCam = Button(this).apply {
            text = "+ 카메라 추가"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E6FCC"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.marginEnd = 8 }
            setOnClickListener { openAdd(DeviceType.CAMERA) }
        }
        val btnAddNvr = Button(this).apply {
            text = "+ NVR 추가"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A7A4A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { openAdd(DeviceType.NVR) }
        }
        btnRow.addView(btnAddCam)
        btnRow.addView(btnAddNvr)

        // 안내 텍스트
        val tvHint = TextView(this).apply {
            text = "  길게 누르면 삭제  |  탭하면 수정"
            setTextColor(Color.parseColor("#666666"))
            textSize = 11f
            setPadding(12, 0, 12, 8)
        }

        // 장비 목록
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

        root.addView(btnRow)
        root.addView(tvHint)
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
            .setTitle("장비 삭제")
            .setMessage("'${dev.name}' 를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                devices.removeAll { it.id == dev.id }
                DeviceStore.save(this, devices)
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}

// ─── RecyclerView 어댑터 ───────────────────────────────────────
class DeviceAdapter(
    private val list: List<Device>,
    private val onEdit:   (Device) -> Unit,
    private val onDelete: (Device) -> Unit,
    private val onToggle: (Device, Boolean) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvType:  TextView = v.findViewById(100)
        val tvName:  TextView = v.findViewById(101)
        val tvInfo:  TextView = v.findViewById(102)
        val swEnable: Switch  = v.findViewById(103)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.parseColor("#16161A"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val badge = TextView(parent.context).apply {
            id = 100; textSize = 10f; setPadding(6, 3, 6, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginEnd = 10 }
        }
        val col = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(parent.context).apply {
            id = 101; textSize = 14f; setTextColor(Color.parseColor("#DDDDDD"))
        }
        val tvInfo = TextView(parent.context).apply {
            id = 102; textSize = 11f; setTextColor(Color.parseColor("#777777"))
        }
        col.addView(tvName); col.addView(tvInfo)

        val sw = Switch(parent.context).apply {
            id = 103
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        row.addView(badge); row.addView(col); row.addView(sw)

        // 구분선 추가
        val wrap = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val div = View(parent.context).apply {
            setBackgroundColor(Color.parseColor("#252528"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        wrap.addView(row); wrap.addView(div)
        return VH(wrap)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val dev = list[pos]

        // 배지 스타일
        h.tvType.text = if (dev.type == DeviceType.CAMERA) "CAM" else "NVR"
        h.tvType.setBackgroundColor(
            if (dev.type == DeviceType.CAMERA) Color.parseColor("#1E3A6E")
            else Color.parseColor("#1A4A2E"))
        h.tvType.setTextColor(
            if (dev.type == DeviceType.CAMERA) Color.parseColor("#5B9BFF")
            else Color.parseColor("#4DC47A"))

        h.tvName.text = dev.name
        h.tvInfo.text = buildString {
            append(dev.ip)
            if (dev.type == DeviceType.NVR) append("  ${dev.channelCount}ch")
            append("  |  ${dev.username}")
            if (!dev.hasPtz) append("  [PTZ 없음]")
        }
        h.swEnable.isChecked = dev.enabled
        h.swEnable.setOnCheckedChangeListener { _, on -> onToggle(dev, on) }

        h.itemView.setOnClickListener { onEdit(dev) }
        h.itemView.setOnLongClickListener { onDelete(dev); true }
    }

    override fun getItemCount() = list.size
}
