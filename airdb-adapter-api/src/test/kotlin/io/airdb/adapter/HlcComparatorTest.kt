/*
 * Copyright 2026 AirDB Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.airdb.adapter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HlcComparatorTest {

    @Test
    fun testLogicalCounterOrderingPastNine() {
        // In string comparison: "1000-10-nodeA" < "1000-2-nodeA" (due to '1' < '2')
        // In HlcComparator: 10 > 2 numerically!
        val ts2 = "1000-2-nodeA"
        val ts10 = "1000-10-nodeA"

        assertTrue(
            HlcComparator.compare(ts10, ts2) > 0,
            "Logical counter 10 must be numerically greater than logical counter 2"
        )
        assertTrue(
            HlcComparator.compare(ts2, ts10) < 0,
            "Logical counter 2 must be numerically smaller than logical counter 10"
        )
    }

    @Test
    fun testPhysicalTimeDigitLengthOrdering() {
        // 9-digit vs 10-digit epoch milliseconds
        val tsOld = "999999999-0-nodeA"
        val tsNew = "1000000000-0-nodeA"

        assertTrue(
            HlcComparator.compare(tsNew, tsOld) > 0,
            "Physical time 1000000000 must be greater than 999999999"
        )
    }

    @Test
    fun testNodeIdTieBreaker() {
        val tsA = "1000-0-nodeA"
        val tsB = "1000-0-nodeB"

        assertTrue(
            HlcComparator.compare(tsB, tsA) > 0,
            "nodeB must act as tie-breaker over nodeA when physical and logical times match"
        )
        assertEquals(0, HlcComparator.compare(tsA, tsA))
    }

    @Test
    fun testFifteenRapidMutationsInSameMillisecond() {
        val list = (0..15).map { counter -> "1700000000000-$counter-nodeA" }
        val shuffled = list.shuffled()
        val sorted = shuffled.sortedWith(HlcComparator)

        assertEquals(list, sorted, "HlcComparator must strictly sort counters 0 through 15 in ascending order")
    }

    @Test
    fun testExtractNodeId() {
        assertEquals("nodeA", HlcComparator.extractNodeId("1700000000000-0-nodeA"))
        assertEquals("posko-merapi-1", HlcComparator.extractNodeId("1700000000000-42-posko-merapi-1"))
        assertNull(HlcComparator.extractNodeId("malformed"))
    }
}
