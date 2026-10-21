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

package io.airdb.adapter.aerolog

import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MutationType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

class AeroLogPerformanceTest {

    @Test
    @DisplayName("Measure throughput and latency statistics for AeroLog storage engine")
    fun testAeroLogEnginePerformanceStats(@TempDir tempDir: Path) = runBlocking {
        val adapter = AeroLogAirDbAdapter.open(tempDir.toFile(), "perf-node")
        val recordCount = 2_000
        val payload = "{\"device\":\"sensor-01\",\"temp\":25.4,\"humidity\":60.2,\"status\":\"ACTIVE\"}".toByteArray()

        // 1. Measure Batch Ingestion Throughput
        val batchRecords = (1..recordCount).map { i ->
            ChangeRecord(
                id = "doc-$i",
                collection = "telemetry",
                type = MutationType.INSERT,
                payload = payload,
                hlcTimestamp = "1700000000000-$i-perf-node"
            )
        }

        val batchWriteTimeMs = measureTimeMillis {
            adapter.applyChanges(batchRecords)
        }
        val batchWriteOps = (recordCount * 1000.0) / batchWriteTimeMs

        // 2. Measure Point Read Latency and Throughput
        val sampleKeys = (1..500).map { "doc-$it" }
        val readTotalNanos = measureNanoTime {
            for (key in sampleKeys) {
                adapter.getActiveRecord("telemetry", key)
            }
        }
        val avgReadLatencyMicros = (readTotalNanos / sampleKeys.size) / 1_000.0
        val readOps = (sampleKeys.size * 1_000_000_000.0) / readTotalNanos

        // 3. Measure Delta Scan Throughput
        val scanTimeMs = measureTimeMillis {
            adapter.readChangelogSince(null, limit = recordCount)
        }
        val scanOps = (recordCount * 1000.0) / scanTimeMs

        // 4. Calculate Storage Metrics
        val diskSizeBytes = tempDir.toFile().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val bytesPerRecord = diskSizeBytes.toDouble() / recordCount

        println("------------------------------------------------------------------")
        println("AeroLog Native Storage Engine - Performance Statistics Report")
        println("------------------------------------------------------------------")
        println("  Dataset Size        : $recordCount records (${payload.size} bytes payload)")
        println("  Batch Write Time    : $batchWriteTimeMs ms (%.0f records/sec)".format(batchWriteOps))
        println("  Point Read Ops      : %.0f reads/sec (avg latency: %.2f us)".format(readOps, avgReadLatencyMicros))
        println("  Delta Scan Ops      : %.0f records/sec".format(scanOps))
        println("  Total Disk Footprint: %.2f KB (%.1f bytes/record)".format(diskSizeBytes / 1024.0, bytesPerRecord))
        println("------------------------------------------------------------------")

        adapter.close()

        // Assert healthy baseline performance thresholds
        assertTrue(batchWriteOps > 200.0, "Batch write throughput should exceed 200 ops/sec (actual: $batchWriteOps)")
        assertTrue(readOps > 500.0, "Point read throughput should exceed 500 ops/sec (actual: $readOps)")
        assertTrue(scanOps > 5_000.0, "Delta scan throughput should exceed 5,000 ops/sec (actual: $scanOps)")
    }
}
