package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout direction must come from the locale, never from the data.
 *
 * A result card starts with an IP address, whose first strong character is a
 * Latin digit. With no explicit direction declared, Android's first-strong-
 * character heuristic resolves that view LTR while the Persian cards around it
 * resolve RTL — so the badge, IP and metric order flip from card to card
 * depending on what each card happens to contain.
 */
class LayoutDirectionTest {

    private val layoutDir = "android:layoutDirection=\"locale\""

    private fun layout(name: String): String =
        java.io.File("src/main/res/layout/$name").readText()

    @Test
    fun everyRecycledItemPinsDirectionToTheLocale() {
        // These are inflated into a RecyclerView, not under the activity root's
        // declaration, so each one has to state it for itself.
        for (name in listOf("item_result.xml", "item_header.xml", "item_empty_state.xml")) {
            assertTrue(
                "$name must pin layout direction to the locale, or the " +
                    "first-strong-character heuristic decides it per card",
                layout(name).contains(layoutDir),
            )
        }
    }

    @Test
    fun theIpKeepsItsOwnLatinDirectionWithoutFlippingTheCard() {
        val result = layout("item_result.xml")
        // The IP itself stays LTR — it is copied into configs verbatim.
        assertTrue(result.contains("android:textDirection=\"ltr\""))
        // But that must not be the only direction statement in the file, or the
        // surrounding card inherits its guess from the address.
        assertTrue(result.contains(layoutDir))
    }

    @Test
    fun persianTextIsIsolatedFromLatinNeighbours() {
        // Format.isolate wraps a run in FSI/PDI so a Latin token inside Persian
        // text cannot reorder the sentence around it.
        val ip = Format.ip("104.27.59.217")
        assertEquals(Format.FSI, ip.first())
        assertEquals(Format.PDI, ip.last())
        assertTrue("the address itself must stay verbatim", ip.contains("104.27.59.217"))
    }
}
