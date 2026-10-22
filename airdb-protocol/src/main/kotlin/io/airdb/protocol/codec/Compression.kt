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

package io.airdb.protocol.codec

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Standard zlib/Deflate compression utility for packet payloads.
 */
object Compression {

    /**
     * Maximum decompressed size threshold (64 KB) to mitigate zip-bomb attacks over the BLE mesh.
     */
    const val MAX_DECOMPRESSED_SIZE = 65535

    /**
     * Compresses the raw input byte array using Deflate with best compression level.
     *
     * @param input Raw uncompressed bytes.
     * @return Deflated byte array.
     */
    fun compress(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()

        val outputStream = ByteArrayOutputStream(input.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        deflater.end()
        return outputStream.toByteArray()
    }

    /**
     * Decompresses Deflate-compressed bytes while strictly enforcing a maximum size threshold.
     *
     * @param input Deflated byte array.
     * @param maxDecompressedSize Maximum allowable size of the decompressed output in bytes.
     * @return Inflated raw byte array.
     * @throws IllegalArgumentException If decompressed payload exceeds [maxDecompressedSize].
     */
    fun decompress(input: ByteArray, maxDecompressedSize: Int = MAX_DECOMPRESSED_SIZE): ByteArray {
        val inflater = Inflater()
        inflater.setInput(input)

        val outputStream = ByteArrayOutputStream(minOf(input.size * 2, maxDecompressedSize))
        val buffer = ByteArray(1024)
        var totalInflated = 0

        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                totalInflated += count
                if (totalInflated > maxDecompressedSize) {
                    throw IllegalArgumentException("Decompressed payload exceeds max allowed size of $maxDecompressedSize bytes")
                }
                outputStream.write(buffer, 0, count)
            }
        } finally {
            inflater.end()
        }
        return outputStream.toByteArray()
    }
}
