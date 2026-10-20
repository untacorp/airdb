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

package io.airdb.core

import io.airdb.core.clock.HlcTimestamp
import io.airdb.core.clock.HybridLogicalClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HybridLogicalClockTest {

    @Test
    fun testMonotonicGenerationOnSameTick() {
        var mockedPhysicalTime = 1000L
        val clock = HybridLogicalClock("node-1") { mockedPhysicalTime }

        val t1 = clock.now()
        val t2 = clock.now()
        val t3 = clock.now()

        assertEquals(1000L, t1.physicalTime)
        assertEquals(0, t1.logicalCounter)

        assertEquals(1000L, t2.physicalTime)
        assertEquals(1, t2.logicalCounter)

        assertEquals(1000L, t3.physicalTime)
        assertEquals(2, t3.logicalCounter)

        assertTrue(t2 > t1)
        assertTrue(t3 > t2)
    }

    @Test
    fun testUpdateWithFutureRemoteTimestamp() {
        var localTime = 1000L
        val clock = HybridLogicalClock("node-1") { localTime }

        // Local generates a timestamp
        val tLocal = clock.now()
        assertEquals(1000L, tLocal.physicalTime)

        // Receive remote timestamp from the future (e.g. peer clock is at 1500L)
        val tRemote = HlcTimestamp(1500L, 5, "node-2")
        val advanced = clock.update(tRemote)

        assertEquals(1500L, advanced.physicalTime)
        assertEquals(6, advanced.logicalCounter)
        assertEquals("node-1", advanced.nodeId)

        // Next local tick should be >= 1500L even if local wall clock is still 1000L
        val tNext = clock.now()
        assertEquals(1500L, tNext.physicalTime)
        assertEquals(7, tNext.logicalCounter)
    }

    @Test
    fun testHlcParseRoundtrip() {
        val original = HlcTimestamp(1710928300000L, 42, "posko-merapi-1")
        val parsed = HlcTimestamp.parse(original.toString())
        assertEquals(original, parsed)
    }
}
