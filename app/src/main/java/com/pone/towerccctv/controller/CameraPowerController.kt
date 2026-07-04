package com.pone.towerccctv.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * 카메라 배터리 무선 전원 제어 — 앱(BLE Central) ↔ TX(ESP, AIVION-PWR).
 *  - TX 로 ON/OFF 명령 write (1바이트 0x00/0x01)
 *  - TX 가 RX(배터리)에서 받아온 상태(출력·링크·전압)를 4바이트 notify 로 수신
 *    [0]=출력 0/1, [1..2]=전압 센티볼트(uint16 LE), [3]=RX링크 0/1
 *  - 연결 고정: 최초 발견한 TX MAC 저장 → 이후 그 기기에만 autoConnect 재연결(혼신 방지)
 *  - 잔량(4칸/2단계) 해석은 앱(BatteryProfile)에서. 여기선 전압만 전달.
 * 규격: deploy/ESP_무선전원제어_제작의뢰서
 */
@SuppressLint("MissingPermission")
object CameraPowerController {

    private val SVC    = UUID.fromString("7A9B0001-4C2E-4B8F-9D3A-1E2F3A4B5C6D")
    private val CMD    = UUID.fromString("7A9B0002-4C2E-4B8F-9D3A-1E2F3A4B5C6D")
    private val STATUS = UUID.fromString("7A9B0003-4C2E-4B8F-9D3A-1E2F3A4B5C6D")
    private val CCCD   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val DEV_NAME  = "AIVION-PWR"
    private const val PREFS     = "cam_power"
    private const val KEY_MAC   = "tx_mac"
    private const val RESEND_MS = 30_000L

    /** 전압은 센티볼트(1258 = 12.58V) */
    data class Status(val relayOn: Boolean, val rxLinked: Boolean, val cv: Int) {
        val volts: Float get() = cv / 100f
    }

    @Volatile var onUpdate: ((connected: Boolean, status: Status?) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false
    @Volatile private var scanning = false
    @Volatile var lastStatus: Status? = null; private set
    @Volatile private var desiredOn = false
    private var appCtx: Context? = null

    fun isConnected() = connected

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Activity 에서 호출: 컨텍스트 보관 + 연결 시작 + 주기 재전송 시작 */
    fun ensure(ctx: Context) {
        appCtx = ctx.applicationContext
        start()
        main.removeCallbacks(resend); main.postDelayed(resend, RESEND_MS)
    }

    /** 원하는 출력 상태 설정 + 즉시 전송 */
    fun setOutput(on: Boolean) { desiredOn = on; write(if (on) 0x01 else 0x00) }
    fun powerOn()  = setOutput(true)
    fun powerOff() = setOutput(false)

    /** 저장된 TX 본딩 해제(다른 기기로 재페어링용) */
    fun forget(ctx: Context) { prefs(ctx).edit().remove(KEY_MAC).apply(); stop() }

    private val resend = object : Runnable {
        override fun run() {
            if (connected) write(if (desiredOn) 0x01 else 0x00)
            main.postDelayed(this, RESEND_MS)
        }
    }

    private fun start() {
        val ctx = appCtx ?: return
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = mgr.adapter ?: return
        if (!adapter.isEnabled) { notifyUi(); return }
        if (connected || scanning) return
        // 연결 고정: 저장된 TX MAC 있으면 스캔 없이 바로 autoConnect
        val mac = prefs(ctx).getString(KEY_MAC, null)
        if (mac != null) {
            try {
                val dev = adapter.getRemoteDevice(mac)
                gatt = dev.connectGatt(ctx, true, gattCb)   // autoConnect=true → 범위복귀 시 자동 재연결
                return
            } catch (e: Exception) { Log.e("CamPwr", "직접연결 실패: ${e.message}") }
        }
        val scanner = adapter.bluetoothLeScanner ?: return
        scanning = true
        try {
            scanner.startScan(scanCb)
            main.postDelayed({ stopScan() }, 12_000L)
        } catch (e: Exception) { scanning = false; Log.e("CamPwr", "scan 실패: ${e.message}") }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            val mgr = appCtx?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try { result.device.name } catch (_: Exception) { null } ?: result.scanRecord?.deviceName
            if (name == DEV_NAME) {
                val ctx = appCtx ?: return
                prefs(ctx).edit().putString(KEY_MAC, result.device.address).apply()   // 연결 고정 저장
                stopScan()
                connect(result.device)
            }
        }
    }

    private fun connect(dev: android.bluetooth.BluetoothDevice) {
        val ctx = appCtx ?: return
        try { gatt = dev.connectGatt(ctx, true, gattCb) }
        catch (e: Exception) { Log.e("CamPwr", "connect 실패: ${e.message}") }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try { g.discoverServices() } catch (_: Exception) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; cmdChar = null
                notifyUi()
                // autoConnect=true 이므로 gatt 는 닫지 않고 자동 재연결에 맡김
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC) ?: return
            cmdChar = svc.getCharacteristic(CMD)
            val st = svc.getCharacteristic(STATUS) ?: return
            try {
                g.setCharacteristicNotification(st, true)
                st.getDescriptor(CCCD)?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
            } catch (_: Exception) {}
            connected = true
            write(if (desiredOn) 0x01 else 0x00)   // 연결되면 원하는 상태 동기화
            notifyUi()
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == STATUS) parseStatus(c.value)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == STATUS) parseStatus(value)
        }
    }

    private fun parseStatus(v: ByteArray?) {
        if (v == null || v.size < 4) return
        val relay = v[0].toInt() != 0
        val cv    = (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        val link  = v[3].toInt() != 0
        lastStatus = Status(relay, link, cv)
        notifyUi()
    }

    private fun write(b: Int) {
        val g = gatt ?: return; val c = cmdChar ?: return
        try { c.value = byteArrayOf(b.toByte()); g.writeCharacteristic(c) }
        catch (e: Exception) { Log.e("CamPwr", "write 실패: ${e.message}") }
    }

    fun stop() {
        main.removeCallbacks(resend)
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; cmdChar = null; connected = false
    }

    private fun notifyUi() { main.post { onUpdate?.invoke(connected, lastStatus) } }
}
