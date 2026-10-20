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

import Foundation

/// Manages fragment reassembly with TTL expiration.
public final class AssemblyBuffer: @unchecked Sendable {
    private struct InFlightAssembly {
        let fragmentId: Int64
        let total: Int
        var fragments: [Data?]
        var receivedCount: Int = 0
        let createdAt: TimeInterval = Date().timeIntervalSince1970
    }

    private let ttlSeconds: TimeInterval
    private let maxConcurrentAssemblies: Int
    private let lock = NSLock()
    private var assemblies: [Int64: InFlightAssembly] = [:]

    public init(ttlSeconds: TimeInterval = 30.0, maxConcurrentAssemblies: Int = 128) {
        self.ttlSeconds = ttlSeconds
        self.maxConcurrentAssemblies = maxConcurrentAssemblies
    }

    /// Ingests an incoming fragment packet. Returns the reassembled AirDbPacket once all chunks arrive.
    public func ingest(_ fragmentPacket: AirDbPacket) -> AirDbPacket? {
        guard let (header, chunk) = FragmentHeader.decode(fragmentPacket.payload) else {
            return nil
        }

        // Validate index boundary
        guard header.index < header.total else { return nil }

        lock.lock()
        defer { lock.unlock() }

        cleanExpired()

        var assembly = assemblies[header.fragmentId] ?? {
            if assemblies.count >= maxConcurrentAssemblies {
                // Evict oldest
                if let oldestKey = assemblies.min(by: { $0.value.createdAt < $1.value.createdAt })?.key {
                    assemblies.removeValue(forKey: oldestKey)
                }
            }
            return InFlightAssembly(
                fragmentId: header.fragmentId,
                total: header.total,
                fragments: Array(repeating: nil, count: header.total)
            )
        }()

        if assembly.fragments[header.index] == nil {
            assembly.fragments[header.index] = chunk
            assembly.receivedCount += 1

            if assembly.receivedCount == assembly.total {
                assemblies.removeValue(forKey: header.fragmentId)

                var fullData = Data()
                for part in assembly.fragments {
                    guard let p = part else { return nil }
                    fullData.append(p)
                }
                return BinaryCodec.decode(fullData)
            } else {
                assemblies[header.fragmentId] = assembly
            }
        }

        return nil
    }

    /// Convenience alias matching labeled argument convention.
    public func ingest(packet: AirDbPacket) -> AirDbPacket? {
        ingest(packet)
    }

    private func cleanExpired() {
        let now = Date().timeIntervalSince1970
        let expiredKeys = assemblies.filter { now - $0.value.createdAt > ttlSeconds }.map { $0.key }
        for key in expiredKeys {
            assemblies.removeValue(forKey: key)
        }
    }

    public func activeAssemblyCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return assemblies.count
    }
}
