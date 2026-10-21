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

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HkdfSha256Test {

    @Test
    fun testKeyDerivationDeterministic() {
        val passphrase = "disaster-response-alpha-team-key".toByteArray(Charsets.UTF_8)
        val salt = "regional-cluster-salt-2026".toByteArray(Charsets.UTF_8)

        val key1 = HkdfSha256.deriveKey(passphrase, salt)
        val key2 = HkdfSha256.deriveKey(passphrase, salt)

        assertEquals(32, key1.size)
        assertContentEquals(key1, key2)
    }

    @Test
    fun testDifferentSaltsProduceDifferentKeys() {
        val passphrase = "disaster-response-alpha-team-key".toByteArray(Charsets.UTF_8)
        val salt1 = "salt-1".toByteArray(Charsets.UTF_8)
        val salt2 = "salt-2".toByteArray(Charsets.UTF_8)

        val key1 = HkdfSha256.deriveKey(passphrase, salt1)
        val key2 = HkdfSha256.deriveKey(passphrase, salt2)

        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun testNullSaltDerivation() {
        val passphrase = "my-secret-passphrase".toByteArray(Charsets.UTF_8)
        val key = HkdfSha256.deriveKey(passphrase, null)
        assertEquals(32, key.size)
    }
}
