package dev.crewx.voiceassistant.core.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * A [SqlRow] snapshot of one JDBC row.
 *
 * Values are copied eagerly rather than delegating to a live [ResultSet]:
 * the caller iterates a materialized list after the statement is closed, so a
 * lazily-read row would throw "ResultSet closed" on first access.
 */
class JdbcRow(rs: ResultSet) : SqlRow {
    // Snapshot in the constructor, not lazily: the statement is closed before the
    // caller touches the row, so any read that touches `rs` afterwards fails.
    private val cached: Map<String, Any?> = run {
        val meta = rs.metaData
        (1..meta.columnCount).associate { i ->
            val label = meta.getColumnLabel(i) ?: meta.getColumnName(i)
            label to rs.getObject(i)
        }
    }

    private fun value(column: String): Any? =
        cached[column] ?: cached.entries.firstOrNull { it.key.equals(column, ignoreCase = true) }?.value

    override fun isNull(column: String): Boolean = value(column) == null

    override fun string(column: String): String? = value(column)?.toString()

    override fun long(column: String): Long? = when (val v = value(column)) {
        null -> null
        is Number -> v.toLong()
        else -> v.toString().toLongOrNull()
    }

    override fun int(column: String): Int? = when (val v = value(column)) {
        null -> null
        is Number -> v.toInt()
        else -> v.toString().toIntOrNull()
    }

    override fun double(column: String): Double? = when (val v = value(column)) {
        null -> null
        is Number -> v.toDouble()
        else -> v.toString().toDoubleOrNull()
    }

    override fun blob(column: String): ByteArray? = when (val v = value(column)) {
        null -> null
        is ByteArray -> v
        is String -> v.toByteArray()
        else -> null
    }
}

/**
 * [SqlDb] over JDBC, used by JVM tests to exercise the real schema.
 *
 * This exists so `assistant_schema.sql`, the FTS5 triggers, the CHECK
 * constraints and every query in [Sql] are verified against a real SQLite rather
 * than assumed to work. SQLite JDBC bundles FTS5, so the tier-2 query is tested
 * for real here instead of only on device.
 *
 * Test-only by intent: on device the same SQL runs through SQLCipher.
 */
class JdbcDb(private val connection: Connection) : SqlDb {

    override fun query(sql: String, args: List<Any?>): List<SqlRow> {
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs ->
                val out = mutableListOf<SqlRow>()
                while (rs.next()) out += JdbcRow(rs)
                return out
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            // Some statements in a schema script are result-returning: `PRAGMA
            // journal_mode`, `PRAGMA user_version`, `CREATE VIRTUAL TABLE ...`
            // probes. JDBC's executeUpdate rejects those outright, so fall back to
            // executeQuery and discard. This mirrors how Android's execSQL is
            // used for the same script, which tolerates them.
            try {
                st.executeUpdate()
            } catch (e: java.sql.SQLException) {
                if (!e.message.orEmpty().contains("results", ignoreCase = true)) throw e
                st.executeQuery().use { rs -> while (rs.next()) { /* drain */ } }
            }
        }
    }

    override fun executeScript(sql: String) {
        Sql.splitStatements(sql).forEach { execute(it) }
    }

    companion object {
        /**
         * Opens an in-memory database with the schema applied.
         *
         * Foreign keys are on because the schema declares them; SQLite defaults
         * them off, so without this the cascade deletes would be silently untested.
         */
        fun inMemory(schema: String, vararg pragmas: String): JdbcDb {
            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            val db = JdbcDb(connection)
            pragmas.forEach { db.execute(it) }
            db.execute("PRAGMA foreign_keys = ON")
            db.executeScript(schema)
            return db
        }

        fun schemaFromFile(file: File): String = file.readText()
    }
}
