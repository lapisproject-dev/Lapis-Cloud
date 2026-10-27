package network.lapis.cloud.shared.domain

/**
 * V1.9.225 -- the reporting unit of a report sheet: the federal level, or one regional chapter (Landesverband) as a state unit or as the
 * summary of its subordinate regional units. The stored `unit_key` is [key] (`FEDERAL`, `STATE:<uuid>`, `SUB:<uuid>`; at most 48 characters).
 */
sealed interface PartgUnit {
    val level: PartgUnitLevel
    val chapterId: String?

    object Federal : PartgUnit {
        override val level = PartgUnitLevel.FEDERAL
        override val chapterId: String? = null
    }

    data class State(
        override val chapterId: String,
    ) : PartgUnit {
        override val level = PartgUnitLevel.STATE
    }

    data class Subordinate(
        override val chapterId: String,
    ) : PartgUnit {
        override val level = PartgUnitLevel.SUBORDINATE
    }

    fun key(): String =
        when (this) {
            is Federal -> FEDERAL_KEY
            is State -> "STATE:$chapterId"
            is Subordinate -> "SUB:$chapterId"
        }

    companion object {
        const val FEDERAL_KEY = "FEDERAL"
        const val MAX_LENGTH = 48
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Strict: exact prefix plus a lower-case UUID, at most [MAX_LENGTH] characters; anything else is `null`. */
        fun parse(raw: String): PartgUnit? {
            if (raw.length > MAX_LENGTH) return null
            if (raw == FEDERAL_KEY) return Federal
            val id: String
            val unit: PartgUnit
            when {
                raw.startsWith("STATE:") -> {
                    id = raw.removePrefix("STATE:")
                    unit = State(id)
                }
                raw.startsWith("SUB:") -> {
                    id = raw.removePrefix("SUB:")
                    unit = Subordinate(id)
                }
                else -> return null
            }
            return if (UUID_PATTERN.matches(id)) unit else null
        }
    }
}
