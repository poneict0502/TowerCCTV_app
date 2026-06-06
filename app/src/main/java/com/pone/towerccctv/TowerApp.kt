package com.pone.towerccctv

import android.app.Application

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
    }
}
