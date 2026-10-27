package network.lapis.cloud.server.lupabb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A `{id, bezeichnung}` pair (status, country, regional association). */
internal data class LupaBbRef(
    val id: Long,
    val label: String,
)

/** One member as the API sends it: every field exactly as received (strings untouched), numbers already parsed. */
internal data class WireMember(
    val personNumber: Long?,
    val statusId: Long?,
    val company: String?,
    val title: String?,
    val givenName: String?,
    val familyName: String?,
    val nameAffix: String?,
    val personType: String?,
    val birthDate: String?,
    val street: String?,
    val houseNumber: String?,
    val poBox: String?,
    val city: String?,
    val postalCode: String?,
    val addressSupplement: String?,
    val countryId: Long?,
    val email: String?,
    val phone: String?,
    val mobile: String?,
    /** Optional, not part of the known payload: the id of the regional association, if LupaBB ever sends it. */
    val regionalAssociationId: Long?,
    /** Optional, not part of the known payload: the joining date, if LupaBB ever sends it. */
    val joinedAt: String?,
)

/** One page of members plus the pagination of the API. */
internal class LupaBbMemberPage(
    val members: List<WireMember>,
    val current: Int?,
    val max: Int?,
)

/**
 * V1.9.226 -- the tolerant reader of the LupaBB payloads. Parsed by hand from the JSON tree instead of a `@Serializable` class on purpose: the
 * API is a PHP backend that sends numbers sometimes as numbers and sometimes as quoted strings, and an unexpected type must end in
 * [LupaBbErrorCode.UNEXPECTED_RESPONSE] without the text of a decoder exception (which would quote the payload).
 */
internal object LupaBbWire {
    private val json = Json { isLenient = false }

    fun parseRefs(body: String): List<LupaBbRef> {
        val root = root(body)
        val data = root["data"] as? JsonArray ?: unexpected()
        return data.map { element ->
            val obj = element as? JsonObject ?: unexpected()
            val id = obj.long("id") ?: unexpected()
            val label = obj.string("bezeichnung") ?: unexpected()
            LupaBbRef(id = id, label = label)
        }
    }

    fun parseMemberPage(body: String): LupaBbMemberPage {
        val root = root(body)
        val data = root["data"] as? JsonArray ?: unexpected()
        val pagination = root["pagination"] as? JsonObject
        val members =
            data.map { element ->
                val o = element as? JsonObject ?: unexpected()
                WireMember(
                    personNumber = o.long("personennummer"),
                    statusId = o.long("mitglied_status"),
                    company = o.string("firma"),
                    title = o.string("titel"),
                    givenName = o.string("vorname"),
                    familyName = o.string("nachname"),
                    nameAffix = o.string("namenszusatz"),
                    personType = o.string("personentyp"),
                    birthDate = o.string("geburtstag"),
                    street = o.string("strasse"),
                    houseNumber = o.string("hausnummer"),
                    poBox = o.string("postfach"),
                    city = o.string("ort"),
                    postalCode = o.string("postleitzahl"),
                    addressSupplement = o.string("adresszusatz"),
                    countryId = o.long("land"),
                    email = o.string("email"),
                    phone = o.string("telefon"),
                    mobile = o.string("mobil"),
                    regionalAssociationId = o.long("landesverband"),
                    joinedAt = o.string("eintritt"),
                )
            }
        return LupaBbMemberPage(
            members = members,
            current = pagination?.long("current")?.toInt(),
            max = pagination?.long("max")?.toInt(),
        )
    }

    private fun root(body: String): JsonObject {
        val element =
            try {
                json.parseToJsonElement(body)
            } catch (_: Exception) {
                unexpected()
            }
        val obj = element as? JsonObject ?: unexpected()
        val status = obj["status"] as? JsonPrimitive ?: unexpected()
        if (status.content != "true") unexpected()
        return obj
    }

    private fun JsonObject.string(key: String): String? =
        when (val v: JsonElement? = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> v.content
            else -> unexpected()
        }

    private fun JsonObject.long(key: String): Long? =
        when (val v: JsonElement? = this[key]) {
            null, JsonNull -> null
            // a value that is not a whole number reads as "absent": the record is then rejected on its own (INVALID_PERSON_NUMBER,
            // UNKNOWN_STATUS) instead of failing the whole fetch
            is JsonPrimitive -> v.content.trim().toLongOrNull()
            else -> unexpected()
        }

    private fun unexpected(): Nothing = throw LupaBbFetchException(LupaBbErrorCode.UNEXPECTED_RESPONSE)
}
