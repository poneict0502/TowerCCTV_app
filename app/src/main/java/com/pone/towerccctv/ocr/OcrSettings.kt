package com.pone.towerccctv.ocr

import android.content.Context

/**
 * OCR 설정 저장/불러오기
 * - OCR 모드 ON/OFF
 * - 풍속/중량 한계치
 * - ROI 영역 (0.0~1.0 비율)
 */
object OcrSettings {
    private const val PREFS = "ocr_settings"

    // OCR 모드
    fun isOcrEnabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("ocr_enabled", false)

    fun setOcrEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("ocr_enabled", on).apply()

    // 한계치
    fun getWindLimit(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat("wind_limit", 15.0f)  // 기본 15 m/s

    fun setWindLimit(ctx: Context, v: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat("wind_limit", v).apply()

    fun getWeightLimit(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat("weight_limit", 5000.0f)  // 기본 5000 kg

    fun setWeightLimit(ctx: Context, v: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat("weight_limit", v).apply()

    // ROI 영역 (비율 0.0~1.0)
    data class Roi(val x: Float, val y: Float, val w: Float, val h: Float)

    fun getWindRoi(ctx: Context): Roi {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Roi(
            p.getFloat("wind_roi_x", 0.0f), p.getFloat("wind_roi_y", 0.1f),
            p.getFloat("wind_roi_w", 0.5f), p.getFloat("wind_roi_h", 0.35f)
        )
    }

    fun setWindRoi(ctx: Context, roi: Roi) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("wind_roi_x", roi.x).putFloat("wind_roi_y", roi.y)
            .putFloat("wind_roi_w", roi.w).putFloat("wind_roi_h", roi.h).apply()
    }

    fun getWeightRoi(ctx: Context): Roi {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Roi(
            p.getFloat("weight_roi_x", 0.5f), p.getFloat("weight_roi_y", 0.1f),
            p.getFloat("weight_roi_w", 0.5f), p.getFloat("weight_roi_h", 0.35f)
        )
    }

    fun setWeightRoi(ctx: Context, roi: Roi) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("weight_roi_x", roi.x).putFloat("weight_roi_y", roi.y)
            .putFloat("weight_roi_w", roi.w).putFloat("weight_roi_h", roi.h).apply()
    }

    // 최신 OCR 값 저장 (PlayerActivity에서 읽기용)
    fun saveLatestValues(ctx: Context, wind: Float?, weight: Float?,
                         windAlert: Boolean, weightAlert: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("latest_wind",   wind   ?: -1f)
            .putFloat("latest_weight", weight ?: -1f)
            .putBoolean("latest_wind_alert",   windAlert)
            .putBoolean("latest_weight_alert", weightAlert)
            .putLong("latest_time", System.currentTimeMillis())
            .apply()
    }

    fun getLatestWind(ctx: Context): Float? {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("latest_wind", -1f)
        return if (v < 0) null else v
    }
    fun getLatestWeight(ctx: Context): Float? {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("latest_weight", -1f)
        return if (v < 0) null else v
    }
    fun isLatestWindAlert(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("latest_wind_alert", false)
    fun isLatestWeightAlert(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("latest_weight_alert", false)
    fun getLatestTime(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("latest_time", 0L)
}