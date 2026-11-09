package dev.bugcapsule.sqlite

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import dev.bugcapsule.android.BugCapsule
import dev.bugcapsule.android.BugCapsuleConfig
import dev.bugcapsule.android.core.CaptureMode
import dev.bugcapsule.android.core.J
import dev.bugcapsule.android.internal.BugCapsuleTestOverrides
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SQLiteSpansTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = null
    }

    @Test
    fun queriesAndStatementsBecomeDbSpansOfTheActiveTransaction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sent = Collections.synchronizedList(ArrayList<Map<String, Any?>>())
        BugCapsule.resetForTest()
        BugCapsule.testOverrides = BugCapsuleTestOverrides(tmp.root)
        BugCapsule.init(
            app,
            BugCapsuleConfig.builder().mode(CaptureMode.QA).tracesSampleRate(1.0)
                .beforeSendTransaction { tx -> tx.also { sent.add(it.payload) } }.build(),
        )
        // Monitoring starts once init's background preparation is done (queued before this);
        // a transaction started before that is a no-op.
        BugCapsule.flushUploads().get(10, TimeUnit.SECONDS)
        val helper = BugCapsuleSQLiteOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()).create(
            SupportSQLiteOpenHelper.Configuration.builder(app).name("shop.db").callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = db.execSQL("CREATE TABLE cart (id INTEGER PRIMARY KEY, sku TEXT)")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                },
            ).build(),
        )
        val db = helper.writableDatabase
        val tx = BugCapsule.startTransaction("Cart", "ui.load", bindToScope = true)
        db.insert("cart", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply { put("sku", "pin") })
        db.query("SELECT sku FROM cart WHERE id = ?", arrayOf(1)).use { assertTrue(it.moveToFirst()) }
        db.compileStatement("DELETE FROM cart WHERE sku = ?").apply { bindString(1, "pin") }.executeUpdateDelete()
        db.execSQL("UPDATE cart SET sku = 'secret-sku' WHERE id = 42")
        tx.finish()
        val spans = J.list(sent.single()["spans"])!!.map { J.obj(it)!! }
        assertEquals(listOf("db.sql.query", "db.sql.query", "db.sql.query", "db.sql.query"), spans.map { it["op"] })
        assertEquals(
            listOf("INSERT INTO cart", "SELECT sku FROM cart WHERE id = ?", "DELETE FROM cart WHERE sku = ?", "UPDATE cart SET sku = ? WHERE id = ?"),
            spans.map { it["description"] },
        )
        assertEquals("sqlite", J.obj(spans[0]["data"])!!["db.system"])
        assertEquals("shop.db", J.obj(spans[0]["data"])!!["db.name"])
        helper.close()
    }

    @Test
    fun literalsBecomePlaceholdersAndTheShapeStays() {
        fun clean(sql: String) = SqlText.withoutLiterals(sql)
        assertEquals("SELECT * FROM users WHERE email = ? AND age > ?", clean("SELECT * FROM users WHERE email = 'a@b.c' AND age > 30"))
        assertEquals("INSERT INTO t (a, b, c) VALUES (?, -?, ?)", clean("INSERT INTO t (a, b, c) VALUES ('it''s', -1.5e-3, X'DEADBEEF')"))
        assertEquals("SELECT \"col 1\", [x], `y`, t1.c2 FROM t1 WHERE n = ?1 OR m = :p2 OR k = ?", clean("SELECT \"col 1\", [x], `y`, t1.c2 FROM t1 WHERE n = ?1 OR m = :p2 OR k = 0x1F"))
        assertEquals("SELECT a FROM t    WHERE b = ?", clean("SELECT a FROM t /* user 'bob' */ -- note 7\nWHERE b = 'x'").replace("\n", ""))
        assertEquals("SELECT ?", clean("SELECT 'unterminated"))
        assertEquals("SELECT sku FROM cart WHERE id = ?", clean("SELECT sku FROM cart WHERE id = ?"))
    }
}
