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
 * (description = the SQL with string, number and blob literals replaced by
 * `?` and comments removed; bound arguments are never recorded).
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
        BugCapsule.activeSpan()?.takeIf { !it.isFinished }?.startChild("db.sql.query", SqlText.withoutLiterals(sql).trim().take(2000))?.also {
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

/**
 * SQL for span descriptions: literal values become `?`, so data typed into
 * a statement (`WHERE email = 'a@b.c'`) never leaves the device, while the
 * statement's shape (keywords, identifiers, parameters) stays.
 */
internal object SqlText {
    fun withoutLiterals(sql: String): String {
        val out = StringBuilder(sql.length)
        var i = 0
        val n = sql.length
        fun identChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        while (i < n) {
            val c = sql[i]
            when {
                // 'string' ('' escapes a quote) and X'blob'.
                c == '\'' || ((c == 'x' || c == 'X') && i + 1 < n && sql[i + 1] == '\'' && (i == 0 || !identChar(sql[i - 1]))) -> {
                    i = if (c == '\'') i + 1 else i + 2
                    while (i < n) {
                        if (sql[i] == '\'') {
                            if (i + 1 < n && sql[i + 1] == '\'') i += 2 else break
                        } else {
                            i++
                        }
                    }
                    i++ // closing quote
                    out.append('?')
                }
                // Quoted identifiers stay as they are.
                c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    val end = sql.indexOf(close, i + 1).let { if (it < 0) n - 1 else it }
                    out.append(sql, i, end + 1)
                    i = end + 1
                }
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    val end = sql.indexOf('\n', i).let { if (it < 0) n else it }
                    i = end
                    out.append(' ')
                }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    val end = sql.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    i = end
                    out.append(' ')
                }
                // Numbers not part of an identifier or a numbered parameter (?1, :p1, $1).
                (c.isDigit() || (c == '.' && i + 1 < n && sql[i + 1].isDigit())) &&
                    (i == 0 || !(identChar(sql[i - 1]) || sql[i - 1] == '?' || sql[i - 1] == ':' || sql[i - 1] == '@')) -> {
                    i++
                    while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '.' || sql[i] == '_' ||
                            ((sql[i] == '+' || sql[i] == '-') && (sql[i - 1] == 'e' || sql[i - 1] == 'E')))
                    ) i++
                    out.append('?')
                }
                identChar(c) -> {
                    val start = i
                    while (i < n && identChar(sql[i])) i++
                    out.append(sql, start, i)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
