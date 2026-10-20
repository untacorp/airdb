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

package io.airdb.transport.ble.queue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Encapsulates a pending GATT write or notification operation.
 */
data class PendingGattOperation(
    val data: ByteArray,
    val isNotification: Boolean,
    var retryCount: Int = 0,
    val execute: () -> Boolean
)

/**
 * Thread-safe single-flight GATT queue manager.
 *
 * Guarantees that at most ONE GATT operation is active per physical link at any instant.
 * Triggers subsequent writes only upon receiving confirmation callbacks from the OS driver.
 */
class GattOperationQueue(
    private val scope: CoroutineScope,
    private val maxQueueSize: Int = 256,
    private val maxBytesPerLink: Int = 1_048_576,
    private val retryDelayMs: Long = 15L,
    private val maxRetries: Int = 3
) {

    private class LinkState {
        val pending = ArrayDeque<PendingGattOperation>()
        var pendingBytes = 0
        var inFlight = false
        var retryScheduled = false
    }

    private val lock = Any()
    private val linkStates = mutableMapOf<String, LinkState>()

    /**
     * Enqueues a GATT operation for the specified link.
     *
     * @return True if admitted to the queue; false if rejected due to buffer capacity overflow.
     */
    fun enqueue(
        linkId: String,
        data: ByteArray,
        isNotification: Boolean,
        execute: () -> Boolean
    ): Boolean {
        val startNow = synchronized(lock) {
            val state = linkStates.getOrPut(linkId) { LinkState() }

            if (state.pending.size >= maxQueueSize || state.pendingBytes + data.size > maxBytesPerLink) {
                return false
            }

            state.pending.addLast(PendingGattOperation(data, isNotification, 0, execute))
            state.pendingBytes += data.size

            if (!state.inFlight && !state.retryScheduled) {
                state.inFlight = true
                true
            } else {
                false
            }
        }

        if (startNow) {
            dispatchHead(linkId)
        }
        return true
    }

    private fun dispatchHead(linkId: String) {
        val op = synchronized(lock) {
            linkStates[linkId]?.pending?.peekFirst()
        } ?: return

        val accepted = try {
            op.execute()
        } catch (_: Exception) {
            false
        }

        if (!accepted) {
            scheduleRetry(linkId)
        }
    }

    private fun scheduleRetry(linkId: String) {
        val doSchedule = synchronized(lock) {
            val state = linkStates[linkId] ?: return
            state.inFlight = false
            if (state.retryScheduled || state.pending.isEmpty()) false else {
                state.retryScheduled = true
                true
            }
        }

        if (doSchedule) {
            scope.launch {
                delay(retryDelayMs)
                val canStart = synchronized(lock) {
                    val state = linkStates[linkId] ?: return@synchronized false
                    state.retryScheduled = false
                    if (!state.inFlight && state.pending.isNotEmpty()) {
                        state.inFlight = true
                        true
                    } else false
                }
                if (canStart) {
                    dispatchHead(linkId)
                }
            }
        }
    }

    /**
     * Called by Bluetooth driver callbacks (onCharacteristicWrite / onNotificationSent).
     */
    fun onOperationComplete(linkId: String, isSuccess: Boolean) {
        var startNext = false

        synchronized(lock) {
            val state = linkStates[linkId] ?: return
            val head = state.pending.peekFirst() ?: run {
                linkStates.remove(linkId)
                return
            }

            state.inFlight = false

            if (!isSuccess && head.retryCount < maxRetries) {
                head.retryCount++
                state.retryScheduled = true
                scope.launch {
                    delay(retryDelayMs)
                    val retryNow = synchronized(lock) {
                        state.retryScheduled = false
                        if (!state.inFlight && state.pending.isNotEmpty()) {
                            state.inFlight = true
                            true
                        } else false
                    }
                    if (retryNow) dispatchHead(linkId)
                }
                return
            } else {
                // Operation completed or exhausted retries
                state.pending.pollFirst()
                state.pendingBytes -= head.data.size

                if (state.pending.isNotEmpty()) {
                    state.inFlight = true
                    startNext = true
                } else {
                    linkStates.remove(linkId)
                }
            }
        }

        if (startNext) {
            dispatchHead(linkId)
        }
    }

    /**
     * Purges queued operations when a physical link disconnects.
     */
    fun onLinkDisconnected(linkId: String) {
        synchronized(lock) {
            linkStates.remove(linkId)
        }
    }

    /**
     * Diagnostic: returns current pending operation count for a link.
     */
    fun pendingCount(linkId: String): Int {
        synchronized(lock) {
            return linkStates[linkId]?.pending?.size ?: 0
        }
    }
}
