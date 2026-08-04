package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.ReserveType

// Accounting UI wave -- German label/badge-color tables for enums shared by *more than one*
// Accounting screen, design decision D9. GemeinnuetzigkeitSphere is needed by both
// `LedgerScreen.kt` (the posting-line sphere picker) and the later Gemeinnützigkeit-compliance
// report screen; ReserveType by `LedgerScreen.kt`'s account form and the same report screen's
// Rücklagen table; DonorCategory by `LedgerScreen.kt`'s journal-entry donor block and the later
// Donors screen. Truly single-screen enums (`LedgerAccountType`, `PostingSide`,
// `JournalEntryStatus`) stay local to their own screen file instead -- see that file's own label
// tables, same posture `MotionsScreen.kt`/`CommitteesScreen.kt` already established for
// single-screen enums.
//
// Every function here is `typeBadge` grammar (fixed classification, not a lifecycle status) --
// see `StatusBadge.kt` KDoc for the fill-vs-no-fill design rule this wave inherits unchanged.

/**
 * The four strictly-separated Gemeinnützigkeit spheres (§§ 51-68 AO). `WIRTSCHAFTLICHER_
 * GESCHAEFTSBETRIEB` deliberately gets `warning`, not a neutral hue -- it is the sphere where a
 * Verein risks its tax-exempt status if mismanaged, so the "pay attention" hue is earned here, not
 * decorative.
 */
fun sphereLabel(sphere: GemeinnuetzigkeitSphere): String =
    when (sphere) {
        GemeinnuetzigkeitSphere.IDEELLER_BEREICH -> "Ideeller Bereich"
        GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG -> "Vermögensverwaltung"
        GemeinnuetzigkeitSphere.ZWECKBETRIEB -> "Zweckbetrieb"
        GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB -> "Wirtschaftlicher Geschäftsbetrieb"
    }

fun sphereColor(sphere: GemeinnuetzigkeitSphere): String =
    when (sphere) {
        GemeinnuetzigkeitSphere.IDEELLER_BEREICH -> "dark"
        GemeinnuetzigkeitSphere.VERMOEGENSVERWALTUNG -> "info"
        GemeinnuetzigkeitSphere.ZWECKBETRIEB -> "primary"
        GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB -> "warning"
    }

/**
 * The four §62 AO reserve categories. The label appends [ReserveType.paragraphRef] straight from
 * the enum's own constructor property -- never a UI-authored re-typing of the citation, so a later
 * backend correction to that citation updates the UI for free (the same "never re-derive, pull
 * from source of truth" principle this wave applies to money, applied here to legal text instead).
 */
fun reserveTypeLabel(type: ReserveType): String =
    when (type) {
        ReserveType.PROJEKTRUECKLAGE -> "Projektrücklage"
        ReserveType.FREIE_RUECKLAGE -> "Freie Rücklage"
        ReserveType.WIEDERBESCHAFFUNGSRUECKLAGE -> "Wiederbeschaffungsrücklage"
        ReserveType.BETRIEBSMITTELRUECKLAGE -> "Betriebsmittelrücklage"
    } + " (${type.paragraphRef})"

fun reserveTypeColor(type: ReserveType): String =
    when (type) {
        ReserveType.PROJEKTRUECKLAGE -> "info"
        // Statutory cap deliberately NOT enforced by this codebase (see ReserveType KDoc) --
        // "warning" here is deliberately the same hue as its own inline caveat wherever this
        // reserve type is discussed, not decorative.
        ReserveType.FREIE_RUECKLAGE -> "warning"
        ReserveType.WIEDERBESCHAFFUNGSRUECKLAGE -> "secondary"
        ReserveType.BETRIEBSMITTELRUECKLAGE -> "secondary"
    }

/**
 * §25 PartG donor categories. The four `danger`-colored categories are exactly the ones the
 * backend structurally always rejects for a political party at post time (`ConflictException`,
 * see `AccountingService.requirePartyDonationAllowed`) -- coloring them danger in the picker itself
 * is a pre-flight signal to a treasurer, not decoration: they see why *before* a failed submission
 * attempt, not only after.
 */
fun donorCategoryLabel(category: DonorCategory): String =
    when (category) {
        DonorCategory.GERMAN_NATURAL_PERSON -> "Deutsche natürliche Person"
        DonorCategory.EU_NATURAL_PERSON -> "EU-Bürger:in / EU-Rechtsperson"
        DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON -> "Natürliche Person außerhalb der EU"
        DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION -> "Deutsches Unternehmen/Organisation"
        DonorCategory.PUBLIC_LAW_CORPORATION -> "Körperschaft des öffentlichen Rechts"
        DonorCategory.OVER_25_PERCENT_STATE_OWNED_COMPANY -> "Unternehmen mit über 25 % staatlicher Beteiligung"
        DonorCategory.OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY -> "Andere Partei/Fraktion"
        DonorCategory.PROFESSIONAL_OR_TRADE_ASSOCIATION -> "Berufs- oder Wirtschaftsverband"
        DonorCategory.ANONYMOUS -> "Anonym"
    }

fun donorCategoryColor(category: DonorCategory): String =
    when (category) {
        DonorCategory.GERMAN_NATURAL_PERSON -> "secondary"
        DonorCategory.EU_NATURAL_PERSON -> "secondary"
        DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON -> "info"
        DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION -> "secondary"
        DonorCategory.PUBLIC_LAW_CORPORATION -> "danger"
        DonorCategory.OVER_25_PERCENT_STATE_OWNED_COMPANY -> "danger"
        DonorCategory.OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY -> "danger"
        DonorCategory.PROFESSIONAL_OR_TRADE_ASSOCIATION -> "danger"
        DonorCategory.ANONYMOUS -> "dark"
    }
