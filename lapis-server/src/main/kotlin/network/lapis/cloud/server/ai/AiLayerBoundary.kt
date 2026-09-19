package network.lapis.cloud.server.ai

/**
 * Welle V1.6.1 "KI-Fundament + Pilot Satzungs-Q&A" -- optional AI assistance layer, **default OFF**.
 *
 * **Deliberate deviation from the concept's `:ai-assistant`/`:ai-providers` Gradle modules.** A real
 * compile-time module cut would first require extracting `DocumentTable`, `RequestContext`,
 * `DbClock` etc. out of `lapis-server` into a `lapis-server-core` -- a refactor that would collide
 * with the RPC/KSP/Flyway/DSGVO wiring living in this module and would dwarf this wave. Instead the
 * layer is one package with sub-packages, and the boundary is enforced by a source-scan test
 * (`AiModuleBoundaryTest`, same pattern as `AuditLogImmutabilityTest`/`PersonalDataCoverageTest`):
 *
 * - **R1** -- `qa/`, `llm/` and `safety/` import neither Exposed, nor `db.generated`, nor `server.rpc`:
 *   the model-facing path sees only the interfaces `KnowledgeRetriever`, `LlmClient`, `AiCallAuditSink`.
 * - **R2** -- nothing below `ai/` imports governance/market/visibility/payment/accounting names.
 * - **R3** -- nothing below `ai/` writes to a table other than the five `Ai*Table` objects.
 * - **R4** -- the tool whitelist has exactly one entry (knowledge search), and the pipeline has no
 *   tool-dispatch loop at all: retrieval is called by our code, the model is handed no tool definition.
 *
 * Extracting a real Gradle module is a follow-up wave (see `docs/architecture/ai-assistant.adoc`).
 */
internal object AiLayerBoundary
