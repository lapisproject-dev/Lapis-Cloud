package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.MemberAddressDataDto
import network.lapis.cloud.shared.domain.MemberAddressField
import network.lapis.cloud.shared.domain.MemberAddressRules
import network.lapis.cloud.shared.domain.MemberAddressViolation
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IMemberService
import network.lapis.cloud.shared.rpc.NotFoundException

/**
 * The three writes [MemberAddressCard] needs -- shared by the self-service card and the board dialog (V1.9.35). Exceptions propagate.
 * V1.9.121: street/postal code/city, the country (a code from the list) and the nationalities (codes) are separate calls; the card
 * only calls the ones whose value changed.
 */
internal interface MemberAddressWriteRpc {
    suspend fun updateStreetAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
    ): MemberDto

    suspend fun updateCountry(
        memberId: String,
        countryCode: String?,
    ): MemberDto

    suspend fun updateNationalities(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationalityCodes: List<String>,
    ): MemberDto
}

/** The RPC surface of the self-service card -- an interface so DOM tests can drive the card without a server. Exceptions propagate. */
internal interface MemberAddressRpc : MemberAddressWriteRpc {
    suspend fun getCurrentMember(): MemberDto
}

/**
 * What [MemberAddressCard] renders -- built from a [MemberDto] (self-service) or a `MemberAddressDataDto` (board dialog).
 * V1.9.121: the country and nationalities are CODES; the legacy free texts ([countryLegacy], [nationalityLegacy]) exist only while no code
 * does and are shown as a hint, never as a value of the field.
 */
internal data class MemberAddressFormData(
    val memberId: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val countryCode: String?,
    val countryLegacy: String?,
    val dateOfBirth: LocalDate?,
    val nationalityCodes: List<String>,
    val nationalityLegacy: String?,
    val dateOfDeath: LocalDate?,
)

internal fun MemberDto.toFormData() =
    MemberAddressFormData(
        memberId = id,
        street = street,
        postalCode = postalCode,
        city = city,
        countryCode = countryCode,
        countryLegacy = countryLegacy,
        dateOfBirth = dateOfBirth,
        nationalityCodes = nationalityCodes,
        nationalityLegacy = nationalityLegacy,
        dateOfDeath = dateOfDeath,
    )

internal fun MemberAddressDataDto.toFormData() =
    MemberAddressFormData(
        memberId = memberId,
        street = street,
        postalCode = postalCode,
        city = city,
        countryCode = countryCode,
        countryLegacy = countryLegacy,
        dateOfBirth = dateOfBirth,
        nationalityCodes = nationalityCodes,
        nationalityLegacy = nationalityLegacy,
        dateOfDeath = dateOfDeath,
    )

/** Who the card is for: the member themselves (default) or a board/admin editing someone else's data. */
internal enum class MemberAddressCardVariant { SELF, ADMINISTRATION }

internal fun liveMemberAddressRpc(): MemberAddressRpc =
    object : MemberAddressRpc {
        override suspend fun getCurrentMember() = rpcService<IMemberService>().getCurrentMember()

        override suspend fun updateStreetAddress(
            memberId: String,
            street: String?,
            postalCode: String?,
            city: String?,
        ) = rpcService<IMemberService>().updateMemberStreetAddress(memberId, street, postalCode, city)

        override suspend fun updateCountry(
            memberId: String,
            countryCode: String?,
        ) = rpcService<IMemberService>().updateMemberCountry(memberId, countryCode)

        override suspend fun updateNationalities(
            memberId: String,
            dateOfBirth: LocalDate?,
            nationalityCodes: List<String>,
        ) = rpcService<IMemberService>().updateMemberNationalities(memberId, dateOfBirth, nationalityCodes)
    }

/**
 * The client's own "today". The server judges with its own clock and time zone: a difference can at worst
 * produce a Conflict, which re-reads the view -- never a wrong write.
 */
internal fun clientToday(): LocalDate = organizationToday()

/**
 * Runs [block]. Kilua RPC transmits only the TYPE of an exception, never its text, so a [ConflictException] is ambiguous (several
 * different server refusals share it), and a [NotFoundException] means the state changed under us: both get [conflictMessage], one fixed sentence that claims nothing specific. Everything else
 * goes through [guarded]'s standard handling (session expiry, toast). Returns `true` iff [block] completed.
 */
internal suspend fun runOrConflict(
    conflictMessage: String,
    toast: (String) -> Unit,
    block: suspend () -> Unit,
): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: ConflictException) {
        toast(conflictMessage)
        false
    } catch (e: NotFoundException) {
        toast(conflictMessage)
        false
    } catch (e: Throwable) {
        guarded<Unit> { throw e }
        false
    }

private fun textRule(field: MemberAddressField): (String) -> FieldCheck =
    { raw ->
        when (MemberAddressRules.textViolation(field, MemberAddressRules.normalize(raw))) {
            MemberAddressViolation.TOO_LONG ->
                FieldCheck.Invalid(gettext("Höchstens %1 Zeichen.", MemberAddressRules.maxLength(field).toString()))
            MemberAddressViolation.CONTROL_CHARACTER ->
                FieldCheck.Invalid(gettext("Zeilenumbrüche und Steuerzeichen sind nicht erlaubt."))
            else -> FieldCheck.Ok
        }
    }

/**
 * Welle V1.9.33 -- the member's own address and GwG data (date of birth, nationality) on "Meine Daten": two independent
 * forms, each with its own save button, enabled only while something changed AND every field is valid.
 *
 * The memberId always comes from the loaded [MemberDto.id] (`getCurrentMember`), never from a URL or parameter. After every
 * save -- successful or not -- the host section is reloaded, so the card is rebuilt from the server state, never patched by hand.
 * Field values never reach a toast, a log, storage or the URL; server text is never shown.
 */
internal class MemberAddressCard(
    private val parent: SimplePanel,
    private val rpc: MemberAddressWriteRpc,
    private val today: () -> LocalDate,
    private val onChanged: () -> Unit,
    private val toastError: (String) -> Unit = { notifyError(it) },
    private val toastSuccess: (String) -> Unit = { notifySuccess(it) },
    /** V1.9.35: when set it replaces [onChanged] after a save -- gets the written [MemberDto] on success, `null` on a refused write. */
    private val onSaved: ((MemberDto?) -> Unit)? = null,
    private val variant: MemberAddressCardVariant = MemberAddressCardVariant.SELF,
    /** V1.9.35: the board dialog asks first; [proceed] performs the save. The self-service card saves at once. */
    private val confirmSave: (proceed: () -> Unit) -> Unit = { it() },
) {
    internal lateinit var addressButton: Button
        private set
    internal lateinit var beneficialOwnerButton: Button
        private set

    fun render(dto: MemberDto) = render(dto.toFormData())

    fun render(dto: MemberAddressFormData) {
        val root = parent.vPanel(spacing = 8) { if (variant == MemberAddressCardVariant.SELF) addCssClasses("border rounded p-3") }
        if (variant == MemberAddressCardVariant.SELF) {
            root.h2(tr("Anschrift und Angaben nach Geldwäschegesetz")) { addCssClass("h5") }
            root.div(
                tr(
                    "Ihre Anschrift verwendet die Organisation für Briefpost und Rechnungen. Geburtsdatum und Staatsangehörigkeit werden bei Bedarf für Pflichtangaben nach dem Geldwäschegesetz benötigt. Ein leeres Feld löscht den gespeicherten Wert. Jede Änderung wird protokolliert, die Werte selbst stehen nicht im Protokoll.",
                ),
            ) { addCssClasses("text-muted small") }
        } else {
            root.div(
                tr(
                    "Ein leeres Feld löscht den gespeicherten Wert. Jede Änderung wird protokolliert, die Werte selbst stehen nicht im Protokoll.",
                ),
            ) { addCssClasses("text-muted small") }
        }

        buildAddressForm(root, dto)
        buildBeneficialOwnerForm(root, dto)
    }

    private fun buildAddressForm(
        root: SimplePanel,
        dto: MemberAddressFormData,
    ) {
        val form = root.lapisForm()
        val street =
            form.textField(
                label = tr("Straße und Hausnummer"),
                value = dto.street.orEmpty(),
                autocomplete = Autocomplete.STREET_ADDRESS,
                rule = textRule(MemberAddressField.STREET),
            )
        val postalCode =
            form.textField(
                label = tr("Postleitzahl"),
                value = dto.postalCode.orEmpty(),
                autocomplete = Autocomplete.POSTAL_CODE,
                rule = textRule(MemberAddressField.POSTAL_CODE),
            )
        val city =
            form.textField(
                label = tr("Ort"),
                value = dto.city.orEmpty(),
                autocomplete = Autocomplete.ADDRESS_LEVEL2,
                rule = textRule(MemberAddressField.CITY),
            )
        val country =
            form.searchableSelectField(
                label = tr("Land"),
                value = dto.countryCode.orEmpty(),
                init = { it.configureAsCountryList(CountryListMode.COUNTRY) },
            )
        legacyHint(form, dto.countryLegacy.takeIf { dto.countryCode == null })
        val fields = listOf(street, postalCode, city)
        val initial = listOf(dto.street, dto.postalCode, dto.city).map { MemberAddressRules.normalize(it) }
        val initialCountry = dto.countryCode.orEmpty()

        fun textChanged() = fields.map { MemberAddressRules.normalize(it.value) } != initial

        fun countryChanged() = country.value != initialCountry

        fun dirty() = textChanged() || countryChanged()

        fun valid() = fields.all { it.isValid() }

        fun canSave() = dirty() && valid()
        val button = newActionButton(ActionIcon.SAVE, tr("Anschrift speichern"), ButtonStyle.PRIMARY)
        button.disabled = true
        addressButton = button
        form.buttons(primary = button)
        (fields + country).forEach { f -> f.subscribe { button.disabled = !canSave() } }
        button.onClick {
            if (!canSave()) return@onClick
            val values = fields.map { MemberAddressRules.normalize(it.value) }
            val changeText = textChanged()
            val changeCountry = countryChanged()
            val newCountry = country.value.ifEmpty { null }
            confirmSave {
                form.runBusy(button, restoreDisabled = { !canSave() }) {
                    save {
                        var written: MemberDto? = null
                        if (changeText) written = rpc.updateStreetAddress(dto.memberId, values[0], values[1], values[2])
                        if (changeCountry) written = rpc.updateCountry(dto.memberId, newCountry)
                        checkNotNull(written)
                    }
                }
            }
        }
    }

    /**
     * The old free text of a member that has no code yet, shown as plain text under the list ("Altwert: ..."): the member is asked to
     * choose from the list; the text is NEVER the value of the field. Untrusted text, rendered only through [untrustedDiv].
     */
    private fun legacyHint(
        form: LapisForm,
        legacy: String?,
    ) {
        val text = MemberAddressRules.normalize(legacy) ?: return
        form.panel.untrustedDiv(
            gettext("Altwert: „%1“ – bitte aus der Liste auswählen.", sanitizeUntrustedI18nText(text)),
            className = "form-text lapis-legacy-hint",
        )
    }

    private fun buildBeneficialOwnerForm(
        root: SimplePanel,
        dto: MemberAddressFormData,
    ) {
        val initialDate = dto.dateOfBirth?.let { machineDate(it) }
        val form = root.lapisForm()
        val dateOfBirth =
            form.textField(
                label = tr("Geburtsdatum"),
                type = InputType.DATE,
                value = initialDate.orEmpty(),
                autocomplete = Autocomplete.BDAY,
                rule = { raw -> birthDateCheck(raw.trim(), dto.dateOfDeath) },
            )
        val nationality = form.panel.nationalityMultiSelect(label = tr("Staatsangehörigkeit"), initial = dto.nationalityCodes)
        legacyHint(form, dto.nationalityLegacy.takeIf { dto.nationalityCodes.isEmpty() })
        val initialCodes = dto.nationalityCodes.sorted()

        fun dateValue(): String? = dateOfBirth.value.trim().ifEmpty { null }

        fun dirty() = dateValue() != initialDate || nationality.codes != initialCodes

        fun valid() = dateOfBirth.isValid()

        fun canSave() = dirty() && valid()
        val button = newActionButton(ActionIcon.SAVE, tr("Angaben speichern"), ButtonStyle.PRIMARY)
        button.disabled = true
        beneficialOwnerButton = button
        form.buttons(primary = button)
        dateOfBirth.subscribe { button.disabled = !canSave() }
        nationality.subscribe { button.disabled = !canSave() }
        button.onClick {
            if (!canSave()) return@onClick
            val date = dateValue()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val codes = nationality.codes
            confirmSave {
                form.runBusy(button, restoreDisabled = { !canSave() }) {
                    save { rpc.updateNationalities(dto.memberId, date, codes) }
                }
            }
        }
    }

    private fun birthDateCheck(
        raw: String,
        dateOfDeath: LocalDate?,
    ): FieldCheck {
        val date =
            runCatching { LocalDate.parse(raw) }.getOrNull() ?: return FieldCheck.Invalid(gettext("Bitte ein gültiges Datum angeben."))
        return when (MemberAddressRules.birthDateViolation(date, dateOfDeath, today())) {
            MemberAddressViolation.BIRTH_IN_FUTURE -> FieldCheck.Invalid(gettext("Das Geburtsdatum darf nicht in der Zukunft liegen."))
            MemberAddressViolation.BIRTH_BEFORE_1900 -> FieldCheck.Invalid(gettext("Bitte ein Datum ab dem 01.01.1900 angeben."))
            MemberAddressViolation.BIRTH_AFTER_DEATH ->
                FieldCheck.Invalid(gettext("Das Geburtsdatum darf nicht nach dem Sterbedatum liegen."))
            else -> FieldCheck.Ok
        }
    }

    /** One write, then ALWAYS a reload: the card shows the server state afterwards, whatever happened. */
    private suspend fun save(write: suspend () -> MemberDto) {
        var written: MemberDto? = null
        val ok =
            runOrConflict(
                conflictMessage =
                    gettext(
                        "Die Angaben wurden nicht gespeichert. Bitte prüfen Sie die Eingaben; die Ansicht wurde neu geladen.",
                    ),
                toast = toastError,
                block = { written = write() },
            )
        if (ok) toastSuccess(gettext("Gespeichert."))
        val saved = onSaved
        if (saved != null) saved(if (ok) written else null) else onChanged()
    }
}

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- what the board dialog shows for an ADMIN's address and beneficial-owner data: the Vorstand does not see
 * them (the server answers with a marked, value-free DTO, no error). The six fields stand there with the word "Geschützt" and the shield
 * icon instead of a value or dots, the saves are disabled, and the reason is visible text. Nothing is loaded, nothing is written.
 */
internal fun renderProtectedAddressData(parent: SimplePanel) {
    val root = parent.vPanel(spacing = 8)
    listOf(
        tr("Straße und Hausnummer"),
        tr("Postleitzahl"),
        tr("Ort"),
        tr("Land"),
        tr("Geburtsdatum"),
        tr("Staatsangehörigkeit"),
    ).forEach { label ->
        val line = root.div { addCssClasses("d-flex align-items-center gap-2") }
        line.span(label) { addCssClass("fw-bold") }
        line.icon("${ActionIcon.PROTECTED.css} fa-fw lapis-action-icon") { setAttribute("aria-hidden", "true") }
        line.span(tr("Geschützt")) { addCssClass("text-muted") }
    }
    val buttons = root.vPanel(spacing = 4)
    val addressButton = newActionButton(ActionIcon.SAVE, tr("Anschrift speichern"), ButtonStyle.PRIMARY)
    addressButton.disabled = true
    buttons.add(addressButton)
    val beneficialOwnerButton = newActionButton(ActionIcon.SAVE, tr("Angaben speichern"), ButtonStyle.PRIMARY)
    beneficialOwnerButton.disabled = true
    buttons.add(beneficialOwnerButton)
    root.peerProtectionNotice(
        tr(
            "Die Daten von Administratoren sind für den Vorstand nicht einsehbar. Administratoren und die betroffene Person selbst sehen sie.",
        ),
    )
}

/**
 * Mounts the card on "Meine Daten": the first load goes through a [dataSection] (one loading, one error state), the card is
 * rebuilt on every reload. Not shown to a guest session (a guest has no member record of their own).
 */
internal fun renderMemberAddressSection(
    container: SimplePanel,
    rpc: MemberAddressRpc = liveMemberAddressRpc(),
    today: () -> LocalDate = ::clientToday,
    toastError: (String) -> Unit = { notifyError(it) },
    toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    val session = AppState.session ?: return
    if (session.isGuest) return
    lateinit var section: DataSection
    section =
        container.dataSection<MemberDto>(
            isEmpty = { false },
            load = { guarded { rpc.getCurrentMember() } },
            render = {
                panel,
                dto,
                ->
                MemberAddressCard(
                    panel,
                    rpc,
                    today,
                    onChanged = { section.reload() },
                    toastError = toastError,
                    toastSuccess = toastSuccess,
                ).render(dto)
            },
        )
    section.reload()
}
