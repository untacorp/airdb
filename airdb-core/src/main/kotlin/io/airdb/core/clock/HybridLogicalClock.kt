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

        fun parse(str: String): HlcTimestamp {
            val parts = str.split("-")
            require(parts.size >= 3) { "Invalid HLC timestamp format: $str" }
            val physical = parts[0].toLong()
            val logical = parts[1].toInt()
            val node = parts.subList(2, parts.size).joinToString("-")
            return HlcTimestamp(physical, logical, node)
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

    private var latest = HlcTimestamp(0L, 0, nodeId)

    /**
     * Generates a new monotonically increasing timestamp for a local database mutation.
     */
    @Synchronized
    fun now(): HlcTimestamp {
        val pt = timeProvider()
        val nextPt = maxOf(latest.physicalTime, pt)
        val nextLogical = if (nextPt == latest.physicalTime) latest.logicalCounter + 1 else 0

        latest = HlcTimestamp(nextPt, nextLogical, nodeId)
        return latest
    }

    /**
     * Advances local HLC clock upon observing an incoming remote HLC timestamp from a peer.
     */
    @Synchronized
    fun update(remote: HlcTimestamp): HlcTimestamp {
        val pt = timeProvider()
        val nextPt = maxOf(maxOf(latest.physicalTime, remote.physicalTime), pt)

        val nextLogical = when {
            nextPt == latest.physicalTime && nextPt == remote.physicalTime ->
                maxOf(latest.logicalCounter, remote.logicalCounter) + 1
            nextPt == latest.physicalTime -> latest.logicalCounter + 1
            nextPt == remote.physicalTime -> remote.logicalCounter + 1
            else -> 0
        }

        latest = HlcTimestamp(nextPt, nextLogical, nodeId)
        return latest
    }

    @Synchronized
    fun current(): HlcTimestamp = latest
}
