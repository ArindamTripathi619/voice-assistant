package dev.crewx.voiceassistant.data

import android.content.ContentResolver
import android.provider.ContactsContract
import dev.crewx.voiceassistant.core.db.Sql
import dev.crewx.voiceassistant.core.db.SqlDb
import dev.crewx.voiceassistant.core.text.DoubleMetaphone
import dev.crewx.voiceassistant.core.text.TextNormalizer

/**
 * Mirrors Android contacts into `contacts` / `aliases` / `contact_endpoints`.
 *
 * Three decisions worth stating:
 *
 * 1. Only *names and phone numbers* are imported, never message bodies. The
 *    assistant needs to map a spoken name to a number; it has no reason to hold
 *    anyone's conversations, and never collecting them is cheaper than promising
 *    not to read them.
 * 2. Runs on a background thread and is idempotent, keyed on
 *    `android_lookup_key`, so a partial run followed by a crash leaves no
 *    duplicates. A contact with no lookup key (an assistant-only contact) is
 *    never touched by sync.
 * 3. Two bulk queries, not one-per-contact. A phone book with 1,000 contacts
 *    would otherwise mean 1,000 binder round-trips, which on a mid-range device
 *    is seconds of jank nobody will accept for a background job.
 */
class ContactSync(
    private val contentResolver: ContentResolver,
    private val db: SqlDb,
) {

    /** @return number of contacts upserted. */
    fun sync(nowSeconds: Long): Int {
        val names = readNames()
        if (names.isEmpty()) return 0
        val nicknames = readNicknames()
        val phonesByContact = readPhones()

        var upserted = 0
        db.executeScript("BEGIN")   // one transaction: a half-synced phone book is worse than none
        try {
            for (name in names) {
                upsertContact(name, nicknames[name.contactId], nowSeconds)
                syncEndpoints(name.contactId, phonesByContact[name.contactId] ?: emptyList())
                upserted++
            }
            db.executeScript("COMMIT")
        } catch (t: Throwable) {
            db.executeScript("ROLLBACK")
            throw t
        }
        return upserted
    }

    private fun upsertContact(name: AndroidName, nickname: String?, nowSeconds: Long) {
        db.execute(
            """
            INSERT INTO contacts (android_lookup_key, display_name, given_name, family_name, nickname, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(android_lookup_key) DO UPDATE SET
              display_name = excluded.display_name,
              given_name   = excluded.given_name,
              family_name  = excluded.family_name,
              nickname     = COALESCE(excluded.nickname, contacts.nickname),
              updated_at   = excluded.updated_at
            """.trimIndent(),
            listOf(name.lookupKey, name.displayName, name.givenName, name.familyName, nickname, nowSeconds),
        )

        val contactId = contactIdFor(name.lookupKey) ?: return

        // Name aliases only: display name, given, family, nickname. Duplicates are
        // removed by normalising first, so "Bob Smith" and "bob  smith" collapse
        // into one row instead of two competing exact-tier hits.
        val spoken = linkedSetOf(name.displayName, name.givenName, name.familyName, nickname)
        for (raw in spoken) {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) continue
            val norm = TextNormalizer.normalizeForEntity(text)
            if (norm.isEmpty()) continue

            // The phonetic tier has to be populated at import time or it can never
            // fire: "Krishna" heard as "krishhna" is a common ASR miss.
            val keys = DoubleMetaphone.encode(norm)
            db.execute(
                Sql.AliasWrite.INSERT_IGNORE_NAME,
                listOf(contactId, text, norm, keys.first, keys.second),
            )
        }
    }

    private fun contactIdFor(lookupKey: String): Long? =
        db.query(Sql.Contact.BY_LOOKUP_KEY, listOf(lookupKey)).firstOrNull()?.long("id")

    private fun syncEndpoints(contactId: Long, phones: List<AndroidPhone>) {
        for (phone in phones) {
            val number = normalizeNumber(phone.number)
            if (number.isEmpty()) continue
            db.execute(
                """
                INSERT INTO contact_endpoints (contact_id, type, value, label, is_primary)
                VALUES (?, 'phone', ?, ?, ?)
                ON CONFLICT(contact_id, type, value) DO UPDATE SET
                  is_primary = excluded.is_primary,
                  label       = excluded.label
                """.trimIndent(),
                listOf(contactId, number, labelOf(phone.type), if (phone.isPrimary) 1 else 0),
            )
        }
    }

    /**
     * Stores numbers in E.164 where the shape is unambiguous.
     *
     * Deliberately not a full libphonenumber implementation. It handles the forms
     * that actually appear in an address book, and anything it cannot interpret is
     * stored verbatim rather than dropped, because losing a number the user can see
     * in their own contacts app is far worse than storing an oddly formatted one.
     */
    private fun normalizeNumber(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("+")) {
            val digits = trimmed.drop(1).filter { it.isDigit() }
            return if (digits.isEmpty()) "" else "+$digits"
        }
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        // A leading 00 is the international access code in much of the world.
        if (digits.startsWith("00")) return "+${digits.drop(2)}"
        // Below 8 digits this is a short code or an extension, not a number we can
        // safely prefix with "+"; leave it alone rather than inventing a country code.
        return if (digits.length >= 8) "+$digits" else digits
    }

    private fun labelOf(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        else -> "other"
    }

    private fun readNames(): List<AndroidName> =
        queryData(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).mapNotNull { c ->
            val display = c.getStringOrNull(COLUMN_DISPLAY) ?: return@mapNotNull null
            val lookupKey = c.getStringOrNull(COLUMN_LOOKUP_KEY) ?: return@mapNotNull null
            AndroidName(
                contactId = c.getLongOrNull(COLUMN_CONTACT_ID) ?: return@mapNotNull null,
                lookupKey = lookupKey,
                displayName = display,
                givenName = c.getStringOrNull(COLUMN_GIVEN),
                familyName = c.getStringOrNull(COLUMN_FAMILY),
            )
        }

    private fun readNicknames(): Map<Long, String> =
        queryData(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE)
            .mapNotNull { c ->
                val id = c.getLongOrNull(COLUMN_CONTACT_ID) ?: return@mapNotNull null
                val value = c.getStringOrNull(COLUMN_NICKNAME) ?: return@mapNotNull null
                id to value
            }
            .toMap()

    private fun readPhones(): Map<Long, List<AndroidPhone>> =
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            ),
            null,
            null,
            // Primary first: the first row wins the is_primary flag after dedupe.
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
        )?.use { cursor ->
            buildMap<Long, MutableList<AndroidPhone>> {
                val idCol = cursor.getColumnIndexOrThrow(COLUMN_CONTACT_ID)
                val numberCol = cursor.getColumnIndexOrThrow(COLUMN_NUMBER)
                val typeCol = cursor.getColumnIndexOrThrow(COLUMN_TYPE)
                val superCol = cursor.getColumnIndexOrThrow(COLUMN_IS_SUPER_PRIMARY)
                val primaryCol = cursor.getColumnIndexOrThrow(COLUMN_IS_PRIMARY)
                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idCol)
                    val number = cursor.getString(numberCol)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    val isPrimary = cursor.getInt(superCol) == 1 || cursor.getInt(primaryCol) == 1
                    val entry = AndroidPhone(number, cursor.getInt(typeCol), isPrimary)
                    // Sorted primary-first above, so the first row seen for a
                    // number decides its is_primary flag and later duplicates of the
                    // same number are dropped rather than flipping it off.
                    val list = getOrPut(contactId) { mutableListOf() }
                    if (list.none { it.number == number }) list += entry
                }
            }
        } ?: emptyMap()

    /**
     * One pass over [ContactsContract.Data] for a single mimetype.
     *
     * `Data.CONTENT_URI` is the only name-bearing URI that is reliably public, and
     * its joined columns (`DISPLAY_NAME_PRIMARY`, `LOOKUP_KEY`) mean a single query
     * returns everything the importer needs.
     */
    private fun queryData(mimetype: String): List<Snapshot> =
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                COLUMN_CONTACT_ID,
                COLUMN_DISPLAY,
                COLUMN_GIVEN,
                COLUMN_FAMILY,
                COLUMN_LOOKUP_KEY,
            ),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(mimetype),
            null,
        )?.use { cursor ->
            buildList {
                // The cursor cannot outlive the query, so each row is materialised
                // as a detached snapshot before the cursor is closed.
                while (cursor.moveToNext()) {
                    add(Snapshot(cursor))
                }
            }
        } ?: emptyList()

    private data class AndroidName(
        val contactId: Long,
        val lookupKey: String,
        val displayName: String,
        val givenName: String?,
        val familyName: String?,
    )

    private data class AndroidPhone(val number: String, val type: Int, val isPrimary: Boolean)

    /** A single row read eagerly, so it stays valid after the cursor closes. */
    private class Snapshot(cursor: android.database.Cursor) {
        private val values: Map<String, String?> =
            cursor.columnNames.withIndex().associate { (index, name) ->
                name to if (cursor.isNull(index)) null else cursor.getString(index)
            }

        fun getStringOrNull(column: String): String? = values[column]?.takeIf { it.isNotBlank() }
        fun getIntOrNull(column: String): Int? = values[column]?.toIntOrNull()
        fun getLongOrNull(column: String): Long? = values[column]?.toLongOrNull()
    }

    private companion object {
        // `contact_id` is a public, stable Data column but has no constant in the
        // SDK stubs (ContactsContract.Data does not implement an interface that
        // declares it), so it is spelled out here rather than imported.
        const val COLUMN_CONTACT_ID = "contact_id"
        const val COLUMN_DISPLAY = ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
        const val COLUMN_GIVEN = ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME
        const val COLUMN_FAMILY = ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
        const val COLUMN_NICKNAME = ContactsContract.CommonDataKinds.Nickname.NAME
        const val COLUMN_LOOKUP_KEY = ContactsContract.Contacts.LOOKUP_KEY
        const val COLUMN_NUMBER = ContactsContract.CommonDataKinds.Phone.NUMBER
        const val COLUMN_TYPE = ContactsContract.CommonDataKinds.Phone.TYPE
        const val COLUMN_IS_PRIMARY = ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
        const val COLUMN_IS_SUPER_PRIMARY = ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
    }
}
