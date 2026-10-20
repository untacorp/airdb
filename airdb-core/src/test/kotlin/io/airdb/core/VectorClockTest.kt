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

import io.airdb.core.clock.VectorClock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VectorClockTest {

    @Test
    fun testUpdateAndMerge() {
        val clockA = VectorClock()
        clockA.update("node-1", "100-0-node-1")
        clockA.update("node-2", "200-0-node-2")

        val clockB = VectorClock()
        clockB.update("node-2", "250-0-node-2")
        clockB.update("node-3", "300-0-node-3")

        clockA.merge(clockB)

        assertEquals("100-0-node-1", clockA.get("node-1"))
        assertEquals("250-0-node-2", clockA.get("node-2"))
        assertEquals("300-0-node-3", clockA.get("node-3"))
    }

    @Test
    fun testBinaryRoundtrip() {
        val original = VectorClock(
            mapOf(
                "posko-1" to "1710928300000-1-posko-1",
                "posko-2" to "1710928305000-4-posko-2",
                "posko-3" to "1710928310000-0-posko-3"
            )
        )

        val bytes = original.toBinary()
        val decoded = VectorClock.fromBinary(bytes)

        assertEquals(original.asMap(), decoded.asMap())
    }
}
