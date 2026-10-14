package app.tcg.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PushConflictMigrationTest {
    @Test
    fun migration_6_7_preserves_existing_conflicts_and_adds_push_defaults() {
        val context: Context = RuntimeEnvironment.getApplication()
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE sync_conflict_records (
                        id TEXT NOT NULL PRIMARY KEY, identity TEXT NOT NULL, sequence INTEGER NOT NULL,
                        itemId TEXT NOT NULL, reason TEXT NOT NULL, localPayload TEXT, remotePayload TEXT NOT NULL,
                        resolution TEXT, resolutionOperationId TEXT, resolutionOperation TEXT, resolvedAt INTEGER
                    )""".trimIndent(),
                )
                db.execSQL("INSERT INTO sync_conflict_records (id, identity, sequence, itemId, reason, remotePayload) VALUES ('old', 'acct', 9, 'item', 'stale', '{}')")
            }

            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(callback).build(),
        )

        try {
            val db = helper.writableDatabase
            TcgDatabase.MIGRATION_6_7.migrate(db)

            db.query("SELECT id, source, serverConflictId, resolutionApplied FROM sync_conflict_records WHERE id = 'old'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("old", cursor.getString(0))
                assertEquals("pull", cursor.getString(1))
                assertNull(cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
        } finally {
            helper.close()
        }
    }
}
