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

package io.airdb.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AirDbCliParserTest {

    @Test
    fun testPutCommandArgumentSplitting() {
        val input = "put patient p hello world with spaces"
        val parts = input.split("\\s+".toRegex(), limit = 4)
        assertEquals(4, parts.size)
        assertEquals("put", parts[0])
        assertEquals("patient", parts[1])
        assertEquals("p", parts[2])
        assertEquals("hello world with spaces", parts[3])
    }

    @Test
    fun testPutCommandWhenCollectionNameContainsId() {
        // In previous buggy version, line.substringAfter(id) matched the '1' inside 'col1'
        val input = "put col1 1 my secret payload"
        val parts = input.split("\\s+".toRegex(), limit = 4)
        assertEquals(4, parts.size)
        assertEquals("put", parts[0])
        assertEquals("col1", parts[1])
        assertEquals("1", parts[2])
        assertEquals("my secret payload", parts[3])
    }

    @Test
    fun testCompactCommandWithOptionalThreshold() {
        val inputWithThreshold = "compact 1700000000000-0-nodeA"
        val parts1 = inputWithThreshold.split("\\s+".toRegex(), limit = 4)
        assertEquals("compact", parts1[0])
        assertEquals("1700000000000-0-nodeA", parts1.getOrNull(1))

        val inputBare = "compact"
        val parts2 = inputBare.split("\\s+".toRegex(), limit = 4)
        assertEquals("compact", parts2[0])
        assertEquals(null, parts2.getOrNull(1))
    }

    @Test
    fun testDelAndGetCommandSplitting() {
        val delInput = "del inventory item-42"
        val delParts = delInput.split("\\s+".toRegex(), limit = 4)
        assertEquals(3, delParts.size)
        assertEquals("del", delParts[0])
        assertEquals("inventory", delParts[1])
        assertEquals("item-42", delParts[2])

        val getInput = "get inventory item-42"
        val getParts = getInput.split("\\s+".toRegex(), limit = 4)
        assertEquals(3, getParts.size)
        assertEquals("get", getParts[0])
        assertEquals("inventory", getParts[1])
        assertEquals("item-42", getParts[2])
    }
}
