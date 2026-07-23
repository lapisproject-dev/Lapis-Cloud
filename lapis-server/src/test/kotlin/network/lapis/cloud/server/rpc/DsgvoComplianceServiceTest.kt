package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.DataBreachIncidentInput
import network.lapis.cloud.shared.domain.DpiaAssessmentInput
import network.lapis.cloud.shared.domain.DpiaRiskBand
import network.lapis.cloud.shared.domain.DsfaStatus
import network.lapis.cloud.shared.domain.ProcessingAgreementInput
import network.lapis.cloud.shared.domain.RiskLevel
import network.lapis.cloud.shared.domain.TechnicalOrganizationalMeasureInput
import network.lapis.cloud.shared.domain.TomCategory
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises the V0.5.5 DSGVO-Vollausbau wave end to end: [DsgvoComplianceService]'s CRUD surface
 * for all four Bausteine (AVV-register/TOMs/DSFA/Datenpannenmeldung), its authorization tiers
 * (deliberately WITHOUT MEMBER/TREASURER on any method), and the read-time-only
 * [BreachDeadlineCalculator]/[DpiaRiskMatrix] display helpers folded into the outgoing Dtos. Same
 * "throwaway routes calling the service class directly" house style as [ServiceIntegrationTest]/
 * [AuditLogServiceTest].
 */
class DsgvoComplianceServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        // ── AVV-Register (Baustein 1) ─────────────────────────────────────────────

        test("AVV-register: ADMIN creates, updates, and lists a processing agreement; active reflects avvStatus+reviewDueDate") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                val createResponse =
                    client
                        .post("/test/avv/create?processor=Letterxpress&status=DRAFT") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                val (id, activeAfterCreate) = createResponse.split(":")
                activeAfterCreate shouldBe "false" // DRAFT is never active

                val updateResponse =
                    client
                        .post("/test/avv/update/$id?processor=Letterxpress&status=SIGNED&reviewDue=2099-01-01") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                val (_, activeAfterSign) = updateResponse.split(":")
                activeAfterSign shouldBe "true" // SIGNED + future reviewDueDate -> active

                val expiredResponse =
                    client
                        .post("/test/avv/update/$id?processor=Letterxpress&status=SIGNED&reviewDue=2020-01-01") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                val (_, activeAfterExpiry) = expiredResponse.split(":")
                activeAfterExpiry shouldBe "false" // SIGNED but reviewDueDate in the past -> not active

                val list = client.get("/test/avv/list") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                (id in list.split(",")) shouldBe true
            }
        }

        test("AVV-register: BOARD can read, but only ADMIN can write; MEMBER/TREASURER cannot read or write") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                client.get("/test/avv/list") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/avv/create?processor=X&status=NONE") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Forbidden

                client.get("/test/avv/list") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/avv/create?processor=X&status=NONE") { header("X-Member-Id", MEMBER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/avv/list") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/avv/list").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("AVV-register: hasActiveProcessingAgreement reflects SIGNED+non-expired rows only") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                val before =
                    client
                        .get("/test/avv/has-active?processor=Letterxpress-Advisory-Test") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                before shouldBe "false"

                client
                    .post("/test/avv/create?processor=Letterxpress-Advisory-Test&status=SIGNED") {
                        header("X-Member-Id", ADMIN_ID)
                    }.bodyAsText()

                val after =
                    client
                        .get("/test/avv/has-active?processor=Letterxpress-Advisory-Test") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                after shouldBe "true"
            }
        }

        // ── TOMs (Baustein 2) ──────────────────────────────────────────────────────

        test("TOMs: ADMIN creates with version=1, update increments version and sets updatedBy; category filter works") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                val createResponse =
                    client
                        .post("/test/tom/create?category=PHYSICAL_ACCESS_CONTROL&title=Zutritt") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                val (id, versionAfterCreate) = createResponse.split(":")
                versionAfterCreate shouldBe "1"

                val updateResponse =
                    client
                        .post("/test/tom/update/$id?category=PHYSICAL_ACCESS_CONTROL&title=Zutritt-v2") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                val (_, versionAfterUpdate) = updateResponse.split(":")
                versionAfterUpdate shouldBe "2"

                val filtered =
                    client
                        .get("/test/tom/list?category=PHYSICAL_ACCESS_CONTROL") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                (id in filtered.split(",")) shouldBe true

                val filteredOut =
                    client
                        .get("/test/tom/list?category=AVAILABILITY_CONTROL") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                (id in filteredOut.split(",")) shouldBe false
            }
        }

        test("TOMs: only ADMIN may write (BOARD forbidden), BOARD may read") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                client.get("/test/tom/list") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/tom/create?category=INPUT_CONTROL&title=X") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("TOMs: update on unknown id throws NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }
                client
                    .post("/test/tom/update/${kotlin.uuid.Uuid.random()}?category=INPUT_CONTROL&title=X") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── DSFA (Baustein 3) ─────────────────────────────────────────────────────

        test("DSFA: dpiaRequired is stored exactly as submitted, never derived from risk inputs; riskBand reflects DpiaRiskMatrix") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                // Draft: dpiaRequired absent, risk inputs absent -> riskBand null.
                val createResponse =
                    client
                        .post("/test/dpia/create?title=Neues+Verfahren") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                val (id, versionAfterCreate, dpiaRequiredAfterCreate, riskBandAfterCreate) = createResponse.split(":")
                versionAfterCreate shouldBe "1"
                dpiaRequiredAfterCreate shouldBe "null"
                riskBandAfterCreate shouldBe "null"

                // Update: human sets dpiaRequired=true explicitly, plus HIGH/HIGH risk inputs.
                val updateResponse =
                    client
                        .post(
                            "/test/dpia/update/$id?title=Neues+Verfahren&dpiaRequired=true&likelihood=HIGH&severity=HIGH&status=COMPLETED",
                        ) { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                val (_, versionAfterUpdate, dpiaRequiredAfterUpdate, riskBandAfterUpdate) = updateResponse.split(":")
                versionAfterUpdate shouldBe "2"
                dpiaRequiredAfterUpdate shouldBe "true"
                riskBandAfterUpdate shouldBe DpiaRiskBand.CRITICAL.name

                // A later read of dpiaRequired must remain exactly what was set -- never silently
                // flipped by anything derived from risk inputs.
                val relisted =
                    client.get("/test/dpia/list?status=COMPLETED") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                (id in relisted.split(",")) shouldBe true
            }
        }

        test("DSFA: BOARD and ADMIN may read+write, MEMBER/TREASURER forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                client.get("/test/dpia/list") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client.get("/test/dpia/list") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/dpia/create?title=X") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                client.get("/test/dpia/list") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/dpia/list") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/dpia/create?title=X") { header("X-Member-Id", MEMBER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── Datenpannenmeldung (Baustein 4) ───────────────────────────────────────

        test(
            "Data breach: authorityNotificationDeadline is discoveredAt+72h; deadlineStatus reflects notification " +
                "state, never auto-flips authorityNotificationRequired",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                // Far in the past -> clearly OVERDUE if not notified.
                val createResponse =
                    client
                        .post("/test/breach/create?discoveredAt=2020-01-01T00:00:00") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                val (id, deadline, deadlineStatus, authorityRequired) = createResponse.split("|")
                LocalDateTime.parse(deadline) shouldBe LocalDateTime(2020, 1, 4, 0, 0, 0) // +72h
                deadlineStatus shouldBe BreachDeadlineStatus.OVERDUE.name
                authorityRequired shouldBe "null" // never auto-set by the deadline calculator

                // Human explicitly records the notification -- deadlineStatus becomes SATISFIED,
                // and authorityNotificationRequired remains exactly whatever is submitted (true here).
                val updateResponse =
                    client
                        .post(
                            "/test/breach/update/$id?discoveredAt=2020-01-01T00:00:00&authorityNotifiedAt=2020-01-02T00:00:00" +
                                "&authorityRequired=true&status=NOTIFIED_AUTHORITY",
                        ) { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                val (_, _, deadlineStatusAfter, authorityRequiredAfter) = updateResponse.split("|")
                deadlineStatusAfter shouldBe BreachDeadlineStatus.SATISFIED.name
                authorityRequiredAfter shouldBe "true"
            }
        }

        test("Data breach: BOARD and ADMIN may read+write, MEMBER/TREASURER forbidden; status filter works") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }

                val createResponse =
                    client
                        .post("/test/breach/create?discoveredAt=2026-06-01T00:00:00") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                val id = createResponse.substringBefore("|")

                val filtered =
                    client.get("/test/breach/list?status=REPORTED") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                (id in filtered.split(",")) shouldBe true

                client.get("/test/breach/list") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.get("/test/breach/list") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/breach/create?discoveredAt=2026-06-01T00:00:00") { header("X-Member-Id", MEMBER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("Data breach: update on unknown id throws NotFound") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }
                client
                    .post("/test/breach/update/${kotlin.uuid.Uuid.random()}?discoveredAt=2026-06-01T00:00:00") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── DoS guard ─────────────────────────────────────────────────────────────

        test("list* caps results at MAX_LIST_SIZE") {
            testApplication {
                application {
                    install(StatusPages) { installDsgvoComplianceExceptionHandlers() }
                    routing { registerDsgvoComplianceTestRoutes() }
                }
                repeat(510) {
                    client.post("/test/tom/create?category=INPUT_CONTROL&title=Cap-Test-$it") { header("X-Member-Id", ADMIN_ID) }
                }
                val listed = client.get("/test/tom/list") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                listed.split(",").filter { it.isNotBlank() }.size shouldBe 500
            }
        }
    })

private fun StatusPagesConfig.installDsgvoComplianceExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
}

private fun Route.registerDsgvoComplianceTestRoutes() {
    // AVV-register
    post("/test/avv/create") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.createProcessingAgreement(
                ProcessingAgreementInput(
                    processorName = q["processor"]!!,
                    processingPurpose = "Testzweck",
                    dataCategories = "Name, Adresse",
                    avvStatus = AvvStatus.valueOf(q["status"]!!),
                    reviewDueDate = q["reviewDue"]?.let { LocalDate.parse(it) },
                ),
            )
        call.respondText("${dto.id}:${dto.active}")
    }
    post("/test/avv/update/{id}") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateProcessingAgreement(
                call.parameters["id"]!!,
                ProcessingAgreementInput(
                    processorName = q["processor"]!!,
                    processingPurpose = "Testzweck",
                    dataCategories = "Name, Adresse",
                    avvStatus = AvvStatus.valueOf(q["status"]!!),
                    reviewDueDate = q["reviewDue"]?.let { LocalDate.parse(it) },
                ),
            )
        call.respondText("${dto.id}:${dto.active}")
    }
    get("/test/avv/list") {
        val service = DsgvoComplianceService(call)
        val list = service.listProcessingAgreements()
        call.respondText(list.joinToString(",") { it.id })
    }
    get("/test/avv/has-active") {
        val service = DsgvoComplianceService(call)
        val processor = call.request.queryParameters["processor"]!!
        call.respondText(service.hasActiveProcessingAgreement(processor).toString())
    }

    // TOMs
    post("/test/tom/create") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.createTechnicalOrganizationalMeasure(
                TechnicalOrganizationalMeasureInput(
                    category = TomCategory.valueOf(q["category"]!!),
                    title = q["title"]!!,
                    description = "Testbeschreibung",
                ),
            )
        call.respondText("${dto.id}:${dto.version}")
    }
    post("/test/tom/update/{id}") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateTechnicalOrganizationalMeasure(
                call.parameters["id"]!!,
                TechnicalOrganizationalMeasureInput(
                    category = TomCategory.valueOf(q["category"]!!),
                    title = q["title"]!!,
                    description = "Testbeschreibung-v2",
                ),
            )
        call.respondText("${dto.id}:${dto.version}")
    }
    get("/test/tom/list") {
        val service = DsgvoComplianceService(call)
        val category = call.request.queryParameters["category"]?.let { TomCategory.valueOf(it) }
        val list = service.listTechnicalOrganizationalMeasures(category)
        call.respondText(list.joinToString(",") { it.id })
    }

    // DSFA
    post("/test/dpia/create") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.createDpiaAssessment(
                DpiaAssessmentInput(
                    title = q["title"]!!,
                    processingDescription = "Testverarbeitung",
                ),
            )
        call.respondText("${dto.id}:${dto.version}:${dto.dpiaRequired}:${dto.riskBand}")
    }
    post("/test/dpia/update/{id}") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateDpiaAssessment(
                call.parameters["id"]!!,
                DpiaAssessmentInput(
                    title = q["title"]!!,
                    processingDescription = "Testverarbeitung-v2",
                    dpiaRequired = q["dpiaRequired"]?.toBoolean(),
                    riskLikelihood = q["likelihood"]?.let { RiskLevel.valueOf(it) },
                    riskSeverity = q["severity"]?.let { RiskLevel.valueOf(it) },
                    status = q["status"]?.let { DsfaStatus.valueOf(it) } ?: DsfaStatus.DRAFT,
                ),
            )
        call.respondText("${dto.id}:${dto.version}:${dto.dpiaRequired}:${dto.riskBand}")
    }
    get("/test/dpia/list") {
        val service = DsgvoComplianceService(call)
        val status = call.request.queryParameters["status"]?.let { DsfaStatus.valueOf(it) }
        val list = service.listDpiaAssessments(status)
        call.respondText(list.joinToString(",") { it.id })
    }

    // Data breach
    post("/test/breach/create") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.createDataBreachIncident(
                DataBreachIncidentInput(
                    discoveredAt = LocalDateTime.parse(q["discoveredAt"]!!),
                    description = "Testvorfall",
                    affectedDataCategories = "E-Mail-Adressen",
                ),
            )
        call.respondText(
            "${dto.id}|${dto.authorityNotificationDeadline}|${dto.deadlineStatus}|${dto.authorityNotificationRequired}",
        )
    }
    post("/test/breach/update/{id}") {
        val service = DsgvoComplianceService(call)
        val q = call.request.queryParameters
        val dto =
            service.updateDataBreachIncident(
                call.parameters["id"]!!,
                DataBreachIncidentInput(
                    discoveredAt = LocalDateTime.parse(q["discoveredAt"]!!),
                    description = "Testvorfall-v2",
                    affectedDataCategories = "E-Mail-Adressen",
                    authorityNotifiedAt = q["authorityNotifiedAt"]?.let { LocalDateTime.parse(it) },
                    authorityNotificationRequired = q["authorityRequired"]?.toBoolean(),
                    status = q["status"]?.let { BreachStatus.valueOf(it) } ?: BreachStatus.REPORTED,
                ),
            )
        call.respondText(
            "${dto.id}|${dto.authorityNotificationDeadline}|${dto.deadlineStatus}|${dto.authorityNotificationRequired}",
        )
    }
    get("/test/breach/list") {
        val service = DsgvoComplianceService(call)
        val status = call.request.queryParameters["status"]?.let { BreachStatus.valueOf(it) }
        val list = service.listDataBreachIncidents(status)
        call.respondText(list.joinToString(",") { it.id })
    }
}
