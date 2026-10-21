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

package io.airdb.crypto

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

/**
 * HMAC-based Extract-and-Expand Key Derivation Function (HKDF) using SHA-256 per RFC 5869.
 */
object HkdfSha256 {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    /**
     * HKDF-Extract(salt, IKM) -> PRK
     */
    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(actualSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand(PRK, info, L) -> OKM
     */
    fun expand(prk: ByteArray, info: ByteArray? = null, length: Int = 32): ByteArray {
        require(length <= 255 * HASH_LEN) { "Requested length $length exceeds HKDF limit" }

        val actualInfo = info ?: ByteArray(0)
        val n = ceil(length.toDouble() / HASH_LEN).toInt()
        val okm = ByteArrayOutputStream()

        var previousT = ByteArray(0)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        for (i in 1..n) {
            mac.reset()
            mac.update(previousT)
            mac.update(actualInfo)
            mac.update(i.toByte())
            previousT = mac.doFinal()
            okm.write(previousT)
        }

        return okm.toByteArray().copyOfRange(0, length)
    }

    /**
     * Derives a cryptographic key from user/cluster secret material.
     *
     * @param secret Initial secret material (e.g. passphrase, shared secret).
     * @param salt Optional salt value (random 16-32 bytes recommended).
     * @param info Context and application specific information string.
     * @param length Desired output key length in bytes (default 32 for ChaCha20-Poly1305).
     */
    fun deriveKey(
        secret: ByteArray,
        salt: ByteArray? = null,
        info: ByteArray? = "airdb-mesh-key-v1".toByteArray(Charsets.UTF_8),
        length: Int = 32
    ): ByteArray {
        val prk = extract(salt, secret)
        return expand(prk, info, length)
    }
}
