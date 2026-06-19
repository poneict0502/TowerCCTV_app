package com.pone.towerccctv

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * 앱 전역에서 공유하는 단일 LibVLC 인스턴스.
 *
 * 화면(액티비티)을 열고 닫을 때마다 LibVLC 를 새로 생성/파괴하면 네이티브 초기화
 * 비용이 매번 발생하고, 장시간 반복 사용 시 카메라 전환·실시간 재생이 점점 느려진다.
 *  → 인스턴스를 한 번만 만들어 앱 수명 동안 재사용한다. (release 하지 않음)
 *
 * network-caching 등 세부 옵션은 각 Media.addOption(":network-caching=N") 으로
 * 개별 지정하므로, 전역 옵션은 공통 기본값만 둔다.
 */
object VlcProvider {
    @Volatile private var instance: LibVLC? = null

    fun get(context: Context): LibVLC =
        instance ?: synchronized(this) {
            instance ?: LibVLC(
                context.applicationContext,
                arrayListOf("--no-audio", "--rtsp-tcp", "--network-caching=300")
            ).also { instance = it }
        }
}
