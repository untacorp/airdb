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

package io.airdb.protocol.fragment

import io.airdb.protocol.codec.BinaryCodec
import io.airdb.protocol.packet.AirDbPacket
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages in-flight fragment assembly with TTL expiration to prevent memory leaks.
 */
class AssemblyBuffer(
    private val ttlMs: Long = 30_000L,
    private val maxConcurrentAssemblies: Int = 128
) {

    private data class InFlightAssembly(
        val fragmentId: Long,
        val total: Int,
        val fragments: Array<ByteArray?>,
        var receivedCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val assemblies = ConcurrentHashMap<Long, InFlightAssembly>()

    /**
     * Ingests an incoming fragment packet. If this fragment completes the packet,
     * returns the reassembled [AirDbPacket]; otherwise returns null.
     */
    fun ingest(fragmentPacket: AirDbPacket): AirDbPacket? {
        val decoded = FragmentHeader.decode(fragmentPacket.payload) ?: return null
        val (header, chunk) = decoded

        cleanExpired()

        synchronized(assemblies) {
            val assembly = assemblies.getOrPut(header.fragmentId) {
                if (assemblies.size >= maxConcurrentAssemblies) {
                    // Evict oldest
                    val oldestKey = assemblies.minByOrNull { it.value.createdAt }?.key
                    if (oldestKey != null) assemblies.remove(oldestKey)
                }
                InFlightAssembly(header.fragmentId, header.total, arrayOfNulls(header.total))
            }

            if (header.index < assembly.total && assembly.fragments[header.index] == null) {
                assembly.fragments[header.index] = chunk
                assembly.receivedCount++

                if (assembly.receivedCount == assembly.total) {
                    assemblies.remove(header.fragmentId)

                    val output = ByteArrayOutputStream()
                    for (part in assembly.fragments) {
                        if (part == null) return null // Safety check
                        output.write(part)
                    }

                    val fullData = output.toByteArray()
                    return BinaryCodec.decode(fullData)
                }
            }
        }

        return null
    }

    private fun cleanExpired() {
        val now = System.currentTimeMillis()
        val expired = assemblies.filterValues { now - it.createdAt > ttlMs }.keys
        expired.forEach { assemblies.remove(it) }
    }

    /**
     * Returns count of currently active fragment assemblies.
     */
    fun activeAssemblyCount(): Int = assemblies.size
}
