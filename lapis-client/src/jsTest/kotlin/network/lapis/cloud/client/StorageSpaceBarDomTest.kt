package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.273 -- the storage-space bar itself: width, states, labelling, nothing leaking into the DOM. */
class StorageSpaceBarDomTest {
    private fun mounted(
        id: String,
        dto: StorageSpaceDto,
        withLink: Boolean = false,
        block: suspend (HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            withFetchStub {
                mountedForm(id) { root, element ->
                    root.add(storageSpaceBar(dto, withLink))
                    block(element())
                }
            }
        }

    private fun usedOf1000(used: Long) = StorageSpaceDto(StorageSpaceLevel.INFO, 1000L - used, 1000L)

    private fun fillWidth(
        id: String,
        used: Long,
        expected: String,
    ): Promise<Unit> =
        mounted(id, usedOf1000(used)) { el ->
            val fill = assertNotNull(el.querySelector(".lapis-storage__fill"), el.innerHTML) as HTMLElement
            assertEquals(expected, fill.style.width)
        }

    @Test
    fun fill_0() = fillWidth("storage-bar-0", 0, "0%")

    @Test
    fun fill_33() = fillWidth("storage-bar-33", 330, "33%")

    @Test
    fun fill_95() = fillWidth("storage-bar-95", 950, "95%")

    @Test
    fun fill_100() = fillWidth("storage-bar-100", 1000, "100%")

    private fun unknown(
        id: String,
        dto: StorageSpaceDto,
    ): Promise<Unit> =
        mounted(id, dto) { el ->
            assertNull(el.querySelector(".lapis-storage__fill"), "no fill when unknown")
            assertTrue(el.querySelector(".lapis-storage--unknown") != null, el.innerHTML)
            val styles = el.allOf("[style]").joinToString { it.getAttribute("style").orEmpty() }
            val all = el.textContent.orEmpty() + styles + el.innerHTML
            assertFalse(all.contains("NaN") || all.contains("Infinity"), all)
        }

    @Test
    fun totalZero_isUnknown() = unknown("storage-bar-u1", StorageSpaceDto(StorageSpaceLevel.INFO, 0L, 0L))

    @Test
    fun totalNull_isUnknown() = unknown("storage-bar-u2", StorageSpaceDto(StorageSpaceLevel.INFO, 5L, null))

    @Test
    fun freeNull_isUnknown() = unknown("storage-bar-u3", StorageSpaceDto(StorageSpaceLevel.INFO, null, 1000L))

    @Test
    fun freeNegative_isUnknown() = unknown("storage-bar-u4", StorageSpaceDto(StorageSpaceLevel.INFO, -1L, 1000L))

    @Test
    fun levelUnknown_isUnknown() = unknown("storage-bar-u5", StorageSpaceDto(StorageSpaceLevel.UNKNOWN, 500L, 1000L))

    @Test
    fun freeAboveTotal_doesNotOverflow(): Promise<Unit> =
        mounted("storage-bar-over", StorageSpaceDto(StorageSpaceLevel.INFO, 2000L, 1000L)) { el ->
            val fill = assertNotNull(el.querySelector(".lapis-storage__fill")) as HTMLElement
            assertEquals("0%", fill.style.width)
        }

    @Test
    fun everyLevel_hasItsClass_icon_andHeadline(): Promise<Unit> =
        formTest {
            val expected =
                mapOf(
                    StorageSpaceLevel.INFO to ("lapis-storage--info" to "Speicherplatz"),
                    StorageSpaceLevel.HINT to ("lapis-storage--hint" to "Speicherplatz knapp"),
                    StorageSpaceLevel.WARNING to ("lapis-storage--warning" to "Speicherplatz fast voll"),
                    StorageSpaceLevel.UNKNOWN to ("lapis-storage--unknown" to "Speicherplatz nicht messbar"),
                )
            expected.forEach { (level, pair) ->
                val dto =
                    if (level == StorageSpaceLevel.UNKNOWN) StorageSpaceDto(level, null, null) else StorageSpaceDto(level, 300L, 1000L)
                val bar = storageSpaceBar(dto)
                withFetchStub {
                    mountedForm("storage-bar-level-$level") { root, element ->
                        root.add(bar)
                        val el = element()
                        assertTrue(el.querySelector(".${pair.first}") != null, "class for $level: ${el.innerHTML}")
                        val icon = assertNotNull(el.querySelector(".fas"))
                        assertTrue(icon.className.contains(storageIconClass(level).removePrefix("fas ")), icon.className)
                        assertEquals("true", icon.getAttribute("aria-hidden"))
                        assertEquals(pair.second, el.querySelector(".lapis-storage__title")?.textContent?.trim())
                    }
                }
            }
        }

    @Test
    fun roleImg_label_sentence_andNoPathOrHost(): Promise<Unit> =
        mounted("storage-bar-label", StorageSpaceDto(StorageSpaceLevel.INFO, 150_000_000_000L, 1_000_000_000_000L)) { el ->
            val figure = assertNotNull(el.querySelector("[role=img]"), el.innerHTML) as HTMLElement
            val sentence = el.querySelector(".lapis-storage__text")?.textContent.orEmpty()
            assertTrue(sentence.contains("Belegt"), sentence)
            assertTrue(sentence.contains("frei"), sentence)
            assertTrue(sentence.contains("15 %"), sentence)
            assertEquals("Speicherplatz. $sentence", figure.getAttribute("aria-label"))
            assertFalse(el.textContent.orEmpty().contains("://"), el.textContent)
            assertFalse(el.innerHTML.contains("://"), el.innerHTML)
        }

    @Test
    fun link_onlyWhenAsked_andNeverInsideTheImgRole(): Promise<Unit> =
        formTest {
            val dto = StorageSpaceDto(StorageSpaceLevel.HINT, 150L, 1000L)
            withFetchStub {
                mountedForm("storage-bar-nolink") { root, element ->
                    root.add(storageSpaceBar(dto, withLink = false))
                    assertNull(element().querySelector("a"))
                }
            }
            withFetchStub {
                mountedForm("storage-bar-link") { root, element ->
                    root.add(storageSpaceBar(dto, withLink = true))
                    val el = element()
                    val link = assertNotNull(el.querySelector("a"), el.innerHTML)
                    assertEquals("#${Routes.DOCUMENTS}", link.getAttribute("href"))
                    val figure = assertNotNull(el.querySelector("[role=img]"))
                    assertFalse(figure.contains(link), "the link is a sibling of the img role, not a descendant")
                }
            }
        }

    @Test
    fun noInlineStyleExceptTheWidthOfTheFill(): Promise<Unit> =
        mounted("storage-bar-style", StorageSpaceDto(StorageSpaceLevel.WARNING, 40L, 1000L), withLink = true) { el ->
            val styled = el.allOf("[style]")
            assertEquals(1, styled.size, el.innerHTML)
            assertTrue(styled.single().classList.contains("lapis-storage__fill"))
        }

    @Test
    fun modelAndSentence_arePure() {
        val model = storageBarModel(StorageSpaceDto(StorageSpaceLevel.INFO, 250_000_000_000L, 1_000_000_000_000L))
        assertEquals(75, model.usedPercent)
        assertEquals(750_000_000_000L, model.usedBytes)
        assertEquals("Belegt 750,0 GB von 1,0 TB, frei 250,0 GB (25 % frei)", storageSentence(model))
        assertEquals(StorageSpaceLevel.UNKNOWN, storageBarModel(StorageSpaceDto(StorageSpaceLevel.WARNING, 1L, 0L)).level)
        assertNull(storageBarModel(StorageSpaceDto(StorageSpaceLevel.INFO, null, null)).usedPercent)
    }
}
