package com.pone.towerccctv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅 완료 시 앱 자동 실행.
 * (배터리 방전 후 재시동 → 태블릿이 켜지면 감시앱 자동 시작 → 무인 운용)
 * ※ K11 등 일부 기기는 설정의 "자동 실행 허용/백그라운드 실행"에서 앱을 허용해야 동작.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            a == "android.intent.action.QUICKBOOT_POWERON") {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(launch) } catch (_: Exception) {}
        }
    }
}
