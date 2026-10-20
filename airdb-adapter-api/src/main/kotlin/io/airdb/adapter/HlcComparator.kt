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

/**
 * Total ordering comparator for Hybrid Logical Clock (HLC) timestamp strings.
 *
 * Compares timestamps according to the canonical HLC total order:
 * 1. Physical time (Long, milliseconds since epoch)
 * 2. Logical counter (Int, within the same millisecond)
 * 3. Node ID (String, deterministic tie-breaker)
 *
 * If either string cannot be parsed as a valid HLC ("pt-lc-nodeId"), falls back to
 * standard lexicographic string comparison to prevent runtime exceptions.
 */
object HlcComparator : Comparator<String> {

    override fun compare(a: String?, b: String?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1

        val parsedA = parseHlcComponents(a)
        val parsedB = parseHlcComponents(b)

        if (parsedA != null && parsedB != null) {
            val (ptA, lcA, nodeA) = parsedA
            val (ptB, lcB, nodeB) = parsedB

            if (ptA != ptB) return ptA.compareTo(ptB)
            if (lcA != lcB) return lcA.compareTo(lcB)
            return nodeA.compareTo(nodeB)
        }

        if (parsedA != null) return 1
        if (parsedB != null) return -1
        return a.compareTo(b)
    }

    /**
     * Extracts the originating node identifier from an HLC timestamp string.
     */
    fun extractNodeId(hlcTimestamp: String): String? {
        val firstDash = hlcTimestamp.indexOf('-')
        if (firstDash <= 0) return null
        val secondDash = hlcTimestamp.indexOf('-', firstDash + 1)
        if (secondDash <= firstDash + 1 || secondDash >= hlcTimestamp.length - 1) return null
        return hlcTimestamp.substring(secondDash + 1)
    }

    /**
     * Decomposes a canonical HLC timestamp string into its physical time (ms),
     * logical counter, and originating node ID components.
     */
    fun parseHlcComponents(str: String): Triple<Long, Int, String>? {
        val firstDash = str.indexOf('-')
        if (firstDash <= 0) return null
        val secondDash = str.indexOf('-', firstDash + 1)
        if (secondDash <= firstDash + 1 || secondDash >= str.length - 1) return null

        val ptStr = str.substring(0, firstDash)
        val lcStr = str.substring(firstDash + 1, secondDash)
        val nodeStr = str.substring(secondDash + 1)

        val pt = ptStr.toLongOrNull() ?: return null
        val lc = lcStr.toIntOrNull() ?: return null
        return Triple(pt, lc, nodeStr)
    }
}
