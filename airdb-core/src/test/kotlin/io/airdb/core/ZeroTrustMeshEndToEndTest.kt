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

package io.airdb.core

import io.airdb.adapter.ChangeRecord
import io.airdb.adapter.MemoryDbAdapter
import io.airdb.adapter.MutationType
import io.airdb.core.engine.AirDbEngine
import io.airdb.core.model.DeltaBatch
import io.airdb.crypto.Ed25519Signer
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.protocol.packet.PacketType
import io.airdb.transport.sim.SimulatedNetwork
import io.airdb.transport.sim.SimulatedTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZeroTrustMeshEndToEndTest {

    private lateinit var network: SimulatedNetwork
    private lateinit var adapterA: MemoryDbAdapter
    private lateinit var adapterB: MemoryDbAdapter
    private lateinit var engineA: AirDbEngine
    private lateinit var engineB: AirDbEngine

    private val keyPairA = Ed25519Signer.generateKeyPair()
    private val keyPairB = Ed25519Signer.generateKeyPair()
    private val clusterKey = ByteArray(32) { (it * 7).toByte() }

    @BeforeEach
    fun setUp() {
        network = SimulatedNetwork()
        adapterA = MemoryDbAdapter()
        adapterB = MemoryDbAdapter()

        val transportA = SimulatedTransport("node-a", network)
        val transportB = SimulatedTransport("node-b", network)

        engineA = AirDbEngine(
            nodeName = "node-a",
            adapter = adapterA,
            transport = transportA,
            clusterKey = clusterKey,
            authorKeyPair = keyPairA,
            enforceSignatures = true
        )

        engineB = AirDbEngine(
            nodeName = "node-b",
            adapter = adapterB,
            transport = transportB,
            clusterKey = clusterKey,
            authorKeyPair = keyPairB,
            enforceSignatures = true
        )
    }

    @AfterEach
    fun tearDown() {
        engineA.stop()
        engineB.stop()
    }

    @Test
    fun testAuthenticSignedRecordsSyncAndConverge() = runBlocking {
        val latch = CountDownLatch(1)
        engineB.addSyncListener { peerId, count ->
            if (peerId == "node-a" && count >= 1) {
                latch.countDown()
            }
        }

        engineA.start()
        engineB.start()
        delay(300)

        // Commit signed record on Node A
        val recordA = engineA.saveRecord(
            collection = "triage",
            id = "survivor-101",
            type = MutationType.INSERT,
            payload = """{"name":"Jane Doe","triage":"YELLOW"}""".toByteArray(Charsets.UTF_8)
        )
        assertTrue(recordA.isSigned)

        assertTrue(
            latch.await(3, TimeUnit.SECONDS),
            "Signed record from Node A should sync to Node B"
        )

        val entityOnB = adapterB.getRecord("triage", "survivor-101")
        assertNotNull(entityOnB)
        assertEquals("""{"name":"Jane Doe","triage":"YELLOW"}""", String(entityOnB.payload, Charsets.UTF_8))
    }

    @Test
    fun testAdversarialForgedRecordRejectedWhenSignaturesEnforced() = runBlocking {
        engineA.start()
        engineB.start()
        delay(300)

        // Rogue attacker node sends counterfeit record claiming to be from doctorA but with forged signature
        val rogueKeyPair = Ed25519Signer.generateKeyPair()
        val maliciousRecord = ChangeRecord(
            id = "patient-evil",
            collection = "triage",
            type = MutationType.INSERT,
            payload = """{"status":"TAMPERED_BY_ROGUE"}""".toByteArray(Charsets.UTF_8),
            hlcTimestamp = "100-0-doctorA",
            authorPublicKey = keyPairA.publicKeyRaw, // Claims to be Node A
            signature = rogueKeyPair.privateKeyRaw.copyOf(64) // Forged signature
        )

        val rogueTransport = SimulatedTransport("rogue-node", network)
        rogueTransport.start()
        delay(200)

        val batchPayload = DeltaBatch.serialize(listOf(maliciousRecord))
        val forgedPacket = AirDbPacket(
            type = PacketType.DELTA_BATCH,
            senderId = 9999L,
            payload = batchPayload
        )

        rogueTransport.send(forgedPacket, targetPeerId = "node-b")
        delay(500)

        // Verify that Node B rejected the forged mutation
        val rejectedRecord = adapterB.getRecord("triage", "patient-evil")
        assertNull(rejectedRecord, "Forged record with invalid signature must NOT be applied to database")
    }
}
