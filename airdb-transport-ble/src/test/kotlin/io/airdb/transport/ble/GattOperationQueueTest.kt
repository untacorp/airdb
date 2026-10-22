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

package io.airdb.transport.ble

import io.airdb.transport.ble.queue.GattOperationQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class GattOperationQueueTest {

    @Test
    fun testStrictSingleFlightExecution() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val queue = GattOperationQueue(testScope, retryDelayMs = 10L)
        val inFlightCounter = AtomicInteger(0)
        val executedOps = mutableListOf<Int>()

        fun makeOp(id: Int) = queue.enqueue("link-1", byteArrayOf(id.toByte()), false) {
            val count = inFlightCounter.incrementAndGet()
            assertTrue(count == 1, "There should be at most 1 in-flight operation at a time")
            executedOps.add(id)
            true
        }

        makeOp(1)
        makeOp(2)
        makeOp(3)

        assertEquals(listOf(1), executedOps)
        assertEquals(3, queue.pendingCount("link-1"))

        inFlightCounter.decrementAndGet()
        queue.onOperationComplete("link-1", isSuccess = true)

        assertEquals(listOf(1, 2), executedOps)
        assertEquals(2, queue.pendingCount("link-1"))

        inFlightCounter.decrementAndGet()
        queue.onOperationComplete("link-1", isSuccess = true)

        assertEquals(listOf(1, 2, 3), executedOps)
        assertEquals(1, queue.pendingCount("link-1"))

        inFlightCounter.decrementAndGet()
        queue.onOperationComplete("link-1", isSuccess = true)

        assertEquals(0, queue.pendingCount("link-1"))
    }

    @Test
    fun testFailingExecutionDropsAfterMaxRetries() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val queue = GattOperationQueue(testScope, retryDelayMs = 10L, maxRetries = 2)
        val op1Attempts = AtomicInteger(0)
        val op2Attempts = AtomicInteger(0)

        queue.enqueue("link-1", byteArrayOf(1), false) {
            op1Attempts.incrementAndGet()
            false
        }

        queue.enqueue("link-1", byteArrayOf(2), false) {
            op2Attempts.incrementAndGet()
            true
        }

        assertEquals(1, op1Attempts.get())
        assertEquals(0, op2Attempts.get())

        testScheduler.advanceTimeBy(15L)
        assertEquals(2, op1Attempts.get())
        assertEquals(0, op2Attempts.get())

        testScheduler.advanceTimeBy(15L)
        assertEquals(3, op1Attempts.get())

        assertEquals(1, op2Attempts.get(), "Op 2 should be dispatched after Op 1 exhausts retries")
        assertEquals(1, queue.pendingCount("link-1"))
    }

    @Test
    fun testWatchdogTimeoutUnfreezesQueueWhenCallbackNeverFires() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val queue = GattOperationQueue(testScope, retryDelayMs = 10L, maxRetries = 0, operationTimeoutMs = 50L)
        val executedOps = mutableListOf<Int>()

        queue.enqueue("link-1", byteArrayOf(1), false) {
            executedOps.add(1)
            true
        }

        queue.enqueue("link-1", byteArrayOf(2), false) {
            executedOps.add(2)
            true
        }

        assertEquals(listOf(1), executedOps)
        assertEquals(2, queue.pendingCount("link-1"))

        testScheduler.advanceTimeBy(49L)
        assertEquals(listOf(1), executedOps)

        testScheduler.advanceTimeBy(2L)

        assertEquals(listOf(1, 2), executedOps, "Op 2 must be dispatched after Op 1 times out")
        assertEquals(1, queue.pendingCount("link-1"))
    }
}
