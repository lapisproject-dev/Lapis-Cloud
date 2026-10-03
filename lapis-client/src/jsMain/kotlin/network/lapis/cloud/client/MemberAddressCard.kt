package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.h2
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

/** The two writes [MemberAddressCard] needs -- shared by the self-service card and the board dialog (V1.9.35). Exceptions propagate. */
internal interface MemberAddressWriteRpc {
    suspend fun updateAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): MemberDto

    suspend fun updateBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto
}

/** The RPC surface of the self-service card -- an interface so DOM tests can drive the card without a server. Exceptions propagate. */
internal interface MemberAddressRpc : MemberAddressWriteRpc {
    suspend fun getCurrentMember(): MemberDto
}

/** What [MemberAddressCard] renders -- built from a [MemberDto] (self-service) or a `MemberAddressDataDto` (board dialog). */
internal data class MemberAddressFormData(
    val memberId: String,
    val street: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val dateOfBirth: LocalDate?,
    val nationality: String?,
    val dateOfDeath: LocalDate?,
)

internal fun MemberDto.toFormData() = MemberAddressFormData(id, street, postalCode, city, country, dateOfBirth, nationality, dateOfDeath)

internal fun MemberAddressDataDto.toFormData() =
    MemberAddressFormData(memberId, street, postalCode, city, country, dateOfBirth, nationality, dateOfDeath)

/** Who the card is for: the member themselves (default) or a board/admin editing someone else's data. */
internal enum class MemberAddressCardVariant { SELF, ADMINISTRATION }

internal fun liveMemberAddressRpc(): MemberAddressRpc =
    object : MemberAddressRpc {
        override suspend fun getCurrentMember() = rpcService<IMemberService>().getCurrentMember()

        override suspend fun updateAddress(
            memberId: String,
            street: String?,
            postalCode: String?,
            city: String?,
            country: String?,
        ) = rpcService<IMemberService>().updateMemberAddress(memberId, street, postalCode, city, country)

        override suspend fun updateBeneficialOwnerData(
            memberId: String,
            dateOfBirth: LocalDate?,
            nationality: String?,
        ) = rpcService<IMemberService>().updateMemberBeneficialOwnerData(memberId, dateOfBirth, nationality)
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
            form.textField(
                label = tr("Land"),
                value = dto.country.orEmpty(),
                autocomplete = Autocomplete.COUNTRY_NAME,
                rule = textRule(MemberAddressField.COUNTRY),
            )
        val fields = listOf(street, postalCode, city, country)
        val initial = listOf(dto.street, dto.postalCode, dto.city, dto.country).map { MemberAddressRules.normalize(it) }

        fun dirty() = fields.map { MemberAddressRules.normalize(it.value) } != initial

        fun valid() = fields.all { it.isValid() }

        fun canSave() = dirty() && valid()
        val button = newActionButton(ActionIcon.SAVE, tr("Anschrift speichern"), ButtonStyle.PRIMARY)
        button.disabled = true
        addressButton = button
        form.buttons(primary = button)
        fields.forEach { f -> f.subscribe { button.disabled = !canSave() } }
        button.onClick {
            if (!canSave()) return@onClick
            val values = fields.map { MemberAddressRules.normalize(it.value) }
            confirmSave {
                form.runBusy(button, restoreDisabled = { !canSave() }) {
                    save {
                        rpc.updateAddress(dto.memberId, values[0], values[1], values[2], values[3])
                    }
                }
            }
        }
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
        val nationality =
            form.textField(
                label = tr("Staatsangehörigkeit"),
                value = dto.nationality.orEmpty(),
                rule = textRule(MemberAddressField.NATIONALITY),
            )
        val initialNationality = MemberAddressRules.normalize(dto.nationality)

        fun dateValue(): String? = dateOfBirth.value.trim().ifEmpty { null }

        fun dirty() = dateValue() != initialDate || MemberAddressRules.normalize(nationality.value) != initialNationality

        fun valid() = dateOfBirth.isValid() && nationality.isValid()

        fun canSave() = dirty() && valid()
        val button = newActionButton(ActionIcon.SAVE, tr("Angaben speichern"), ButtonStyle.PRIMARY)
        button.disabled = true
        beneficialOwnerButton = button
        form.buttons(primary = button)
        listOf(dateOfBirth, nationality).forEach { f -> f.subscribe { button.disabled = !canSave() } }
        button.onClick {
            if (!canSave()) return@onClick
            val date = dateValue()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val nat = MemberAddressRules.normalize(nationality.value)
            confirmSave {
                form.runBusy(button, restoreDisabled = { !canSave() }) {
                    save { rpc.updateBeneficialOwnerData(dto.memberId, date, nat) }
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
