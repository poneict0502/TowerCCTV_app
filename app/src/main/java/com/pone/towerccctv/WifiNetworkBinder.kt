package com.pone.towerccctv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * 앱의 모든 통신을 "현재 Wi-Fi"에 고정한다. (Tier 1)
 *
 * 인터넷이 없는 로컬 카메라망 AP에 붙어도, 안드로이드가 다른 망으로
 * 라우팅을 바꾸거나 모바일 데이터로 전환하더라도 앱(RTSP/HTTP/PTZ)은
 * 그 Wi-Fi 로만 통신하게 만든다.
 *
 * - requestNetwork(WIFI, 인터넷 불요)로 Wi-Fi 를 잡아두고
 * - onAvailable 시 bindProcessToNetwork(network) 로 프로세스 트래픽을 묶는다
 * - 동일 SSID 2개 AP(브릿지) 로밍 시: 같은 Network 면 그대로 유지,
 *   재생성되면 onAvailable 에서 자동으로 다시 바인딩된다.
 *
 * 주의: 시스템의 Wi-Fi 자동전환 설정 자체는 앱이 끌 수 없다. 이 바인더는
 * "앱 자신의 트래픽"이 Wi-Fi 로만 나가도록 보장하는 방식이다.
 */
class WifiNetworkBinder(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val ok = cm?.bindProcessToNetwork(network) ?: false
            Log.d(TAG, "Wi-Fi 바인딩: $network (ok=$ok)")
        }

        override fun onLost(network: Network) {
            // 현재 바인딩이 이 네트워크였다면 해제 (죽은 망에 묶인 채로 두지 않음)
            if (cm?.boundNetworkForProcess == network) {
                cm.bindProcessToNetwork(null)
                Log.d(TAG, "Wi-Fi 바인딩 해제: $network")
            }
        }
    }

    fun start() {
        val cm = cm ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // 인터넷 검증을 요구하지 않음 → 인터넷 없는 로컬 AP 도 매칭
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(request, callback)
            Log.d(TAG, "Wi-Fi requestNetwork 시작")
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork 실패: ${e.message}")
        }
    }

    fun stop() {
        try { cm?.unregisterNetworkCallback(callback) } catch (e: Exception) {}
        cm?.bindProcessToNetwork(null)
    }

    companion object { private const val TAG = "WifiBinder" }
}
