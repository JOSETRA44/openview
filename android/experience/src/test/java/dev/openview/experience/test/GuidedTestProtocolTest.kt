// Copyright 2026, OpenView contributors. SPDX-License-Identifier: BSL-1.0
package dev.openview.experience.test

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedTestProtocolTest {
    private val walk = GuidedTestProtocol.WALK

    @Test fun stepIdsAreUnique() {
        assertEquals(walk.size, walk.map { it.id }.toSet().size)
    }

    @Test fun walkingStepsReturnToTheStart() {
        // The analyser checks loop closure: the expected moves must cancel out.
        val moves = walk.mapNotNull { it.expected }
        assertEquals(0f, moves.sumOf { it.x.toDouble() }.toFloat(), 1e-6f)
        assertEquals(0f, moves.sumOf { it.y.toDouble() }.toFloat(), 1e-6f)
        assertEquals(0f, moves.sumOf { it.z.toDouble() }.toFloat(), 1e-6f)
    }

    @Test fun everyMoveIsFollowedByAStillStep() {
        walk.forEachIndexed { i, step ->
            if (step.kind != StepKind.STILL) assertEquals(StepKind.STILL, walk[i + 1].kind)
        }
    }

    @Test fun positionWalksThroughTheSteps() {
        assertEquals(GuidedTestProtocol.Position(0, walk[0].seconds), GuidedTestProtocol.at(walk, 0))
        val firstEnd = walk[0].seconds * 1000L
        assertEquals(0, GuidedTestProtocol.at(walk, firstEnd - 1)!!.index)
        assertEquals(1, GuidedTestProtocol.at(walk, firstEnd)!!.index)
        assertEquals(walk[1].seconds, GuidedTestProtocol.at(walk, firstEnd)!!.secondsLeft)
        assertEquals(1, GuidedTestProtocol.at(walk, firstEnd + walk[1].seconds * 1000L - 1)!!.secondsLeft)
    }

    @Test fun protocolEnds() {
        val total = GuidedTestProtocol.totalSeconds(walk) * 1000L
        assertNotNull(GuidedTestProtocol.at(walk, total - 1))
        assertNull(GuidedTestProtocol.at(walk, total))
        assertTrue("short enough to hold a phone up", total <= 90_000L)
    }
}
