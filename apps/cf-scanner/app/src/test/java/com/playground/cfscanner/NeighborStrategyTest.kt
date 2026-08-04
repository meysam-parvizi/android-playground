package com.playground.cfscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborStrategyTest {

    @Test
    fun offsetsSpreadGeometricallyInBothDirections() {
        assertEquals(
            listOf(1, -1, 2, -2, 4, -4, 8, -8, 16, -16, 32, -32),
            NeighborStrategy.offsets(radius = 32, limit = 12),
        )
    }

    @Test
    fun limitCapsWorkPerCleanHit() {
        assertEquals(
            listOf(1, -1, 2, -2, 4, -4, 8, -8),
            NeighborStrategy.offsets(radius = 32, limit = 8),
        )
    }

    @Test
    fun noOffsetExceedsRadiusOrRepeats() {
        val offsets = NeighborStrategy.offsets(radius = 10, limit = 100)
        assertTrue(offsets.all { kotlin.math.abs(it) <= 10 })
        assertEquals(offsets.size, offsets.distinct().size)
    }

    @Test
    fun disabledParametersProduceNoWork() {
        assertTrue(NeighborStrategy.offsets(radius = 0, limit = 8).isEmpty())
        assertTrue(NeighborStrategy.offsets(radius = 32, limit = 0).isEmpty())
    }
}
