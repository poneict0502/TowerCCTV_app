package com.pone.towerccctv

import android.content.Context

/**
 * 배터리 종류별 전압 파라미터 + 잔량 해석. RX 는 전압만 보내고, 해석은 전부 여기(앱)서 한다.
 *  - 배터리형(이동식 크레인 NCM): 4칸 표시
 *  - 태양광형(타워 NCM/LFP): 2단계(정상 / 충전 부족)
 * 값은 앱에 저장되며 설정 화면에서 수정 가능. 종류 바꾸면 해당 기본값으로 초기화.
 */
object BatteryProfile {

    enum class Type(val label: String, val twoState: Boolean) {
        NCM_MOBILE("이동식 크레인 · NCM (4칸)", false),
        NCM_SOLAR ("이동식 타워 · NCM 태양광 (정상/부족)", true),
        LFP_SOLAR ("고정 T타워 · LFP 태양광 (정상/부족)", true),
    }

    private const val PREFS = "battery_profile"
    private const val K_ENABLE = "enable"
    private const val K_TYPE   = "type"
    private const val K_CAP    = "cap_ah"
    // 전압은 센티볼트(cv) 정수로 저장. 4칸: b4/b3/b2/b1, 2단계: low/urgent
    private const val K_B4 = "b4"; private const val K_B3 = "b3"; private const val K_B2 = "b2"; private const val K_B1 = "b1"
    private const val K_LOW = "low"; private const val K_URG = "urgent"

    // 종류별 기본값(cv). 실측표 기반(2026-07-02)
    private fun defaults(t: Type): Map<String, Int> = when (t) {
        Type.NCM_MOBILE -> mapOf(K_B4 to 1220, K_B3 to 1160, K_B2 to 1110, K_B1 to 1050) // 12.2/11.6/11.1/10.5
        Type.NCM_SOLAR  -> mapOf(K_LOW to 1110, K_URG to 1050)                            // 11.1 / 10.5
        Type.LFP_SOLAR  -> mapOf(K_LOW to 1280, K_URG to 1200)                            // 12.8 / 12.0
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = p(ctx).getBoolean(K_ENABLE, false)
    fun setEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean(K_ENABLE, on).apply()

    fun type(ctx: Context): Type =
        try { Type.valueOf(p(ctx).getString(K_TYPE, Type.NCM_MOBILE.name)!!) } catch (e: Exception) { Type.NCM_MOBILE }

    fun capacityAh(ctx: Context): Int = p(ctx).getInt(K_CAP, 20)
    fun setCapacity(ctx: Context, ah: Int) = p(ctx).edit().putInt(K_CAP, ah).apply()

    /** 종류 변경 → 해당 기본 임계값으로 초기화 */
    fun setType(ctx: Context, t: Type) {
        val e = p(ctx).edit().putString(K_TYPE, t.name)
        defaults(t).forEach { (k, v) -> e.putInt(k, v) }
        e.apply()
    }

    private fun cv(ctx: Context, key: String, t: Type): Int =
        p(ctx).getInt(key, defaults(t)[key] ?: 0)

    // 임계값 읽기/쓰기 (볼트 단위 입출력)
    fun getThresholdsV(ctx: Context): Map<String, Float> {
        val t = type(ctx)
        return if (t.twoState)
            mapOf("정상↓(충전부족)" to cv(ctx, K_LOW, t) / 100f, "긴급" to cv(ctx, K_URG, t) / 100f)
        else
            mapOf("4칸" to cv(ctx, K_B4, t)/100f, "3칸" to cv(ctx, K_B3, t)/100f,
                  "2칸" to cv(ctx, K_B2, t)/100f, "1칸(경고)" to cv(ctx, K_B1, t)/100f)
    }
    fun setThresholdsV(ctx: Context, values: Map<String, Float>) {
        val t = type(ctx); val e = p(ctx).edit()
        values.forEach { (k, v) ->
            val icv = (v * 100).toInt()
            when (k) {
                "4칸" -> e.putInt(K_B4, icv); "3칸" -> e.putInt(K_B3, icv)
                "2칸" -> e.putInt(K_B2, icv); "1칸(경고)" -> e.putInt(K_B1, icv)
                "정상↓(충전부족)" -> e.putInt(K_LOW, icv); "긴급" -> e.putInt(K_URG, icv)
            }
        }
        e.apply()
    }

    /** 해석 결과. level: 0=정상, 1=경고(1칸/충전부족), 2=긴급 */
    data class View(val twoState: Boolean, val bars: Int, val level: Int, val text: String)

    fun evaluate(ctx: Context, volts: Float): View {
        val t = type(ctx)
        val v = (volts * 100).toInt()   // cv
        return if (t.twoState) {
            val low = cv(ctx, K_LOW, t); val urg = cv(ctx, K_URG, t)
            when {
                v < urg -> View(true, -1, 2, "⚠ 긴급 (충전 급함)")
                v < low -> View(true, -1, 1, "⚠ 충전 부족")
                else    -> View(true, -1, 0, "정상")
            }
        } else {
            val b4 = cv(ctx, K_B4, t); val b3 = cv(ctx, K_B3, t); val b2 = cv(ctx, K_B2, t); val b1 = cv(ctx, K_B1, t)
            val bars = when { v >= b4 -> 4; v >= b3 -> 3; v >= b2 -> 2; v >= b1 -> 1; else -> 0 }
            val level = when (bars) { 0 -> 2; 1 -> 1; else -> 0 }
            val fill = "■".repeat(bars) + "□".repeat(4 - bars)
            View(false, bars, level, "$fill (${bars}칸)")
        }
    }
}
