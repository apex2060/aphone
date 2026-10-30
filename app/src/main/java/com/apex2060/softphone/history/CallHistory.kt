package com.apex2060.softphone.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.apex2060.softphone.config.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class Direction { INCOMING, OUTGOING }

enum class Outcome { COMPLETED, MISSED, DECLINED, FAILED }

data class CallRecord(
    val id: Long,
    val number: String,
    val direction: Direction,
    val outcome: Outcome,
    val startedAt: Long,
    val durationSec: Long,
    val recording: String? = null,
    val lineId: String? = null,
    val lineLabel: String? = null,
)

/** Call log in a tiny SQLite table. Small enough that every change just reloads the list. */
object CallHistory {
    private lateinit var db: Helper
    private lateinit var app: Context
    private val _records = MutableStateFlow<List<CallRecord>>(emptyList())
    val records: StateFlow<List<CallRecord>> = _records

    fun init(context: Context) {
        app = context.applicationContext
        db = Helper(app)
        ConfigStore.consumeHistoryClaim()?.let { (lineId, label) -> claimUnassigned(lineId, label) }
        reload()
    }

    @Synchronized
    fun add(
        number: String,
        direction: Direction,
        outcome: Outcome,
        startedAt: Long,
        durationSec: Long,
        limit: Int,
        lineId: String? = null,
        lineLabel: String? = null,
    ): Long {
        val w = db.writableDatabase
        val id = w.insert(TABLE, null, ContentValues().apply {
            put("number", number)
            put("direction", direction.name)
            put("outcome", outcome.name)
            put("started_at", startedAt)
            put("duration", durationSec)
            put("line_id", lineId)
            put("line_label", lineLabel)
        })
        w.rawQuery(
            "SELECT recording FROM $TABLE WHERE recording IS NOT NULL AND id NOT IN " +
                "(SELECT id FROM $TABLE ORDER BY started_at DESC LIMIT $limit)",
            null,
        ).use { c ->
            while (c.moveToNext()) deleteSavedFile(c.getString(0))
        }
        w.execSQL("DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY started_at DESC LIMIT $limit)")
        reload()
        return id
    }

    @Synchronized
    fun setRecording(id: Long, fileName: String?): Boolean {
        if (!::db.isInitialized || id <= 0) return false
        val values = ContentValues()
        if (fileName == null) values.putNull("recording") else values.put("recording", fileName)
        val updated = db.writableDatabase.update(TABLE, values, "id=?", arrayOf(id.toString()))
        reload()
        return updated > 0
    }

    @Synchronized
    fun clear() {
        if (!::db.isInitialized) return
        savedDir().listFiles()?.forEach { it.delete() }
        db.writableDatabase.delete(TABLE, null, null)
        reload()
    }

    /** Drops history that belongs to one line, including its saved recordings. */
    @Synchronized
    fun clearLine(lineId: String) {
        if (!::db.isInitialized || lineId.isBlank()) return
        db.writableDatabase.rawQuery(
            "SELECT recording FROM $TABLE WHERE line_id=? AND recording IS NOT NULL",
            arrayOf(lineId),
        ).use { c ->
            while (c.moveToNext()) deleteSavedFile(c.getString(0))
        }
        db.writableDatabase.delete(TABLE, "line_id=?", arrayOf(lineId))
        reload()
    }

    @Synchronized
    private fun claimUnassigned(lineId: String, label: String) {
        db.writableDatabase.execSQL(
            "UPDATE $TABLE SET line_id=?, line_label=? WHERE line_id IS NULL OR line_id=''",
            arrayOf(lineId, label),
        )
    }

    private fun reload() {
        val list = mutableListOf<CallRecord>()
        db.readableDatabase.rawQuery(
            "SELECT id, number, direction, outcome, started_at, duration, recording, line_id, line_label " +
                "FROM $TABLE ORDER BY started_at DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                list += CallRecord(
                    id = c.getLong(0),
                    number = c.getString(1),
                    direction = runCatching { Direction.valueOf(c.getString(2)) }.getOrDefault(Direction.OUTGOING),
                    outcome = runCatching { Outcome.valueOf(c.getString(3)) }.getOrDefault(Outcome.FAILED),
                    startedAt = c.getLong(4),
                    durationSec = c.getLong(5),
                    recording = c.getString(6)?.takeIf { it.isNotBlank() },
                    lineId = c.getString(7)?.takeIf { it.isNotBlank() },
                    lineLabel = c.getString(8)?.takeIf { it.isNotBlank() },
                )
            }
        }
        _records.value = list
    }

    private fun deleteSavedFile(name: String?) {
        if (name.isNullOrBlank()) return
        File(savedDir(), name).delete()
    }

    private fun savedDir(): File = File(app.filesDir, "recordings/saved").apply { mkdirs() }

    private const val TABLE = "calls"

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL, " +
                    "direction TEXT NOT NULL, outcome TEXT NOT NULL, started_at INTEGER NOT NULL, " +
                    "duration INTEGER NOT NULL, recording TEXT, line_id TEXT, line_label TEXT)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE $TABLE ADD COLUMN recording TEXT")
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN line_id TEXT")
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN line_label TEXT")
            }
        }
    }
}
