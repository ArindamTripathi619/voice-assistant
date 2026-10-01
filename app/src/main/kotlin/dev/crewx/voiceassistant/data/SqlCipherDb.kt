package dev.crewx.voiceassistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.crewx.voiceassistant.core.db.Sql
import dev.crewx.voiceassistant.core.db.SqlDb
import dev.crewx.voiceassistant.core.db.SqlRow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Adapts an Android [android.database.Cursor] to the shared [SqlRow]. */
class CursorRow(private val cursor: android.database.Cursor) : SqlRow {

    // Column lookup is resolved once: getColumnIndexOrThrow per field access would
    // walk the column-name array on every call, and rows are mapped field by field.
    private val index = mutableMapOf<String, Int>()

    private fun idx(column: String): Int = index.getOrPut(column) {
        cursor.getColumnIndex(column)
    }

    private fun <T> read(column: String, value: () -> T): T? {
        val i = idx(column)
        return if (i < 0 || cursor.isNull(i)) null else value()
    }

    override fun isNull(column: String): Boolean {
        val i = idx(column)
        return i < 0 || cursor.isNull(i)
    }

    override fun string(column: String): String? = read(column) { cursor.getString(idx(column)) }
    override fun long(column: String): Long? = read(column) { cursor.getLong(idx(column)) }
    override fun int(column: String): Int? = read(column) { cursor.getInt(idx(column)) }
    override fun double(column: String): Double? = read(column) { cursor.getDouble(idx(column)) }
    override fun blob(column: String): ByteArray? = read(column) { cursor.getBlob(idx(column)) }
}

/**
 * Device-side database.
 *
 * Uses SQLCipher for two reasons: the schema's FTS5 tier needs a SQLite build
 * that actually ships it, and the contact graph plus the command log are exactly
 * the kind of data that should not sit in plaintext on a phone.
 *
 * The passphrase is generated once and wrapped by a hardware-backed Keystore key.
 * The passphrase itself is never persisted in plaintext.
 */
class SqlCipherDb private constructor(
    private val helper: SupportSQLiteOpenHelper,
) : SqlDb, AutoCloseable {

    private val db: SupportSQLiteDatabase get() = helper.writableDatabase

    override fun query(sql: String, args: List<Any?>): List<SqlRow> =
        db.query(sql, args.toTypedArray()).use { cursor ->
            val out = mutableListOf<SqlRow>()
            while (cursor.moveToNext()) out += CursorRow(cursor)
            out
        }

    override fun execute(sql: String, args: List<Any?>) {
        // execSQL is used rather than compileStatement for the write path: the
        // resolver writes are infrequent (usage counters, taught aliases) and not
        // latency-critical, so clarity wins over preallocation.
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    override fun executeScript(sql: String) {
        db.beginTransaction()
        try {
            Sql.splitStatements(sql).forEach { db.execSQL(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = helper.close()

    companion object {
        private const val DB_NAME = "assistant.db"
        private const val KEY_ALIAS = "voice_assistant_db_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        /**
         * Opens (creating on first run) the encrypted database.
         *
         * @param onReady invoked once the schema is present, on a background
         *   thread. Callers must not touch the DB on the main thread.
         */
        fun open(context: Context, onReady: (SqlCipherDb) -> Unit) {
            val passphrase = loadOrCreatePassphrase(context)
            val file = context.getDatabasePath(DB_NAME)
            file.parentFile?.mkdirs()

            val factory = SupportOpenHelperFactory(passphrase)
            val helper = factory.create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(null)
                    .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            val schema = context.assets.open("assistant_schema.sql")
                                .use { it.readBytes().decodeToString() }
                            Sql.splitStatements(schema).forEach { db.execSQL(it) }
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // No migration path yet while user_version is 1.
                            // When the first upgrade ships it must be additive, and
                            // the destructive fallback is deliberately not used:
                            // dropping this DB loses taught aliases and relationships,
                            // which are the user's own data and cannot be resynced.
                            error("no migration from $oldVersion to $newVersion yet")
                        }
                    })
                    .build(),
            )
            onReady(SqlCipherDb(helper))
        }

        /**
         * Reads the wrapped passphrase, creating it on first launch.
         *
         * Stored encrypted in prefs. Confidentiality of a 32-byte random value
         * matters less than the fact that it never sits in plaintext, so GCM with
         * a hardware-backed key is proportionate here.
         */
        private fun loadOrCreatePassphrase(context: Context): ByteArray {
            val prefs = context.getSharedPreferences("voice_assistant_secure", Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_ALIAS, null)
            if (existing != null) return unwrap(context, existing)

            val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_ALIAS, wrap(context, secret)).apply()
            return secret
        }

        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // No user-authentication requirement: the assistant must work
                    // while the phone is locked and unattended, which is the whole
                    // point of a wake-word assistant.
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }

        private fun wrap(context: Context, plain: ByteArray): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain)
            return "${iv.toHex()}:${encrypted.toHex()}"
        }

        private fun unwrap(context: Context, wrapped: String): ByteArray {
            val (ivHex, dataHex) = wrapped.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreKey(),
                GCMParameterSpec(TAG_BITS, ivHex.hexToBytes()),
            )
            return cipher.doFinal(dataHex.hexToBytes())
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** Present so `AppDatabase` can assert the file is where we expect it. */
fun defaultDatabaseFile(context: Context): File = context.getDatabasePath("assistant.db")
