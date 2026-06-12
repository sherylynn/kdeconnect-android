package org.kde.kdeconnect.plugins.portforward

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PortForwardDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "portforward.db"
        private const val DB_VERSION = 3

        @Volatile
        private var instance: PortForwardDatabase? = null

        fun getInstance(context: Context): PortForwardDatabase =
            instance ?: synchronized(this) {
                instance ?: PortForwardDatabase(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NAME TEXT,
                $COL_REMOTE_PORT INTEGER,
                $COL_LOCAL_PORT INTEGER,
                $COL_PROTOCOL TEXT DEFAULT 'tcp',
                $COL_AUTO_START INTEGER DEFAULT 0,
                $COL_STATUS INTEGER DEFAULT 0,
                $COL_CREATED_AT INTEGER,
                $COL_BYTES INTEGER DEFAULT 0,
                $COL_ERROR TEXT
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun addTask(task: PortForwardTask): Long {
        val cv = ContentValues().apply {
            put(COL_NAME, task.name)
            put(COL_REMOTE_PORT, task.remotePort)
            put(COL_LOCAL_PORT, task.localPort)
            put(COL_PROTOCOL, task.protocol)
            put(COL_AUTO_START, if (task.autoStart) 1 else 0)
            put(COL_STATUS, task.status)
            put(COL_CREATED_AT, task.createdAt)
            put(COL_BYTES, task.bytesTransferred)
            put(COL_ERROR, task.errorMessage)
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun updateTask(task: PortForwardTask) {
        val cv = ContentValues().apply {
            put(COL_NAME, task.name)
            put(COL_REMOTE_PORT, task.remotePort)
            put(COL_LOCAL_PORT, task.localPort)
            put(COL_PROTOCOL, task.protocol)
            put(COL_AUTO_START, if (task.autoStart) 1 else 0)
            put(COL_STATUS, task.status)
            put(COL_BYTES, task.bytesTransferred)
            put(COL_ERROR, task.errorMessage)
        }
        writableDatabase.update(TABLE, cv, "$COL_ID=?", arrayOf(task.id.toString()))
    }

    fun deleteTask(id: Long) {
        writableDatabase.delete(TABLE, "$COL_ID=?", arrayOf(id.toString()))
    }

    fun getTask(id: Long): PortForwardTask? {
        val c = readableDatabase.query(TABLE, null, "$COL_ID=?", arrayOf(id.toString()), null, null, null)
        return c.use { if (it.moveToFirst()) cursorToTask(it) else null }
    }

    fun getAllTasks(): List<PortForwardTask> {
        val tasks = mutableListOf<PortForwardTask>()
        val c = readableDatabase.query(TABLE, null, null, null, null, null, "$COL_CREATED_AT DESC")
        c.use {
            while (it.moveToNext()) tasks.add(cursorToTask(it))
        }
        return tasks
    }

    fun getAutoStartTasks(): List<PortForwardTask> {
        val tasks = mutableListOf<PortForwardTask>()
        val c = readableDatabase.query(TABLE, null, "$COL_AUTO_START=?", arrayOf("1"), null, null, null)
        c.use {
            while (it.moveToNext()) tasks.add(cursorToTask(it))
        }
        return tasks
    }

    private fun cursorToTask(c: android.database.Cursor) = PortForwardTask(
        id = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
        name = c.getString(c.getColumnIndexOrThrow(COL_NAME)) ?: "",
        remotePort = c.getInt(c.getColumnIndexOrThrow(COL_REMOTE_PORT)),
        localPort = c.getInt(c.getColumnIndexOrThrow(COL_LOCAL_PORT)),
        protocol = c.getString(c.getColumnIndexOrThrow(COL_PROTOCOL)) ?: "tcp",
        autoStart = c.getInt(c.getColumnIndexOrThrow(COL_AUTO_START)) == 1,
        status = c.getInt(c.getColumnIndexOrThrow(COL_STATUS)),
        createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)),
        bytesTransferred = c.getLong(c.getColumnIndexOrThrow(COL_BYTES)),
        errorMessage = c.getString(c.getColumnIndexOrThrow(COL_ERROR)),
    )

    private val TABLE = "tasks"
    private val COL_ID = "id"
    private val COL_NAME = "name"
    private val COL_REMOTE_PORT = "remote_port"
    private val COL_LOCAL_PORT = "local_port"
    private val COL_PROTOCOL = "protocol"
    private val COL_AUTO_START = "auto_start"
    private val COL_STATUS = "status"
    private val COL_CREATED_AT = "created_at"
    private val COL_BYTES = "bytes_transferred"
    private val COL_ERROR = "error_message"
}
