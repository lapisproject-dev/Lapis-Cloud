package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- die reine, DOM-freie Logik aus
 * `ConferenceBackgroundEffects.kt`: Whitelist/Parsing, Asset-Pfade, Weichzeichner-Staerken, Unterstuetzungs-
 * Gate, Zustandsautomat samt Jobs-K6-Regel (Fehlschlag laesst die Absicht unberuehrt, hoechstens ein
 * automatischer Versuch pro Sitzung). Kamera/WASM/WebGL sind unter Karma nicht testbar -- dafuer gibt es den
 * manuellen Pruefplan in `docs/architecture/video-background-effects.adoc`.
 */
class ConferenceBackgroundEffectsTest {
    private val imageEffects =
        listOf(
            ConferenceBackgroundEffect.BG_WARM_GREY,
            ConferenceBackgroundEffect.BG_COOL_BLUE,
            ConferenceBackgroundEffect.BG_SAGE,
            ConferenceBackgroundEffect.BG_SANDSTONE,
            ConferenceBackgroundEffect.BG_MIDNIGHT,
            ConferenceBackgroundEffect.BG_STUDIO,
        )

    // --- Whitelist / Parsing ------------------------------------------------------------------

    @Test
    fun parse_nullBlankAndOff_areNull() {
        assertNull(parseStoredBackgroundEffect(null))
        assertNull(parseStoredBackgroundEffect(""))
        assertNull(parseStoredBackgroundEffect("   "))
        assertNull(parseStoredBackgroundEffect("off"))
    }

    @Test
    fun parse_wrongCaseAndHostileValues_areNull() {
        assertNull(parseStoredBackgroundEffect("BLUR-LIGHT"))
        assertNull(parseStoredBackgroundEffect("../../etc/passwd"))
        assertNull(parseStoredBackgroundEffect("https://evil.example/x.png"))
        assertNull(parseStoredBackgroundEffect("bg-does-not-exist"))
        assertNull(parseStoredBackgroundEffect(" bg-sage"))
    }

    @Test
    fun parse_knownId_roundTrips() {
        assertEquals(ConferenceBackgroundEffect.BG_WARM_GREY, parseStoredBackgroundEffect("bg-warm-grey"))
    }

    @Test
    fun parse_everyEnumId_roundTrips_exceptOff() {
        ConferenceBackgroundEffect.entries.forEach { effect ->
            val expected = if (effect == ConferenceBackgroundEffect.OFF) null else effect
            assertEquals(expected, parseStoredBackgroundEffect(effect.id), effect.id)
        }
    }

    @Test
    fun whitelist_hasExactlyNineDistinctIds() {
        assertEquals(9, ConferenceBackgroundEffect.entries.size)
        val ids = ConferenceBackgroundEffect.entries.map { it.id }
        assertEquals(9, ids.toSet().size)
    }

    @Test
    fun persistValue_off_isNull_neverBlank() {
        assertNull(conferenceBackgroundPersistValue(ConferenceBackgroundEffect.OFF))
        val nonOff = ConferenceBackgroundEffect.entries.filter { it != ConferenceBackgroundEffect.OFF }
        nonOff.forEach { effect ->
            val value = conferenceBackgroundPersistValue(effect)
            assertEquals(effect.id, value)
            assertTrue(value!!.isNotBlank())
        }
    }

    // --- Asset-Pfade ---------------------------------------------------------------------------

    @Test
    fun imagePath_forBackgrounds_isSameOriginWebp() {
        imageEffects.forEach { effect ->
            assertEquals("/assets/video-backgrounds/${effect.id}.webp", conferenceBackgroundImagePath(effect))
        }
    }

    @Test
    fun imagePath_forOffAndBlur_isNull() {
        assertNull(conferenceBackgroundImagePath(ConferenceBackgroundEffect.OFF))
        assertNull(conferenceBackgroundImagePath(ConferenceBackgroundEffect.BLUR_LIGHT))
        assertNull(conferenceBackgroundImagePath(ConferenceBackgroundEffect.BLUR_STRONG))
    }

    @Test
    fun producedPaths_neverEscapeTheSameOrigin() {
        val paths =
            ConferenceBackgroundEffect.entries.mapNotNull { conferenceBackgroundImagePath(it) } +
                listOf(
                    ConferenceBackgroundAssets.TASKS_VISION_FILE_SET,
                    ConferenceBackgroundAssets.MODEL_ASSET_PATH,
                    ConferenceBackgroundAssets.BACKGROUND_IMAGE_DIR,
                )
        paths.forEach { path ->
            assertTrue(path.startsWith("/assets/"), path)
            assertFalse(path.contains("//"), path)
            assertFalse(path.contains(".."), path)
            assertFalse(path.contains(":"), path)
            assertFalse(path.startsWith("http"), path)
        }
    }

    @Test
    fun tasksVisionFileSet_carriesTheVersionAndEndsInWasm() {
        assertTrue(ConferenceBackgroundAssets.TASKS_VISION_FILE_SET.endsWith("/wasm"))
        assertTrue(ConferenceBackgroundAssets.TASKS_VISION_FILE_SET.contains(ConferenceBackgroundAssets.MEDIAPIPE_TASKS_VISION_VERSION))
    }

    // --- Weichzeichnen -------------------------------------------------------------------------

    @Test
    fun blurRadius_lightAndStrong_onlyForBlurEffects() {
        assertEquals(8, conferenceBackgroundBlurRadius(ConferenceBackgroundEffect.BLUR_LIGHT))
        assertEquals(24, conferenceBackgroundBlurRadius(ConferenceBackgroundEffect.BLUR_STRONG))
        assertNull(conferenceBackgroundBlurRadius(ConferenceBackgroundEffect.OFF))
        imageEffects.forEach { assertNull(conferenceBackgroundBlurRadius(it)) }
    }

    @Test
    fun blurRadius_effectiveDistanceIsReal_andBelowShaderSaturation() {
        // Bibliothek rechnet intern floor(radius / 4) und saettigt ab Radius 64 (MAX_SAMPLES = 16).
        assertTrue(CONFERENCE_BLUR_RADIUS_STRONG / 4 > CONFERENCE_BLUR_RADIUS_LIGHT / 4)
        assertTrue(CONFERENCE_BLUR_RADIUS_STRONG < 64)
        assertTrue(CONFERENCE_BLUR_RADIUS_LIGHT < 64)
    }

    // --- Unterstuetzung ------------------------------------------------------------------------

    @Test
    fun supported_requiresBothLibraryAndSecureContext() {
        assertTrue(conferenceBackgroundEffectsSupported(libraryReportsSupport = true, isSecureContext = true))
        assertFalse(conferenceBackgroundEffectsSupported(libraryReportsSupport = false, isSecureContext = true))
        assertFalse(conferenceBackgroundEffectsSupported(libraryReportsSupport = true, isSecureContext = false))
    }

    // --- Audit-Befund M5: eingebettete App-WebView --------------------------------------------

    @Test
    fun androidSystemWebView_isDetected_byTheWvToken() {
        // Echte UA-Form der Android System WebView (`; wv)` nach der Android-Version).
        val webView =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/121.0.6167.143 Mobile Safari/537.36"
        assertTrue(conferenceBackgroundIsInAppWebView(webView))
    }

    @Test
    fun iosInAppWebView_isDetected_byTheMissingSafariToken() {
        val wkWebView =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Mobile/15E148"
        assertTrue(conferenceBackgroundIsInAppWebView(wkWebView))
    }

    /**
     * Folge-Audit, Punkt 6: In-App-Browser grosser Apps schicken ein `Safari/`-Token mit und wuerden von den
     * zwei Plattform-Regeln nicht erfasst -- deshalb die zusaetzliche Token-Liste.
     */
    @Test
    fun inAppBrowsersOfLargeApps_areDetectedDespiteTheirSafariToken() {
        listOf(
            // Facebook (iOS)
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Mobile/15E148 [FBAN/FBIOS;FBDV/iPhone15,2;FBMD/iPhone;FBSN/iOS;FBSV/17.4;FBSS/3;FBID/phone;" +
                "FBLC/de_DE;FBOP/5] Safari/604.1",
            // Facebook (Android)
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 " +
                "Mobile Safari/537.36 [FBAV/450.0.0.38.109;]",
            // Instagram
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Mobile/15E148 Instagram 320.0.2.29.89 (iPhone15,2; iOS 17_4) Safari/604.1",
            // LINE
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Mobile/15E148 Safari/604.1 Line/14.2.1",
            // LinkedIn
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Mobile/15E148 Safari/604.1 LinkedInApp",
        ).forEach { userAgent -> assertTrue(conferenceBackgroundIsInAppWebView(userAgent), userAgent) }
    }

    /**
     * Die Tokens duerfen keinen echten Browser treffen -- insbesondere darf `Line/` nicht auf `Linux`
     * anspringen (deshalb der Schraegstrich im Token).
     */
    @Test
    fun theInAppTokens_doNotMatchSubstringsOfOrdinaryUserAgents() {
        assertFalse(conferenceBackgroundIsInAppWebView("Mozilla/5.0 (X11; Linux x86_64; rv:123.0) Gecko/20100101 Firefox/123.0"))
        assertFalse(
            conferenceBackgroundIsInAppWebView(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/121.0.0.0 Mobile Safari/537.36",
            ),
        )
    }

    /**
     * Die zwei bekannten blinden Flecken, festgeschrieben damit sie nicht unbemerkt "aus Versehen" zu
     * Falsch-Positiven werden: ein unbekannter In-App-Browser mit `Safari/`-Token und eine iPad-WKWebView mit
     * Macintosh-Desktop-UA bleiben UNERKANNT -- dort bleibt das Feature an und faellt im Zweifel ueber den
     * normalen Fehlerpfad zurueck. Dokumentiert unter "Known gaps" in
     * `docs/architecture/video-background-effects.adoc`.
     */
    @Test
    fun theTwoDocumentedBlindSpots_stayUndetected() {
        val unknownInAppBrowser =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Mobile/15E148 Safari/604.1 SomeUnknownApp/1.0"
        val iPadWebViewWithDesktopUserAgent =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko)"
        assertFalse(conferenceBackgroundIsInAppWebView(unknownInAppBrowser))
        assertFalse(conferenceBackgroundIsInAppWebView(iPadWebViewWithDesktopUserAgent))
    }

    @Test
    fun realBrowsers_areNeverMistakenForAnAppWebView() {
        listOf(
            // Chrome auf Android (KEIN `; wv)`)
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/121.0.0.0 Mobile Safari/537.36",
            // Mobile Safari
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
            // Chrome auf iOS
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) CriOS/121.0.6167.138 Mobile/15E148 Safari/604.1",
            // Firefox auf iOS
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) FxiOS/123.0 Mobile/15E148 Safari/605.1.15",
            // Chrome auf dem Desktop
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/121.0.0.0 Safari/537.36",
            // Firefox auf dem Desktop (gar kein AppleWebKit)
            "Mozilla/5.0 (X11; Linux x86_64; rv:123.0) Gecko/20100101 Firefox/123.0",
            // Safari auf macOS
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "", // unbekannt: konservativ NICHT als WebView zaehlen
        ).forEach { userAgent -> assertFalse(conferenceBackgroundIsInAppWebView(userAgent), userAgent) }
    }

    @Test
    fun availability_prefersTheWebViewVerdictOverTheCapabilityGate() {
        val webView =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/121.0.0.0 Mobile Safari/537.36"
        val desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        // Eine WebView meldet ueblicherweise voll unterstuetzt -- genau das ist der Befund.
        assertEquals(
            ConferenceBackgroundAvailability.UNSUPPORTED_IN_APP_WEBVIEW,
            conferenceBackgroundAvailability(libraryReportsSupport = true, isSecureContext = true, userAgent = webView),
        )
        assertEquals(
            ConferenceBackgroundAvailability.AVAILABLE,
            conferenceBackgroundAvailability(libraryReportsSupport = true, isSecureContext = true, userAgent = desktop),
        )
        assertEquals(
            ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER,
            conferenceBackgroundAvailability(libraryReportsSupport = false, isSecureContext = true, userAgent = desktop),
        )
        assertEquals(
            ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER,
            conferenceBackgroundAvailability(libraryReportsSupport = true, isSecureContext = false, userAgent = desktop),
        )
    }

    // --- Zustandsautomat -----------------------------------------------------------------------

    @Test
    fun defaultState_isOffOffOff() {
        val state = ConferenceBackgroundState()
        assertEquals(ConferenceBackgroundEffect.OFF, state.desired)
        assertEquals(ConferenceBackgroundEffect.OFF, state.applied)
        assertEquals(ConferenceBackgroundPhase.OFF, state.phase)
        assertFalse(state.autoAttemptUsed)
    }

    @Test
    fun happyPath_selectStartSucceed() {
        var state = ConferenceBackgroundState()
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(ConferenceBackgroundEffect.BLUR_LIGHT))
        assertEquals(ConferenceBackgroundEffect.BLUR_LIGHT, state.desired)
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyStarted)
        assertEquals(ConferenceBackgroundPhase.APPLYING, state.phase)
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplySucceeded(ConferenceBackgroundEffect.BLUR_LIGHT))
        assertEquals(ConferenceBackgroundEffect.BLUR_LIGHT, state.applied)
        assertEquals(ConferenceBackgroundPhase.ACTIVE, state.phase)
    }

    @Test
    fun applyFailed_fallsBackToOff_butKeepsDesired() {
        var state = ConferenceBackgroundState()
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(ConferenceBackgroundEffect.BG_SAGE))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.LOAD_FAILED))
        assertEquals(ConferenceBackgroundEffect.OFF, state.applied)
        assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, state.phase)
        assertTrue(state.autoAttemptUsed)
        assertEquals(ConferenceBackgroundEffect.BG_SAGE, state.desired) // Zhuo-Ruling: Absicht bleibt
        assertTrue(ConferenceBackgroundFailure.LOAD_FAILED in state.notifiedFailures)
    }

    @Test
    fun applyFailed_notifiesOncePerCause_notOncePerSession() {
        var state = ConferenceBackgroundState(desired = ConferenceBackgroundEffect.BG_SAGE)
        assertTrue(conferenceBackgroundShouldNotify(state, ConferenceBackgroundFailure.LOAD_FAILED))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.LOAD_FAILED))
        assertFalse(conferenceBackgroundShouldNotify(state, ConferenceBackgroundFailure.LOAD_FAILED))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.LOAD_FAILED))
        assertEquals(setOf(ConferenceBackgroundFailure.LOAD_FAILED), state.notifiedFailures)
        assertTrue(conferenceBackgroundShouldNotify(state, ConferenceBackgroundFailure.TIMEOUT))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.TIMEOUT))
        assertEquals(
            setOf(ConferenceBackgroundFailure.LOAD_FAILED, ConferenceBackgroundFailure.TIMEOUT),
            state.notifiedFailures,
        )
    }

    @Test
    fun userClickAfterFailure_getsAFreshAttempt() {
        var state = ConferenceBackgroundState()
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(ConferenceBackgroundEffect.BG_SAGE))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.APPLY_FAILED))
        assertTrue(state.autoAttemptUsed)
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(ConferenceBackgroundEffect.BG_SAGE))
        assertEquals(ConferenceBackgroundPhase.APPLYING, state.phase)
        assertFalse(state.autoAttemptUsed)
        assertEquals(ConferenceBackgroundEffect.BG_SAGE, conferenceBackgroundEffectForNewTrack(state))
    }

    @Test
    fun newLocalTrack_neverChangesDesired_orAnythingElse() {
        val state = ConferenceBackgroundState(desired = ConferenceBackgroundEffect.BLUR_STRONG)
        assertEquals(state, conferenceBackgroundReduce(state, ConferenceBackgroundEvent.NewLocalTrack))
    }

    @Test
    fun processorLost_clearsApplied_butKeepsDesired() {
        val state =
            ConferenceBackgroundState(
                desired = ConferenceBackgroundEffect.BG_STUDIO,
                applied = ConferenceBackgroundEffect.BG_STUDIO,
                phase = ConferenceBackgroundPhase.ACTIVE,
            )
        val next = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ProcessorLost)
        assertEquals(ConferenceBackgroundEffect.OFF, next.applied)
        assertEquals(ConferenceBackgroundEffect.BG_STUDIO, next.desired)
    }

    @Test
    fun reducer_neverThrows_forAnyEventInAnyPhase() {
        val events =
            buildList<ConferenceBackgroundEvent> {
                ConferenceBackgroundEffect.entries.forEach {
                    add(ConferenceBackgroundEvent.UserSelected(it))
                    add(ConferenceBackgroundEvent.ApplySucceeded(it))
                }
                ConferenceBackgroundFailure.entries.forEach { add(ConferenceBackgroundEvent.ApplyFailed(it)) }
                add(ConferenceBackgroundEvent.ApplyStarted)
                add(ConferenceBackgroundEvent.NewLocalTrack)
                add(ConferenceBackgroundEvent.ProcessorLost)
            }
        ConferenceBackgroundPhase.entries.forEach { phase ->
            events.forEach { event ->
                conferenceBackgroundReduce(ConferenceBackgroundState(phase = phase), event)
            }
        }
    }

    // --- effectToApplyForNewTrack (Jobs-K6) ----------------------------------------------------

    @Test
    fun newTrack_normalCase_appliesDesired() {
        assertEquals(
            ConferenceBackgroundEffect.BG_SAGE,
            effectToApplyForNewTrack(ConferenceBackgroundEffect.BG_SAGE, lastAttemptFailed = false, sessionAutoRetryUsed = false),
        )
    }

    @Test
    fun newTrack_failedAndAutoRetryUsed_isOff() {
        assertEquals(
            ConferenceBackgroundEffect.OFF,
            effectToApplyForNewTrack(ConferenceBackgroundEffect.BG_SAGE, lastAttemptFailed = true, sessionAutoRetryUsed = true),
        )
    }

    @Test
    fun newTrack_failedButAutoRetryStillAvailable_appliesDesired() {
        assertEquals(
            ConferenceBackgroundEffect.BG_SAGE,
            effectToApplyForNewTrack(ConferenceBackgroundEffect.BG_SAGE, lastAttemptFailed = true, sessionAutoRetryUsed = false),
        )
    }

    @Test
    fun newTrack_desiredOff_isAlwaysOff() {
        listOf(false, true).forEach { failed ->
            listOf(false, true).forEach { used ->
                assertEquals(
                    ConferenceBackgroundEffect.OFF,
                    effectToApplyForNewTrack(ConferenceBackgroundEffect.OFF, failed, used),
                )
            }
        }
    }

    @Test
    fun newTrack_afterAutomaticRestoreFailed_noSecondAutomaticAttempt() {
        var state = ConferenceBackgroundState(desired = ConferenceBackgroundEffect.BG_MIDNIGHT) // aus localStorage
        assertEquals(ConferenceBackgroundEffect.BG_MIDNIGHT, conferenceBackgroundEffectForNewTrack(state))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.TIMEOUT))
        assertEquals(ConferenceBackgroundEffect.OFF, conferenceBackgroundEffectForNewTrack(state))
        assertEquals(ConferenceBackgroundEffect.BG_MIDNIGHT, state.desired)
    }

    // --- Anzeige -------------------------------------------------------------------------------

    @Test
    fun displayedEffect_followsDesired_exceptAfterFailure() {
        val chosen = ConferenceBackgroundState(desired = ConferenceBackgroundEffect.BLUR_STRONG)
        assertEquals(ConferenceBackgroundEffect.BLUR_STRONG, conferenceBackgroundDisplayedEffect(chosen))
        val failed = conferenceBackgroundReduce(chosen, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.APPLY_FAILED))
        assertEquals(ConferenceBackgroundEffect.OFF, conferenceBackgroundDisplayedEffect(failed))
    }

    // --- Labels / Meldungen --------------------------------------------------------------------

    @Test
    fun toggleLabel_isNeverEmpty_neverLeaksTheKvisionMarker() {
        ConferenceBackgroundEffect.entries.forEach { effect ->
            val label = conferenceBackgroundToggleLabel(effect)
            assertTrue(label.isNotBlank(), effect.id)
            assertFalse(label.contains("###KvI18nS###"), effect.id)
            val effectLabel = conferenceBackgroundEffectLabel(effect)
            assertTrue(effectLabel.isNotBlank(), effect.id)
            assertFalse(effectLabel.contains("###KvI18nS###"), effect.id)
        }
    }

    /**
     * Audit-Befund N4: die SICHTBAREN Beschriftungen muessen den KVision-`tr()`-Marker tragen -- nur dann
     * loest KVision sie im Patch-Zyklus auf und uebersetzt sie bei einem Sprachwechsel zur Laufzeit neu. Die
     * `gettext`-Varianten oben (fuer Attribute und Zusammensetzungen) duerfen ihn im Gegenzug NIE tragen.
     */
    @Test
    fun visibleLabels_carryTheKvisionMarker_soALanguageSwitchRetranslatesThem() {
        ConferenceBackgroundEffect.entries.forEach { effect ->
            val tileLabel = conferenceBackgroundEffectLabelTr(effect)
            val toggleLabel = conferenceBackgroundToggleLabelTr(effect)
            assertTrue(tileLabel.contains("###KvI18nS###"), effect.id)
            assertTrue(toggleLabel.contains("###KvI18nS###"), effect.id)
            // Kein Platzhalter mehr im sichtbaren Text: tr() kann keine Argumente einsetzen.
            assertFalse(toggleLabel.contains("%1"), effect.id)
        }
        // Neun verschiedene Knopfbeschriftungen -- der Zustand steht wirklich im Text.
        val distinctToggleLabels =
            ConferenceBackgroundEffect.entries
                .map { conferenceBackgroundToggleLabelTr(it) }
                .toSet()
        assertEquals(ConferenceBackgroundEffect.entries.size, distinctToggleLabels.size)
    }

    @Test
    fun failureMessages_areThreeDistinctNonBlankTexts() {
        val messages = ConferenceBackgroundFailure.entries.map { conferenceBackgroundFailureMessage(it) }
        assertEquals(3, messages.size)
        messages.forEach {
            assertTrue(it.isNotBlank())
            assertFalse(it.contains("###KvI18nS###"))
        }
        assertEquals(3, messages.toSet().size)
    }

    @Test
    fun failureMessages_neverCarryUrlsOrFileNames() {
        ConferenceBackgroundFailure.entries.forEach {
            val message = conferenceBackgroundFailureMessage(it)
            assertFalse(message.contains("http"))
            assertFalse(message.contains(".wasm"))
            assertFalse(message.contains(".tflite"))
        }
    }

    // --- Storage / Konstanten ------------------------------------------------------------------

    @Test
    fun storageKey_followsTheLapisCloudConvention_andDoesNotCollide() {
        assertEquals("lapis-cloud-conference-background", CONFERENCE_BACKGROUND_STORAGE_KEY)
        assertNotEquals("lapis-cloud-theme", CONFERENCE_BACKGROUND_STORAGE_KEY)
        listOf(
            network.lapis.cloud.client.livekit.ConferenceDeviceKind.MICROPHONE,
            network.lapis.cloud.client.livekit.ConferenceDeviceKind.CAMERA,
            network.lapis.cloud.client.livekit.ConferenceDeviceKind.SPEAKER,
        ).forEach { assertNotEquals(conferenceDeviceStorageKey(it), CONFERENCE_BACKGROUND_STORAGE_KEY) }
    }

    @Test
    fun timeoutAndFps_areSane() {
        assertTrue(CONFERENCE_BACKGROUND_APPLY_TIMEOUT_MS in 1_000L..60_000L)
        assertEquals(15, CONFERENCE_BACKGROUND_MAX_FPS)
    }
}
