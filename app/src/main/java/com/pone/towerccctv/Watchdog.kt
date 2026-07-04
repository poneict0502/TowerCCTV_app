package com.pone.towerccctv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong

/**
 * 무인 장기운용 자동 복구.
 * - 매일 새벽 자동 재시작(누수/발열 누적 해소)
 * - 메인스레드 행(hang) 감지 시 앱 재시작
 */
object Watchdog {

    private const val NIGHTLY_HOUR  = 4
    private const val HANG_CHECK_MS = 5_000L
    private const val HANG_LIMIT_MS = 20_000L

    private var hangStarted = false
    private val lastResponse = AtomicLong(0)

    // 절전(딥슬립) 동안에는 메인스레드가 멈춰도 정상 → 행 감지 일시 정지(복귀 시 오재시작 방지)
    @Volatile private var sleeping = false
    fun setSleeping(s: Boolean) { sleeping = s; lastResponse.set(SystemClock.elapsedRealtime()) }

    fun restartApp(context: Context) {
        val ctx = context.applicationContext
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(ctx, 1001, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
        Log.w("Watchdog", "앱 재시작 실행")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun scheduleNightlyRestart(context: Context) {
        val ctx = context.applicationContext
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, NIGHTLY_HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        val pi = PendingIntent.getBroadcast(ctx, 2002,
            Intent(ctx, RestartReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        Log.d("Watchdog", "새벽 재시작 예약: ${cal.time}")
    }

    fun startHangWatchdog(context: Context) {
        if (hangStarted) return
        hangStarted = true
        val ctx = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        lastResponse.set(SystemClock.elapsedRealtime())
        val pinger = object : Runnable {
            override fun run() {
                lastResponse.set(SystemClock.elapsedRealtime())
                main.postDelayed(this, HANG_CHECK_MS)
            }
        }
        main.post(pinger)
        Thread {
            while (true) {
                try { Thread.sleep(HANG_CHECK_MS) } catch (e: InterruptedException) { break }
                if (sleeping) { lastResponse.set(SystemClock.elapsedRealtime()); continue }
                val gap = SystemClock.elapsedRealtime() - lastResponse.get()
                if (gap > HANG_LIMIT_MS) {
                    Log.e("Watchdog", "메인스레드 ${gap}ms 무응답 → 재시작")
                    restartApp(ctx); break
                }
            }
        }.apply { isDaemon = true; name = "hang-watchdog" }.start()
    }
}
