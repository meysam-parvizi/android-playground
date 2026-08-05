package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout direction must be inherited, not re-declared per view.
 *
 * `android:layoutDirection="locale"` does not consult the parent: AOSP
 * View.java resolves LAYOUT_DIRECTION_LOCALE through
 * TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) — a process-global
 * this app rewrites in attachBaseContext. Worse, a view set to "locale" becomes
 * its own resolution authority and is skipped by ViewGroup's propagation, which
 * only pushes direction into children where isLayoutDirectionInherited() is
 * true.
 *
 * RecyclerView creates holders lazily, so each item snapshotted whatever the
 * global happened to be at its own resolution time and cached the result. That
 * is why direction tracked scroll position, and why three rounds of *adding*
 * attributes made it worse: each new attribute created another independent
 * resolution point immune to the correct parent value.
 */
class LayoutDirectionTest {

    private fun layout(name: String): String =
        java.io.File("src/main/res/layout/$name").readText()

    private fun source(name: String): String =
        java.io.File("src/main/java/com/playground/cfscanner/$name").readText()

    @Test
    fun onlyTheActivityRootDeclaresADirection() {
        // One authority for the whole window. Every view below it inherits,
        // which is the framework default (LAYOUT_DIRECTION_DEFAULT == INHERIT).
        val declarations = Regex("android:layoutDirection=\"(\\w+)\"")
            .findAll(layout("activity_main.xml")).map { it.groupValues[1] }.toList()

        assertEquals("exactly one declaration expected", 1, declarations.size)
        assertEquals("locale", declarations.first())
    }

    @Test
    fun recycledItemsNeverDeclareTheirOwnDirection() {
        // A "locale" item root opts itself out of parent propagation and
        // resolves against the mutable global instead. That is the bug.
        for (name in listOf("item_result.xml", "item_header.xml", "item_empty_state.xml")) {
            assertTrue(
                "$name must not declare layoutDirection: it makes the item its " +
                    "own resolution authority and excludes it from inheritance",
                !layout(name).contains("android:layoutDirection"),
            )
            assertTrue(
                "$name must not declare textDirection=\"locale\" for the same reason",
                !layout(name).contains("android:textDirection=\"locale\""),
            )
        }
    }

    @Test
    fun theIpKeepsItsOwnLatinDirection() {
        // The one legitimate override: an address is copied into configs
        // verbatim, so its digits must not be reordered by the surrounding RTL
        // text. This is a property of the content, not of the card.
        assertTrue(layout("item_result.xml").contains("android:textDirection=\"ltr\""))
    }

    @Test
    fun persianTextIsIsolatedFromLatinNeighbours() {
        val ip = Format.ip("104.27.59.217")
        assertEquals(Format.FSI, ip.first())
        assertEquals(Format.PDI, ip.last())
        assertTrue("the address itself must stay verbatim", ip.contains("104.27.59.217"))
    }

    @Test
    fun theDatacenterCodeIsIsolated() {
        // A bare Latin "VIE" beside Persian text would otherwise be reordered
        // by the bidi algorithm.
        assertTrue(
            "contextLine must isolate the colo code",
            source("ResultAdapter.kt").contains("Format.isolate(r.colo)"),
        )
    }
}
