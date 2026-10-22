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

package io.airdb.core.clock

import java.util.concurrent.atomic.AtomicReference

/**
 * Monotonically increasing Hybrid Logical Clock (HLC) timestamp.
 *
 * Guarantees causality and total ordering across distributed peers without NTP synchronization.
 */
data class HlcTimestamp(
    val physicalTime: Long,
    val logicalCounter: Int,
    val nodeId: String
) : Comparable<HlcTimestamp> {

    override fun compareTo(other: HlcTimestamp): Int {
        if (physicalTime != other.physicalTime) return physicalTime.compareTo(other.physicalTime)
        if (logicalCounter != other.logicalCounter) return logicalCounter.compareTo(other.logicalCounter)
        return nodeId.compareTo(other.nodeId)
    }

    override fun toString(): String = "$physicalTime-$logicalCounter-$nodeId"

    companion object {
        val ZERO = HlcTimestamp(0L, 0, "")

        fun parseOrNull(str: String): HlcTimestamp? {
            val firstDash = str.indexOf('-')
            if (firstDash <= 0) return null
            val secondDash = str.indexOf('-', firstDash + 1)
            if (secondDash <= firstDash + 1 || secondDash >= str.length - 1) return null

            val pt = str.substring(0, firstDash).toLongOrNull() ?: return null
            val lc = str.substring(firstDash + 1, secondDash).toIntOrNull() ?: return null
            val node = str.substring(secondDash + 1)
            return HlcTimestamp(pt, lc, node)
        }

        fun parse(str: String): HlcTimestamp {
            return parseOrNull(str) ?: throw IllegalArgumentException("Invalid HLC timestamp format: $str")
        }
    }
}

/**
 * Thread-safe Hybrid Logical Clock generator.
 */
class HybridLogicalClock(
    val nodeId: String,
    private val timeProvider: () -> Long = System::currentTimeMillis
) {

    private val latest = AtomicReference(HlcTimestamp(0L, 0, nodeId))

    /**
     * Generates a new monotonically increasing timestamp for a local database mutation.
     */
    fun now(): HlcTimestamp {
        val pt = timeProvider()
        return latest.updateAndGet { current ->
            val nextPt = maxOf(current.physicalTime, pt)
            val nextLogical = if (nextPt == current.physicalTime) current.logicalCounter + 1 else 0
            HlcTimestamp(nextPt, nextLogical, nodeId)
        }
    }

    /**
     * Advances local HLC clock upon observing an incoming remote HLC timestamp from a peer.
     */
    fun update(remote: HlcTimestamp): HlcTimestamp {
        val pt = timeProvider()
        return latest.updateAndGet { current ->
            val nextPt = maxOf(maxOf(current.physicalTime, remote.physicalTime), pt)

            val nextLogical = when {
                nextPt == current.physicalTime && nextPt == remote.physicalTime ->
                    maxOf(current.logicalCounter, remote.logicalCounter) + 1
                nextPt == current.physicalTime -> current.logicalCounter + 1
                nextPt == remote.physicalTime -> remote.logicalCounter + 1
                else -> 0
            }

            HlcTimestamp(nextPt, nextLogical, nodeId)
        }
    }

    fun current(): HlcTimestamp = latest.get()
}
