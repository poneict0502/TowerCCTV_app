package com.pone.towerccctv

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.DispatchingAuthenticator
import com.burgstaller.okhttp.basic.BasicAuthenticator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 카메라용 OkHttpClient 팩토리 — **모든 카메라 HTTP 인증은 이 함수 하나로만 만든다.**
 * Digest+Basic 인증 · 계정 앞뒤공백 제거 · 한화 자체서명 HTTPS 신뢰를 한 곳에 모음.
 * ※ 인증 관련 변경은 여기만 고치면 PTZ·스냅샷·최적화·시간동기·OCR·녹화재생 전 경로에 반영된다.
 */
fun camHttpClient(
    user: String, pass: String,
    connectSec: Long = 4, readSec: Long = 5, writeSec: Long = 10
): OkHttpClient {
    val cache = ConcurrentHashMap<String, CachingAuthenticator>()
    val creds = Credentials(user.trim(), pass.trim())
    val auth = DispatchingAuthenticator.Builder()
        .with("digest", DigestAuthenticator(creds))
        .with("basic", BasicAuthenticator(creds))
        .build()
    return OkHttpClient.Builder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(readSec, TimeUnit.SECONDS)
        .writeTimeout(writeSec, TimeUnit.SECONDS)
        .authenticator(CachingAuthenticatorDecorator(auth, cache))
        .addInterceptor(AuthenticationCacheInterceptor(cache))
        .trustSelfSignedCam()
        .build()
}

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
