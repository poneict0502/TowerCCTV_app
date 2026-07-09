package com.pone.towerccctv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper

/**
 * 전원/배터리 감시 + 절전(시동 OFF)·복귀(시동 ON) 제어.
 *
 *  - 전원 분리(=시동 OFF) 후 N분 경과 → onSleep()  (영상 끄고 화면 꺼 배터리 보존)
 *  - 전원 연결(=시동 ON)             → onWake()   (절전 중이었다면 깨워서 복귀)
 *  - 배터리 부족 / 과열 / 충전 불량 → onEvent 경고
 *
 * ※ "시동" 은 시거잭(ACC) 전원이 충전기에 물려 있을 때 성립.
 * ※ K11(MTK)은 충전기만으로 자동 부팅이 안 되므로, 죽지 않게 "절전으로 버티기"가 핵심.
 */
class PowerMonitor(
    private val ctx: Context,
    private val onEvent: (Kind, String) -> Unit,
    private val onStatus: (Status) -> Unit,
    private val onSleep: () -> Unit,
    private val onWake: () -> Unit
) {
    enum class Kind { INFO, WARN, CRIT }
    data class Status(val charging: Boolean, val level: Int, val tempC: Float, val voltageMv: Int)

    @Volatile var status = Status(true, 100, 0f, 0); private set
    @Volatile var sleeping = false; private set

    private val handler = Handler(Looper.getMainLooper())
    private var lowAlertFloor = 101
    private var lastRiseLevel = -1
    private var lastRiseAt = 0L
    private var overheatedAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED    -> onConnected()
                Intent.ACTION_POWER_DISCONNECTED -> onDisconnected()
                Intent.ACTION_BATTERY_CHANGED    -> onBattery(i)
            }
        }
    }

    private val sleepRunnable = Runnable {
        if (!status.charging && !sleeping) {
            sleeping = true
            onEvent(Kind.INFO, "절전 진입 (시동 OFF ${sleepDelayMin()}분)")
            onSleep()
        }
    }

    fun start() {
        val sticky = ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        sticky?.let { onBattery(it) }
        if (!status.charging) {
            handler.postDelayed(sleepRunnable, sleepDelayMs())
        }
        handler.post(tick)
    }

    fun stop() {
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        handler.removeCallbacks(tick)
        handler.removeCallbacks(sleepRunnable)
    }

    /** 수동 절전(테스트/현장 즉시 절전용) */
    fun forceSleep() {
        handler.removeCallbacks(sleepRunnable)
        if (!sleeping) { sleeping = true; onEvent(Kind.INFO, "수동 절전"); onSleep() }
    }

    private fun sleepDelayMin(): Int =
        ctx.getSharedPreferences("power_mon", Context.MODE_PRIVATE)
            .getInt("sleepDelayMin", 5).coerceIn(1, 180)

    private fun sleepDelayMs(): Long = sleepDelayMin() * 60_000L

    private fun onDisconnected() {
        onEvent(Kind.INFO, "전원 분리됨 — ${sleepDelayMin()}분 뒤 절전")
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, sleepDelayMs())
    }

    private fun onConnected() {
        handler.removeCallbacks(sleepRunnable)
        onEvent(Kind.INFO, "전원 연결됨")
        if (sleeping) { sleeping = false; onWake() }
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else status.level
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val volt = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        status = Status(charging, pct, temp, volt)
        onStatus(status)

        if (charging && sleeping) {
            sleeping = false
            handler.removeCallbacks(sleepRunnable)
            onEvent(Kind.INFO, "충전 감지 — 복귀")
            onWake()
        }

        if (charging) {
            lowAlertFloor = 101
            if (pct > lastRiseLevel) { lastRiseLevel = pct; lastRiseAt = now() }
        } else {
            lastRiseLevel = -1
            for (th in intArrayOf(20, 15, 10)) {
                if (pct <= th && lowAlertFloor > th) {
                    lowAlertFloor = th
                    onEvent(if (th <= 10) Kind.CRIT else Kind.WARN, "배터리 부족 ${pct}% — 전원(시동) 연결 필요")
                    break
                }
            }
        }
        if (temp >= 55f && now() - overheatedAt > 5 * 60_000L) {
            overheatedAt = now(); onEvent(Kind.WARN, "배터리 과열 ${temp.toInt()}°C — 환기·차광 필요")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!sleeping) {
                if (status.charging && status.level < 95 && lastRiseAt > 0 && now() - lastRiseAt > 10 * 60_000L) {
                    onEvent(Kind.WARN, "충전 안 됨 — 케이블·포트 확인 (${status.level}%)")
                    lastRiseAt = now()
                }
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    private fun now() = System.currentTimeMillis()
}
