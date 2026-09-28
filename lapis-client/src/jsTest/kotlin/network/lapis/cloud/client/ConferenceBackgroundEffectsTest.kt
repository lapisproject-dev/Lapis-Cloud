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
 *
 * V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- alle Faelle wurden auf
 * [ConferenceBackgroundChoice] umgestellt (ersetzt den bisherigen bloßen [ConferenceBackgroundEffect]
 * ueberall dort, wo die Wahl generisch ist); neue Faelle fuer [ConferenceBackgroundChoice.Custom]
 * (`custom:<uuid>`-Parsing, same-origin-Bildpfad, Zaehlertexte) stehen am Ende der jeweiligen Abschnitte.
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

    private val sampleUuid = "12345678-1234-1234-1234-123456789abc"

    // --- Whitelist / Parsing ------------------------------------------------------------------

    @Test
    fun parse_nullBlankAndOff_areNull() {
        assertNull(parseStoredBackgroundChoice(null))
        assertNull(parseStoredBackgroundChoice(""))
        assertNull(parseStoredBackgroundChoice("   "))
        assertNull(parseStoredBackgroundChoice("off"))
    }

    @Test
    fun parse_wrongCaseAndHostileValues_areNull() {
        assertNull(parseStoredBackgroundChoice("BLUR-LIGHT"))
        assertNull(parseStoredBackgroundChoice("../../etc/passwd"))
        assertNull(parseStoredBackgroundChoice("https://evil.example/x.png"))
        assertNull(parseStoredBackgroundChoice("bg-does-not-exist"))
        assertNull(parseStoredBackgroundChoice(" bg-sage"))
    }

    @Test
    fun parse_knownId_roundTrips() {
        assertEquals(
            ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_WARM_GREY),
            parseStoredBackgroundChoice("bg-warm-grey"),
        )
    }

    @Test
    fun parse_everyEnumId_roundTrips_exceptOff() {
        ConferenceBackgroundEffect.entries.forEach { effect ->
            val expected = if (effect == ConferenceBackgroundEffect.OFF) null else ConferenceBackgroundChoice.BuiltIn(effect)
            assertEquals(expected, parseStoredBackgroundChoice(effect.id), effect.id)
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
        assertNull(conferenceBackgroundPersistValue(CONFERENCE_BACKGROUND_OFF))
        val nonOff = ConferenceBackgroundEffect.entries.filter { it != ConferenceBackgroundEffect.OFF }
        nonOff.forEach { effect ->
            val value = conferenceBackgroundPersistValue(ConferenceBackgroundChoice.BuiltIn(effect))
            assertEquals(effect.id, value)
            assertTrue(value!!.isNotBlank())
        }
    }

    // --- V1.9.4: custom:<uuid> Parsing ----------------------------------------------------------

    @Test
    fun parse_customWithCanonicalUuid_roundTrips() {
        assertEquals(
            ConferenceBackgroundChoice.Custom(sampleUuid),
            parseStoredBackgroundChoice("custom:$sampleUuid"),
        )
    }

    @Test
    fun parse_customWithoutOrMalformedUuid_isNull() {
        assertNull(parseStoredBackgroundChoice("custom:"))
        assertNull(parseStoredBackgroundChoice("custom:not-a-uuid"))
        assertNull(parseStoredBackgroundChoice("custom:${sampleUuid.uppercase()}")) // kein trim/lowercase, siehe KDoc
        assertNull(parseStoredBackgroundChoice("custom: $sampleUuid"))
        assertNull(parseStoredBackgroundChoice("custom:$sampleUuid/../etc"))
        assertNull(parseStoredBackgroundChoice("custom:${sampleUuid}extra"))
        assertNull(parseStoredBackgroundChoice("custom:12345678123412341234123456789abc")) // ohne Bindestriche
    }

    @Test
    fun persistValue_custom_isPrefixedWithCustomColon() {
        assertEquals("custom:$sampleUuid", conferenceBackgroundPersistValue(ConferenceBackgroundChoice.Custom(sampleUuid)))
    }

    // --- Asset-Pfade ---------------------------------------------------------------------------

    @Test
    fun imagePath_forBackgrounds_isSameOriginWebp() {
        imageEffects.forEach { effect ->
            assertEquals(
                "/assets/video-backgrounds/${effect.id}.webp",
                conferenceBackgroundImagePath(ConferenceBackgroundChoice.BuiltIn(effect)),
            )
        }
    }

    @Test
    fun imagePath_forOffAndBlur_isNull() {
        assertNull(conferenceBackgroundImagePath(CONFERENCE_BACKGROUND_OFF))
        assertNull(conferenceBackgroundImagePath(ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_LIGHT)))
        assertNull(conferenceBackgroundImagePath(ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_STRONG)))
    }

    @Test
    fun imagePath_forCustom_isSameOriginApiPath() {
        assertEquals(
            "/api/conference-backgrounds/$sampleUuid/image",
            conferenceBackgroundImagePath(ConferenceBackgroundChoice.Custom(sampleUuid)),
        )
    }

    @Test
    fun thumbPath_forCustom_isSameOriginApiPath() {
        assertEquals("/api/conference-backgrounds/$sampleUuid/thumb", conferenceBackgroundThumbPath(sampleUuid))
    }

    @Test
    fun thumbPath_rejectsANonCanonicalId_evenIfSomehowReached() {
        assertNull(conferenceBackgroundThumbPath("../../etc/passwd"))
        assertNull(conferenceBackgroundThumbPath(""))
    }

    @Test
    fun producedPaths_neverEscapeTheSameOrigin() {
        val paths =
            ConferenceBackgroundEffect.entries.mapNotNull { conferenceBackgroundImagePath(ConferenceBackgroundChoice.BuiltIn(it)) } +
                listOf(
                    conferenceBackgroundImagePath(ConferenceBackgroundChoice.Custom(sampleUuid))!!,
                    conferenceBackgroundThumbPath(sampleUuid)!!,
                    ConferenceBackgroundAssets.TASKS_VISION_FILE_SET,
                    ConferenceBackgroundAssets.MODEL_ASSET_PATH,
                    ConferenceBackgroundAssets.BACKGROUND_IMAGE_DIR,
                )
        paths.forEach { path ->
            assertTrue(path.startsWith("/assets/") || path.startsWith("/api/"), path)
            assertFalse(path.contains("//"), path)
            assertFalse(path.contains(".."), path)
            assertTrue(path.indexOf(":") == -1, path) // ein same-origin "/..."-Pfad hat nie einen Doppelpunkt (kein Schema, kein Port)
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
        assertEquals(8, conferenceBackgroundBlurRadius(ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_LIGHT)))
        assertEquals(24, conferenceBackgroundBlurRadius(ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_STRONG)))
        assertNull(conferenceBackgroundBlurRadius(CONFERENCE_BACKGROUND_OFF))
        assertNull(conferenceBackgroundBlurRadius(ConferenceBackgroundChoice.Custom(sampleUuid)))
        imageEffects.forEach { assertNull(conferenceBackgroundBlurRadius(ConferenceBackgroundChoice.BuiltIn(it))) }
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

    /**
     * V1.4.24: Die Android System WebView (`; wv)`) ist NICHT mehr gesperrt -- auf einem Nokia 9 (Android 10,
     * WebView 153) gemessen und in Ordnung. Die zweite UA ist die echte Kennung dieses Geraets.
     */
    @Test
    fun androidSystemWebView_isNoLongerBlocked_sinceItWasMeasuredOnARealDevice() {
        listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/121.0.6167.143 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10; Nokia 9 Build/QKQ1.190828.002; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36",
        ).forEach { userAgent -> assertFalse(conferenceBackgroundIsInAppWebView(userAgent), userAgent) }
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
     * Die Token-Regel steht VOR allem anderen: ein Drittanbieter-In-App-Browser auf Android traegt haeufig
     * zusaetzlich `; wv)` -- er bleibt gesperrt, obwohl die reine Android-WebView seit V1.4.24 frei ist.
     */
    @Test
    fun anAndroidWebViewWithAThirdPartyInAppToken_staysBlocked() {
        val facebookOnAndroidWebView =
            "Mozilla/5.0 (Linux; Android 10; Nokia 9 Build/QKQ1.190828.002; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36 [FBAV/450.0.0.38.109;]"
        assertTrue(conferenceBackgroundIsInAppWebView(facebookOnAndroidWebView))
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
            // Chrome auf Android
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
        // Ein iOS-WKWebView (kein `Safari/`-Token) ist weiterhin gesperrt: nie auf einem Geraet getestet.
        val webView =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                "Mobile/15E148"
        val androidWebView =
            "Mozilla/5.0 (Linux; Android 10; Nokia 9 Build/QKQ1.190828.002; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36"
        val desktop = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
        // Eine WebView meldet ueblicherweise voll unterstuetzt -- deshalb sperrt nur die UA-Regel (iOS).
        assertEquals(
            ConferenceBackgroundAvailability.UNSUPPORTED_IN_APP_WEBVIEW,
            conferenceBackgroundAvailability(libraryReportsSupport = true, isSecureContext = true, userAgent = webView),
        )
        // Die Android-WebView laeuft durch das normale Faehigkeits-Gate (Bibliothek + sicherer Kontext).
        assertEquals(
            ConferenceBackgroundAvailability.AVAILABLE,
            conferenceBackgroundAvailability(libraryReportsSupport = true, isSecureContext = true, userAgent = androidWebView),
        )
        assertEquals(
            ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER,
            conferenceBackgroundAvailability(libraryReportsSupport = false, isSecureContext = true, userAgent = androidWebView),
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
        assertEquals(CONFERENCE_BACKGROUND_OFF, state.desired)
        assertEquals(CONFERENCE_BACKGROUND_OFF, state.applied)
        assertEquals(ConferenceBackgroundPhase.OFF, state.phase)
        assertFalse(state.autoAttemptUsed)
    }

    @Test
    fun happyPath_selectStartSucceed() {
        val blurLight = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_LIGHT)
        var state = ConferenceBackgroundState()
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(blurLight))
        assertEquals(blurLight, state.desired)
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyStarted)
        assertEquals(ConferenceBackgroundPhase.APPLYING, state.phase)
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplySucceeded(blurLight))
        assertEquals(blurLight, state.applied)
        assertEquals(ConferenceBackgroundPhase.ACTIVE, state.phase)
    }

    @Test
    fun happyPath_selectStartSucceed_forACustomImage() {
        val custom = ConferenceBackgroundChoice.Custom(sampleUuid)
        var state = ConferenceBackgroundState()
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(custom))
        assertEquals(custom, state.desired)
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplySucceeded(custom))
        assertEquals(custom, state.applied)
        assertEquals(ConferenceBackgroundPhase.ACTIVE, state.phase)
    }

    @Test
    fun applyFailed_fallsBackToOff_butKeepsDesired() {
        val bgSage = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_SAGE)
        var state = ConferenceBackgroundState()
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(bgSage))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.LOAD_FAILED))
        assertEquals(CONFERENCE_BACKGROUND_OFF, state.applied)
        assertEquals(ConferenceBackgroundPhase.FAILED_FALLBACK, state.phase)
        assertTrue(state.autoAttemptUsed)
        assertEquals(bgSage, state.desired) // Zhuo-Ruling: Absicht bleibt
        assertTrue(ConferenceBackgroundFailure.LOAD_FAILED in state.notifiedFailures)
    }

    @Test
    fun applyFailed_notifiesOncePerCause_notOncePerSession() {
        var state = ConferenceBackgroundState(desired = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_SAGE))
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
        val bgSage = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_SAGE)
        var state = ConferenceBackgroundState()
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(bgSage))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.APPLY_FAILED))
        assertTrue(state.autoAttemptUsed)
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.UserSelected(bgSage))
        assertEquals(ConferenceBackgroundPhase.APPLYING, state.phase)
        assertFalse(state.autoAttemptUsed)
        assertEquals(bgSage, conferenceBackgroundChoiceForNewTrack(state))
    }

    @Test
    fun newLocalTrack_neverChangesDesired_orAnythingElse() {
        val state = ConferenceBackgroundState(desired = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_STRONG))
        assertEquals(state, conferenceBackgroundReduce(state, ConferenceBackgroundEvent.NewLocalTrack))
    }

    @Test
    fun processorLost_clearsApplied_butKeepsDesired() {
        val bgStudio = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_STUDIO)
        val state =
            ConferenceBackgroundState(
                desired = bgStudio,
                applied = bgStudio,
                phase = ConferenceBackgroundPhase.ACTIVE,
            )
        val next = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ProcessorLost)
        assertEquals(CONFERENCE_BACKGROUND_OFF, next.applied)
        assertEquals(bgStudio, next.desired)
    }

    @Test
    fun reducer_neverThrows_forAnyEventInAnyPhase() {
        val events =
            buildList<ConferenceBackgroundEvent> {
                ConferenceBackgroundEffect.entries.forEach {
                    val choice = ConferenceBackgroundChoice.BuiltIn(it)
                    add(ConferenceBackgroundEvent.UserSelected(choice))
                    add(ConferenceBackgroundEvent.ApplySucceeded(choice))
                }
                add(ConferenceBackgroundEvent.UserSelected(ConferenceBackgroundChoice.Custom(sampleUuid)))
                add(ConferenceBackgroundEvent.ApplySucceeded(ConferenceBackgroundChoice.Custom(sampleUuid)))
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

    // --- choiceToApplyForNewTrack (Jobs-K6, ex effectToApplyForNewTrack) -----------------------

    @Test
    fun newTrack_normalCase_appliesDesired() {
        val bgSage = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_SAGE)
        assertEquals(bgSage, choiceToApplyForNewTrack(desired = bgSage, lastAttemptFailed = false, sessionAutoRetryUsed = false))
    }

    @Test
    fun newTrack_failedAndAutoRetryUsed_isOff() {
        val bgSage = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_SAGE)
        assertEquals(
            CONFERENCE_BACKGROUND_OFF,
            choiceToApplyForNewTrack(desired = bgSage, lastAttemptFailed = true, sessionAutoRetryUsed = true),
        )
    }

    @Test
    fun newTrack_failedButAutoRetryStillAvailable_appliesDesired() {
        val bgSage = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_SAGE)
        assertEquals(bgSage, choiceToApplyForNewTrack(desired = bgSage, lastAttemptFailed = true, sessionAutoRetryUsed = false))
    }

    @Test
    fun newTrack_desiredOff_isAlwaysOff() {
        listOf(false, true).forEach { failed ->
            listOf(false, true).forEach { used ->
                assertEquals(
                    CONFERENCE_BACKGROUND_OFF,
                    choiceToApplyForNewTrack(desired = CONFERENCE_BACKGROUND_OFF, lastAttemptFailed = failed, sessionAutoRetryUsed = used),
                )
            }
        }
    }

    @Test
    fun newTrack_afterAutomaticRestoreFailed_noSecondAutomaticAttempt() {
        val bgMidnight = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BG_MIDNIGHT)
        var state = ConferenceBackgroundState(desired = bgMidnight) // aus localStorage
        assertEquals(bgMidnight, conferenceBackgroundChoiceForNewTrack(state))
        state = conferenceBackgroundReduce(state, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.TIMEOUT))
        assertEquals(CONFERENCE_BACKGROUND_OFF, conferenceBackgroundChoiceForNewTrack(state))
        assertEquals(bgMidnight, state.desired)
    }

    // --- Anzeige -------------------------------------------------------------------------------

    @Test
    fun displayedChoice_followsDesired_exceptAfterFailure() {
        val blurStrong = ConferenceBackgroundChoice.BuiltIn(ConferenceBackgroundEffect.BLUR_STRONG)
        val chosen = ConferenceBackgroundState(desired = blurStrong)
        assertEquals(blurStrong, conferenceBackgroundDisplayedChoice(chosen))
        val failed = conferenceBackgroundReduce(chosen, ConferenceBackgroundEvent.ApplyFailed(ConferenceBackgroundFailure.APPLY_FAILED))
        assertEquals(CONFERENCE_BACKGROUND_OFF, conferenceBackgroundDisplayedChoice(failed))
    }

    // --- Labels / Meldungen --------------------------------------------------------------------

    @Test
    fun toggleLabel_isNeverEmpty_neverLeaksTheKvisionMarker() {
        ConferenceBackgroundEffect.entries.forEach { effect ->
            val label = conferenceBackgroundToggleLabel(ConferenceBackgroundChoice.BuiltIn(effect))
            assertTrue(label.isNotBlank(), effect.id)
            assertFalse(label.contains("###KvI18nS###"), effect.id)
            val effectLabel = conferenceBackgroundEffectLabel(effect)
            assertTrue(effectLabel.isNotBlank(), effect.id)
            assertFalse(effectLabel.contains("###KvI18nS###"), effect.id)
        }
        val customLabel = conferenceBackgroundToggleLabel(ConferenceBackgroundChoice.Custom(sampleUuid))
        assertTrue(customLabel.isNotBlank())
        assertFalse(customLabel.contains("###KvI18nS###"))
    }

    /**
     * Audit-Befund N4: die SICHTBAREN Beschriftungen muessen den KVision-`tr()`-Marker tragen -- nur dann
     * loest KVision sie im Patch-Zyklus auf und uebersetzt sie bei einem Sprachwechsel zur Laufzeit neu. Die
     * `gettext`-Varianten oben (fuer Attribute und Zusammensetzungen) duerfen ihn im Gegenzug NIE tragen.
     */
    @Test
    fun visibleLabels_carryTheKvisionMarker_soALanguageSwitchRetranslatesThem() {
        ConferenceBackgroundEffect.entries.forEach { effect ->
            val choice = ConferenceBackgroundChoice.BuiltIn(effect)
            val tileLabel = conferenceBackgroundChoiceLabelTr(choice)
            val toggleLabel = conferenceBackgroundToggleLabelTr(choice)
            assertTrue(tileLabel.contains("###KvI18nS###"), effect.id)
            assertTrue(toggleLabel.contains("###KvI18nS###"), effect.id)
            // Kein Platzhalter mehr im sichtbaren Text: tr() kann keine Argumente einsetzen.
            assertFalse(toggleLabel.contains("%1"), effect.id)
        }
        val customToggleLabel = conferenceBackgroundToggleLabelTr(ConferenceBackgroundChoice.Custom(sampleUuid))
        assertTrue(customToggleLabel.contains("###KvI18nS###"))
        assertFalse(customToggleLabel.contains("%1"))
        // Zehn verschiedene Knopfbeschriftungen (neun eingebaute + "Eigenes Bild") -- der Zustand steht
        // wirklich im Text.
        val distinctToggleLabels =
            (
                ConferenceBackgroundEffect.entries.map { conferenceBackgroundToggleLabelTr(ConferenceBackgroundChoice.BuiltIn(it)) } +
                    customToggleLabel
            ).toSet()
        assertEquals(ConferenceBackgroundEffect.entries.size + 1, distinctToggleLabels.size)
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
