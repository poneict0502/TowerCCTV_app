package com.pone.towerccctv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class AlertDatabase(context: Context) :
    SQLiteOpenHelper(context, "alert_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE alerts (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                date_time   TEXT NOT NULL,
                weight      REAL,
                wind_speed  REAL,
                weight_alert INTEGER NOT NULL DEFAULT 0,
                wind_alert   INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS alerts")
        onCreate(db)
    }

    fun insert(record: AlertRecord) {
        writableDatabase.insert("alerts", null, ContentValues().apply {
            put("date_time",    record.dateTime)
            put("weight",       record.weight)
            put("wind_speed",   record.windSpeed)
            put("weight_alert", if (record.weightAlert) 1 else 0)
            put("wind_alert",   if (record.windAlert) 1 else 0)
        })
    }

    fun loadRecent(limit: Int = 200): List<AlertRecord> {
        val list = mutableListOf<AlertRecord>()
        readableDatabase.rawQuery(
            "SELECT * FROM alerts ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(AlertRecord(
                    id           = c.getLong(c.getColumnIndexOrThrow("id")),
                    dateTime     = c.getString(c.getColumnIndexOrThrow("date_time")),
                    weight       = c.getFloat(c.getColumnIndexOrThrow("weight")).let { if (c.isNull(c.getColumnIndexOrThrow("weight"))) null else it },
                    windSpeed    = c.getFloat(c.getColumnIndexOrThrow("wind_speed")).let { if (c.isNull(c.getColumnIndexOrThrow("wind_speed"))) null else it },
                    weightAlert  = c.getInt(c.getColumnIndexOrThrow("weight_alert")) == 1,
                    windAlert    = c.getInt(c.getColumnIndexOrThrow("wind_alert")) == 1
                ))
            }
        }
        return list
    }

    fun exportCsv(): String {
        val sb = StringBuilder("일시,중량(t),풍속(m/s),경보유형\n")
        loadRecent(1000).forEach { r ->
            sb.append("${r.dateTime},")
            sb.append("${r.weight ?: ""},")
            sb.append("${r.windSpeed ?: ""},")
            sb.append("${r.alertType}\n")
        }
        return sb.toString()
    }

    fun deleteAll() = writableDatabase.delete("alerts", null, null)
}
