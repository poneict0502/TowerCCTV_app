package com.pone.towerccctv

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 사설망 CCTV의 자체서명 HTTPS 신뢰.
 * 한화(Wisenet) 등 일부 카메라는 HTTP CGI 를 HTTPS(443)로 강제 리다이렉트하는데,
 * 자체서명 인증서라 기본 OkHttp 는 SSL 핸드셰이크에서 거부한다 → 줌/설정 CGI 실패.
 * 로컬 사설망 카메라 전용(공인망 아님)이므로 인증서 검증을 생략한다.
 */
fun OkHttpClient.Builder.trustSelfSignedCam(): OkHttpClient.Builder {
    val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
    sslSocketFactory(ctx.socketFactory, tm)
    hostnameVerifier { _, _ -> true }
    return this
}
