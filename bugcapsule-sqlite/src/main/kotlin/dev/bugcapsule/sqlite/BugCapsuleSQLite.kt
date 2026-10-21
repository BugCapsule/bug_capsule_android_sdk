package dev.bugcapsule.sqlite

import android.content.ContentValues
import android.database.Cursor
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.monitoring.Span
import dev.bugcapsule.android.monitoring.SpanStatus

/**
 * SQLite / Room spans (monitoring-v1 §8, `db.sql.query`): wraps a
 * [SupportSQLiteOpenHelper.Factory] so every query, statement and
 * insert/update/delete running while a span is active becomes a child span
 * (description = the SQL; bound arguments are never recorded).
 *
 * ```kotlin
 * Room.databaseBuilder(context, ShopDb::class.java, "shop.db")
 *     .openHelperFactory(BugCapsuleSQLiteOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))
 *     .build()
 * ```
 */
class BugCapsuleSQLiteOpenHelperFactory(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        BugCapsuleSQLiteOpenHelper(delegate.create(configuration))
}

/** An open helper whose databases create spans; see [BugCapsuleSQLiteOpenHelperFactory]. */
class BugCapsuleSQLiteOpenHelper(private val delegate: SupportSQLiteOpenHelper) : SupportSQLiteOpenHelper by delegate {
    override val writableDatabase: SupportSQLiteDatabase
        get() = TracingDatabase(delegate.writableDatabase, delegate.databaseName)
    override val readableDatabase: SupportSQLiteDatabase
        get() = TracingDatabase(delegate.readableDatabase, delegate.databaseName)
}

internal object DbSpans {
    fun <T> trace(sql: String, dbName: String?, block: () -> T): T {
        val span = start(sql, dbName) ?: return block()
        try {
            val result = block()
            span.finish(SpanStatus.OK)
            return result
        } catch (e: Throwable) {
            span.finish(SpanStatus.INTERNAL_ERROR)
            throw e
        }
    }

    private fun start(sql: String, dbName: String?): Span? = try {
        BugCapsule.activeSpan()?.takeIf { !it.isFinished }?.startChild("db.sql.query", sql.trim().take(2000))?.also {
            it.setData("db.system", "sqlite")
            if (dbName != null) it.setData("db.name", dbName)
        }
    } catch (_: Throwable) {
        null
    }
}

internal class TracingDatabase(private val delegate: SupportSQLiteDatabase, private val dbName: String?) :
    SupportSQLiteDatabase by delegate {
    override fun compileStatement(sql: String): SupportSQLiteStatement =
        TracingStatement(delegate.compileStatement(sql), sql, dbName)

    override fun query(query: String): Cursor = DbSpans.trace(query, dbName) { delegate.query(query) }

    override fun query(query: String, bindArgs: Array<out Any?>): Cursor =
        DbSpans.trace(query, dbName) { delegate.query(query, bindArgs) }

    override fun query(query: SupportSQLiteQuery): Cursor = DbSpans.trace(query.sql, dbName) { delegate.query(query) }

    override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
        DbSpans.trace(query.sql, dbName) { delegate.query(query, cancellationSignal) }

    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long =
        DbSpans.trace("INSERT INTO $table", dbName) { delegate.insert(table, conflictAlgorithm, values) }

    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int =
        DbSpans.trace("DELETE FROM $table" + (whereClause?.let { " WHERE $it" } ?: ""), dbName) {
            delegate.delete(table, whereClause, whereArgs)
        }

    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int =
        DbSpans.trace("UPDATE $table" + (whereClause?.let { " WHERE $it" } ?: ""), dbName) {
            delegate.update(table, conflictAlgorithm, values, whereClause, whereArgs)
        }

    override fun execSQL(sql: String) = DbSpans.trace(sql, dbName) { delegate.execSQL(sql) }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = DbSpans.trace(sql, dbName) { delegate.execSQL(sql, bindArgs) }
}

internal class TracingStatement(
    private val delegate: SupportSQLiteStatement,
    private val sql: String,
    private val dbName: String?,
) : SupportSQLiteStatement by delegate {
    override fun execute() = DbSpans.trace(sql, dbName) { delegate.execute() }
    override fun executeUpdateDelete(): Int = DbSpans.trace(sql, dbName) { delegate.executeUpdateDelete() }
    override fun executeInsert(): Long = DbSpans.trace(sql, dbName) { delegate.executeInsert() }
    override fun simpleQueryForLong(): Long = DbSpans.trace(sql, dbName) { delegate.simpleQueryForLong() }
    override fun simpleQueryForString(): String? = DbSpans.trace(sql, dbName) { delegate.simpleQueryForString() }
}
