package com.jenix.stream.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jenix.stream.data.model.AppConstants
import com.jenix.stream.data.model.Schedule
import com.jenix.stream.data.preferences.AppPreferences
import com.jenix.stream.data.repository.AppDatabase
import com.jenix.stream.service.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.*

class StreamScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleAlarm(schedule: Schedule) {
        if (!schedule.enabled) return
        val now = Calendar.getInstance()
        val nextStart = getNextTriggerTime(schedule.startHour, schedule.startMinute, schedule.daysList)
        val nextStop = getNextTriggerTime(schedule.stopHour, schedule.stopMinute, schedule.daysList)

        if (nextStart != null) setAlarm(getStartPendingIntent(schedule.id, true), nextStart)
        if (nextStop != null) setAlarm(getStartPendingIntent(schedule.id, false), nextStop)

        Log.d("Scheduler", "Scheduled: ${schedule.startFormatted}→${schedule.stopFormatted} days=${schedule.days}")
    }

    fun cancelAlarm(scheduleId: Int) {
        alarmManager.cancel(getStartPendingIntent(scheduleId, true))
        alarmManager.cancel(getStartPendingIntent(scheduleId, false))
    }

    private fun setAlarm(pi: PendingIntent, triggerMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun getNextTriggerTime(hour: Int, minute: Int, days: List<String>): Long? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)

        // Find next matching day
        if (days.isEmpty()) return cal.timeInMillis // once - today/tomorrow

        repeat(7) {
            val dayName = getDayName(cal.get(Calendar.DAY_OF_WEEK))
            if (days.contains(dayName)) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun getDayName(calDay: Int) = when (calDay) {
        Calendar.MONDAY -> "mon"; Calendar.TUESDAY -> "tue"; Calendar.WEDNESDAY -> "wed"
        Calendar.THURSDAY -> "thu"; Calendar.FRIDAY -> "fri"; Calendar.SATURDAY -> "sat"
        Calendar.SUNDAY -> "sun"; else -> ""
    }

    private fun getStartPendingIntent(scheduleId: Int, isStart: Boolean): PendingIntent {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            putExtra("schedule_id", scheduleId)
            putExtra("is_start", isStart)
        }
        val reqCode = if (isStart) scheduleId * 10 else scheduleId * 10 + 1
        return PendingIntent.getBroadcast(context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

// ── Alarm Receiver ────────────────────────────────────────────────────────────
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isStart = intent.getBooleanExtra("is_start", true)
        val scheduleId = intent.getIntExtra("schedule_id", -1)
        Log.d("ScheduleAlarm", "Triggered: scheduleId=$scheduleId isStart=$isStart")

        if (isStart) {
            // goAsync() keeps the receiver process alive while the coroutine runs IO
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val prefs = AppPreferences(context)

                    // Load the schedule first so we can pass the label to the service
                    val schedule = db.scheduleDao().getById(scheduleId)
                    val scheduleLabel = schedule?.label ?: ""

                    // Load stream config and user profile (first() suspends until one emission)
                    val config = prefs.streamConfig.first()
                    val userProfile = prefs.userProfile.first()

                    // Pre-populate log with session header BEFORE starting the service
                    StreamingService.appendLog("INFO: ═══════════════════════════════════════")
                    StreamingService.appendLog("INFO: STREAM SESSION START — SCHEDULED")
                    val u = userProfile
                    if (u != null) {
                        val displayName = u.name.ifBlank { u.email }
                        StreamingService.appendLog("INFO: User : $displayName")
                        if (u.email.isNotBlank() && u.name.isNotBlank())
                            StreamingService.appendLog("INFO: Email: ${u.email}")
                        if (u.mobile.isNotBlank())
                            StreamingService.appendLog("INFO: Mobile: ${u.mobile}")
                        if (u.city.isNotBlank())
                            StreamingService.appendLog("INFO: City : ${u.city}")
                    }
                    if (scheduleLabel.isNotBlank()) {
                        StreamingService.appendLog("INFO: Schedule: \"$scheduleLabel\"")
                    }

                    val serviceIntent = StreamingService.buildIntent(context, config, scheduleLabel)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else context.startService(serviceIntent)

                    // Reschedule for next occurrence
                    if (schedule != null && schedule.repeatPattern != "once") {
                        StreamScheduler(context).scheduleAlarm(schedule)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            // Stop stream by schedule
            StreamingService.appendLog("INFO: Stream stopped by schedule")
            context.startService(Intent(context, StreamingService::class.java).apply {
                action = StreamingService.ACTION_STOP
            })
            // Reschedule stop for next occurrence — also needs goAsync for DB access
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val schedule = db.scheduleDao().getById(scheduleId) ?: return@launch
                    if (schedule.repeatPattern != "once") {
                        StreamScheduler(context).scheduleAlarm(schedule)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

// ── Boot Receiver - restore schedules after phone restart ─────────────────────
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        Log.d("BootReceiver", "Boot completed - restoring schedules")
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val scheduler = StreamScheduler(context)
            db.scheduleDao().getEnabledSchedules().forEach { scheduler.scheduleAlarm(it) }
        }
    }
}
