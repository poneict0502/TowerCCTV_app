package com.pone.towerccctv

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 앱 전역 초기화. 프로세스 시작 시 Wi-Fi 바인더를 켜서
 * 인터넷 없는 로컬 카메라망에서도 앱 통신이 Wi-Fi 로만 나가도록 한다.
 */
class TowerApp : Application() {

    private lateinit var wifiBinder: WifiNetworkBinder

    override fun onCreate() {
        super.onCreate()
        wifiBinder = WifiNetworkBinder(this)
        wifiBinder.start()

        // 무인 장기운용 자동 복구: 매일 새벽 재시작 + 메인스레드 행 감시
        Watchdog.scheduleNightlyRestart(this)
        Watchdog.startHangWatchdog(this)
        // 실사용/장비상태 로그
        UsageLogger.init(this)

        // 모든 화면(액티비티)이 시스템 바를 숨긴 몰입형 전체화면을 유지하도록
        // (장치관리·경보이력 등 전환 화면 포함)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { applyImmersive(a) }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun applyImmersive(a: Activity) {
        val w = a.window ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            w.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
