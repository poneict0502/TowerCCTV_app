package com.pone.towerccctv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** 새벽 재시작 알람 수신 → 앱 재시작. 단, 배터리(시동 OFF) 상태면 절전 유지가 우선이라 스킵하고 다음 새벽만 재예약. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val plugged = try {
            val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            (bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        } catch (e: Exception) { true }
        if (plugged) Watchdog.restartApp(context)
        else Watchdog.scheduleNightlyRestart(context)
    }
}
