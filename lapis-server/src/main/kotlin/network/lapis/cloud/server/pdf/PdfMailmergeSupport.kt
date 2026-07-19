package network.lapis.cloud.server.pdf

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** German date format, e.g. `"19.07.2026"`. Shared by [LetterPdfBuilder] and every PDF generator. */
internal fun formatGermanDate(date: LocalDate): String = "%02d.%02d.%04d".format(date.day, date.month.number, date.year)

/** German currency format, e.g. `"1.234,56 €"` (thousands separator `.`, decimal comma `,`). */
internal fun formatEuro(amount: BigDecimal): String = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(amount)

/**
 * [displayName] followed by street / "postalCode city" / country, each only if present.
 * Beitragsrechnung/Spendenbescheinigung callers validate a complete address before generating
 * (see `network.lapis.cloud.server.routes.registerMailmergeRoutes` KDoc) -- this helper itself
 * stays defensive (skips whichever component is `null`) so it is equally safe to reuse for
 * Einladung recipients, which are deliberately NOT hard-failed on a missing address.
 */
internal fun MemberDto.addressLines(): List<String> =
    buildList {
        add(displayName)
        street?.let { add(it) }
        val cityLine = listOfNotNull(postalCode, city).joinToString(" ")
        if (cityLine.isNotBlank()) add(cityLine)
        country?.let { add(it) }
    }

/** Street / "postalCode city" / country, each only if present -- [name] is rendered separately by [LetterPdfBuilder.letterhead]. */
internal fun OrganizationSettingsDto.addressLines(): List<String> =
    buildList {
        street?.let { add(it) }
        val cityLine = listOfNotNull(postalCode, city).joinToString(" ")
        if (cityLine.isNotBlank()) add(cityLine)
        country?.let { add(it) }
    }
