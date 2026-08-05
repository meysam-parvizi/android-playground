package com.playground.cfscanner

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtree walk, verified by simulation.
 *
 * `Format.applyDirection` cannot be called here — plain JVM tests have no real
 * Views. What matters is the rule it implements, which was wrong in five
 * releases: assign to EVERY view in the subtree, unconditionally.
 *
 * The measured failure was a card reporting the language as RTL and its own
 * direction as RTL while its inner ConstraintLayout was still LTR. Android's
 * `setLayoutDirection` only invalidates resolution when the value differs from
 * the current *raw* value; children inherit, so their raw value never changes
 * and they keep the direction they resolved earlier while detached.
 */
class SubtreeDirectionTest {

    /** Minimal stand-in: a tree of nodes with the same assign/inherit semantics. */
    private class Node(val children: List<Node> = emptyList()) {
        /** What the view resolved earlier; stale for detached children. */
        var resolved: Int = Format.LAYOUT_DIRECTION_LTR
        var assignments = 0

        fun assign(direction: Int) {
            resolved = direction
            assignments++
        }
    }

    /** The rule under test, applied to the stand-in tree. */
    private fun apply(node: Node, direction: Int) {
        node.assign(direction)
        node.children.forEach { apply(it, direction) }
    }

    /** The broken rule from 0.11.5-0.11.8: root only. */
    private fun applyRootOnly(node: Node, direction: Int) {
        node.assign(direction)
    }

    private fun card() = Node(
        listOf(
            Node(listOf(Node(), Node(), Node(), Node())), // inner ConstraintLayout
        ),
    )

    @After
    fun restoreDefault() = Format.setLocale(LocaleRegistry.DEFAULT)

    @Test
    fun rootOnlyLeavesTheInnerLayoutStale() {
        // Reproduces the measured bug: F1 V1 ... L0.
        val root = card()
        applyRootOnly(root, Format.LAYOUT_DIRECTION_RTL)

        assertEquals(Format.LAYOUT_DIRECTION_RTL, root.resolved)
        assertEquals(
            "the inner layout keeps its stale LTR — this is the bug",
            Format.LAYOUT_DIRECTION_LTR,
            root.children[0].resolved,
        )
    }

    @Test
    fun theWalkReachesEveryDescendant() {
        val root = card()
        apply(root, Format.LAYOUT_DIRECTION_RTL)

        fun assertAll(node: Node) {
            assertEquals(Format.LAYOUT_DIRECTION_RTL, node.resolved)
            node.children.forEach(::assertAll)
        }
        assertAll(root)
    }

    @Test
    fun assignmentIsUnconditional() {
        // A guard like `if (view.layoutDirection != target)` reads the resolved
        // value, which is the field that lies: a stale child reports the
        // direction it cached, so the guard skips the view that needs fixing.
        val root = card()
        root.children[0].resolved = Format.LAYOUT_DIRECTION_RTL // already "correct"
        apply(root, Format.LAYOUT_DIRECTION_RTL)

        assertTrue(
            "every view must be assigned even when it already reports the target",
            root.children[0].assignments > 0,
        )
    }

    @Test
    fun resultCardsGetTheirDirectionOnBindAsWellAsOnCreate() {
        // A holder created before the language was applied is reused after it,
        // so creation-time-only application leaves a stale card on screen.
        val src = java.io.File(
            "src/main/java/com/playground/cfscanner/ResultAdapter.kt",
        ).readText()
        val calls = Regex("Format\\.applyDirection").findAll(src).count()

        assertTrue(
            "expected applyDirection in both onCreateViewHolder and " +
                "onBindViewHolder (found $calls call(s))",
            calls >= 2,
        )
    }

    @Test
    fun theAdaptersAllUseTheSharedHelper() {
        // Three adapters inflate items; a subtle fix duplicated three times is
        // three chances to diverge.
        for (name in listOf("ResultAdapter.kt", "HeaderAdapter.kt", "EmptyStateAdapter.kt")) {
            val src = java.io.File("src/main/java/com/playground/cfscanner/$name").readText()
            assertTrue(
                "$name must call Format.applyDirection on the inflated item",
                src.contains("Format.applyDirection"),
            )
        }
    }
}
