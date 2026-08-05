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
    fun theDeclarationIsOnTheRootOfEachItem() {
        // Declaring it on an inner container is not enough: RecyclerView inflates
        // the item's ROOT view, so that root is where the heuristic runs. A card
        // whose MaterialCardView lacks the attribute still resolves itself from
        // its first strong character, which is the Latin digit of an IP.
        for (name in listOf("item_result.xml", "item_header.xml", "item_empty_state.xml")) {
            // Strip the XML declaration and any leading comment, then take the
            // first real element: that is the root RecyclerView inflates.
            val text = layout(name)
                .replace(Regex("<\\?xml.*?\\?>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            val rootAttrs = text.substring(0, text.indexOf('>', text.indexOf('<')))
            assertTrue(
                "$name declares the direction only on an inner view; the item root " +
                    "is what RecyclerView inflates and what the heuristic resolves",
                rootAttrs.contains(layoutDir),
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
    fun everyTextViewInACardDeclaresItsDirection() {
        // layoutDirection on the root fixes the *container*, but each TextView
        // still resolves its own text direction from content. The datacenter
        // line holds a bare Latin code like "VIE", so without a declaration it
        // resolves LTR and drags its constraint chain with it — which is the
        // per-card flipping that survived two attempted fixes.
        val text = layout("item_result.xml")
        val views = Regex("<TextView(.*?)/>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).map { it.groupValues[1] }.toList()

        assertTrue("expected the card to contain TextViews", views.isNotEmpty())
        for (v in views) {
            val id = Regex("android:id=\"@\\+id/(\\w+)\"").find(v)?.groupValues?.get(1) ?: "?"
            assertTrue(
                "$id must declare android:textDirection, or its content decides it",
                v.contains("android:textDirection="),
            )
        }
    }

    @Test
    fun theAdapterForcesItemsToMatchTheListDirection() {
        // XML alone cannot fix this. Items are inflated with attachToRoot=false,
        // so a card has no parent when it resolves RTL properties, and
        // layoutDirection="locale" then resolves against Locale.getDefault() —
        // which this app rewrites when applying its language. Three XML-only
        // attempts failed for that reason. The adapter must copy the list's own
        // resolved direction onto each card.
        val adapter = java.io.File(
            "src/main/java/com/playground/cfscanner/ResultAdapter.kt",
        ).readText()

        assertTrue(
            "ResultAdapter must set the item's layoutDirection from its parent " +
                "in onCreateViewHolder; XML attributes alone do not survive " +
                "attachToRoot=false inflation",
            adapter.contains("layoutDirection = parent.layoutDirection"),
        )
    }

    @Test
    fun theListItselfDeclaresADirection() {
        // The parent the adapter copies from has to be right in the first place.
        val main = layout("activity_main.xml")
        val rv = main.substring(main.indexOf("<androidx.recyclerview"))
        val rvTag = rv.substring(0, rv.indexOf("/>"))
        assertTrue(
            "the RecyclerView must declare its own direction, since every card " +
                "now inherits it",
            rvTag.contains(layoutDir),
        )
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
