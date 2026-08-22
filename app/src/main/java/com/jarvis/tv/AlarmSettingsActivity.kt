package com.jarvis.tv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

class AlarmSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32) }
        val title = TextView(this).apply { text = "Alarma y briefing de Jarvis"; textSize = 28f }
        val info = TextView(this).apply { text = "Al sonar, Jarvis abrirá un briefing hablado con tiempo, noticias y datos sincronizados del móvil." }
        val picker = TimePicker(this).apply { setIs24HourView(true); hour = prefs.getInt("alarm_hour", 8); minute = prefs.getInt("alarm_minute", 0) }
        val daily = CheckBox(this).apply { text = "Repetir todos los días"; isChecked = prefs.getBoolean("alarm_daily", true) }
        val save = Button(this).apply { text = "PROGRAMAR ALARMA" }
        val cancel = Button(this).apply { text = "CANCELAR ALARMA" }
        box.addView(title); box.addView(info); box.addView(picker); box.addView(daily); box.addView(save); box.addView(cancel)
        setContentView(box)

        save.setOnClickListener {
            prefs.edit().putInt("alarm_hour", picker.hour).putInt("alarm_minute", picker.minute).putBoolean("alarm_daily", daily.isChecked).apply()
            schedule(picker.hour, picker.minute)
            Toast.makeText(this, "Alarma Jarvis programada", Toast.LENGTH_LONG).show()
        }
        cancel.setOnClickListener {
            alarmManager().cancel(alarmIntent())
            prefs.edit().putBoolean("alarm_enabled", false).apply()
            Toast.makeText(this, "Alarma cancelada", Toast.LENGTH_LONG).show()
        }
    }

    private fun schedule(hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = alarmManager()
        val pi = alarmIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        }
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putBoolean("alarm_enabled", true).putLong("alarm_next", next.timeInMillis).apply()
    }

    private fun alarmManager() = getSystemService(ALARM_SERVICE) as AlarmManager
    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(this, 6405, Intent(this, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
