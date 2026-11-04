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

import io.airdb.adapter.MutationType
import io.airdb.adapter.sqlite.SqliteDbAdapter
import io.airdb.core.engine.AirDbEngine
import io.airdb.protocol.packet.AirDbPacket
import io.airdb.transport.AirDbTransport
import io.airdb.transport.TransportEvent
import io.airdb.transport.TransportListener
import io.airdb.transport.ble.BleTransportFactory
import io.airdb.transport.ble.LinuxBleTransport
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Scanner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private data class PeerEntry(val address: String, var rssi: Int, var lastSeenMs: Long)
private data class LinkEntry(val linkId: String, val address: String, val isCentral: Boolean)

fun main(args: Array<String>) = runBlocking {
    val nodeName = args.getOrNull(0) ?: "node-${System.currentTimeMillis() % 1000}"
    val dataDir = File("build/cli-data")
    if (!dataDir.exists()) dataDir.mkdirs()
    val dbFile = File(dataDir, "$nodeName.sqlite")

    val peers = ConcurrentHashMap<String, PeerEntry>()
    val links = ConcurrentHashMap<String, LinkEntry>()
    val rxPackets = AtomicLong(0)
    val txPackets = AtomicLong(0)

    val db = SqliteDbAdapter.open(dbFile)
    val rawTransport = try {
        BleTransportFactory.create(nodeName)
    } catch (e: Exception) {
        System.err.println("error: cannot initialize ble transport: ${e.message}")
        return@runBlocking
    }

    val transport = object : AirDbTransport by rawTransport {
        override suspend fun send(packet: AirDbPacket, targetPeerId: String?): Boolean {
            val result = rawTransport.send(packet, targetPeerId)
            if (result) {
                txPackets.incrementAndGet()
            }
            return result
        }

        override fun setListener(listener: TransportListener) {
            rawTransport.setListener(object : TransportListener {
                override fun onTransportEvent(event: TransportEvent) {
                    when (event) {
                        is TransportEvent.PeerDiscovered -> {
                            peers.compute(event.peerId) { addr, existing ->
                                if (existing != null) {
                                    existing.rssi = event.rssi
                                    existing.lastSeenMs = System.currentTimeMillis()
                                    existing
                                } else {
                                    PeerEntry(addr, event.rssi, System.currentTimeMillis())
                                }
                            }
                        }
                        is TransportEvent.LinkConnected -> {
                            links[event.linkId] = LinkEntry(
                                linkId = event.linkId,
                                address = event.peerId,
                                isCentral = event.linkId.startsWith("client-")
                            )
                            println("\n[ble] link established: ${event.linkId}")
                            printPrompt()
                        }
                        is TransportEvent.LinkDisconnected -> {
                            links.remove(event.linkId)
                            println("\n[ble] link closed: ${event.linkId}")
                            printPrompt()
                        }
                        is TransportEvent.PacketReceived -> {
                            rxPackets.incrementAndGet()
                        }
                        else -> Unit
                    }
                    listener.onTransportEvent(event)
                }
            })
        }
    }

    val engine = AirDbEngine(nodeName = nodeName, adapter = db, transport = transport)
    engine.addSyncListener { peer, count ->
        println("\n[sync] $count mutation(s) from $peer")
        printPrompt()
    }

    try {
        engine.start()
    } catch (e: Exception) {
        System.err.println("error: failed to start engine: ${e.message}")
        return@runBlocking
    }

    println("airdb 0.2.2 (node: $nodeName, db: ${dbFile.name})")
    println("type 'help' for commands.")
    printPrompt()

    val scanner = Scanner(System.`in`)
    while (scanner.hasNextLine()) {
        val line = scanner.nextLine().trim()
        if (line.isEmpty()) {
            printPrompt()
            continue
        }

        val parts = line.split("\\s+".toRegex(), limit = 4)
        val cmd = parts[0].lowercase()

        when (cmd) {
            "exit", "quit" -> {
                break
            }
            "help" -> {
                println("commands:")
                println("  put <col> <id> <data>  write/update record (creates collection automatically)")
                println("  get <col> <id>         read record by id")
                println("  del <col> <id>         delete record (replicates tombstone to peers)")
                println("  list <col>             list active records in collection")
                println("  cols                   list all collections in database")
                println("  peers                  list discovered BLE devices and signal RSSI")
                println("  links                  list active BLE Central / Peripheral connections")
                println("  radio                  show radio configuration and packet stats")
                println("  status                 show causal HLC clock and vector clock")
                println("  compact [hlc]          prune tombstoned records up to hlc threshold")
                println("  log [on|off]           toggle live BLE driver trace logging")
                println("  exit                   shutdown node and exit")
            }
            "cols", "collections" -> {
                val collections = db.getCollections()
                if (collections.isEmpty()) {
                    println("no collections found.")
                } else {
                    println("collections (${collections.size}):")
                    collections.forEach { println("  $it") }
                }
            }
            "peers" -> {
                val now = System.currentTimeMillis()
                if (peers.isEmpty()) {
                    println("no BLE peers discovered yet (scanning...)")
                } else {
                    println(String.format("%-20s %-10s %-12s %s", "PEER ADDRESS", "RSSI", "STATE", "LAST SEEN"))
                    peers.values.sortedByDescending { it.lastSeenMs }.forEach { p ->
                        val isConnected = links.values.any { it.address == p.address }
                        val status = if (isConnected) "connected" else "nearby"
                        val age = "${(now - p.lastSeenMs) / 1000}s ago"
                        println(String.format("%-20s %-10s %-12s %s", p.address, "${p.rssi} dBm", status, age))
                    }
                }
            }
            "links" -> {
                if (links.isEmpty()) {
                    println("no active BLE connections.")
                } else {
                    println(String.format("%-32s %-20s %s", "LINK ID", "PEER ADDRESS", "ROLE"))
                    links.values.forEach { l ->
                        val role = if (l.isCentral) "central (client)" else "peripheral (server)"
                        println(String.format("%-32s %-20s %s", l.linkId, l.address, role))
                    }
                }
            }
            "radio" -> {
                val linuxBle = rawTransport as? LinuxBleTransport
                val cfg = linuxBle?.config
                println("radio status:")
                println("  os driver   : Linux BlueZ (D-Bus)")
                println("  service     : ${cfg?.serviceUuid}")
                println("  char        : ${cfg?.characteristicUuid}")
                println("  target mtu  : ${cfg?.targetMtu} bytes")
                println("  chunk size  : ${cfg?.maxFragmentChunkSize} bytes")
                println("  stats       : rx ${rxPackets.get()} pkts, tx ${txPackets.get()} pkts")
            }
            "status" -> {
                println("node: ${engine.nodeName}")
                println("hlc : ${engine.clock.now()}")
                println("vc  : ${engine.vectorClock.asMap()}")
            }
            "list" -> {
                if (parts.size < 2) {
                    println("usage: list <collection>")
                } else {
                    val col = parts[1]
                    val records = db.getActiveRecords(col)
                    if (records.isEmpty()) {
                        println("collection '$col' is empty.")
                    } else {
                        println("records in '$col' (${records.size}):")
                        records.forEach { (id, r) ->
                            println("  [$id] hlc=${r.hlcTimestamp} data=${String(r.payload, Charsets.UTF_8)}")
                        }
                    }
                }
            }
            "get" -> {
                if (parts.size < 3) {
                    println("usage: get <collection> <id>")
                } else {
                    val col = parts[1]
                    val id = parts[2]
                    val record = db.getActiveRecord(col, id)
                    if (record == null) {
                        println("record '$id' not found in '$col'")
                    } else {
                        println("[$id] type=${record.type} hlc=${record.hlcTimestamp}")
                        println(String(record.payload, Charsets.UTF_8))
                    }
                }
            }
            "put" -> {
                if (parts.size < 4) {
                    println("usage: put <collection> <id> <data...>")
                } else {
                    val col = parts[1]
                    val id = parts[2]
                    val payload = parts[3].toByteArray(Charsets.UTF_8)
                    val change = engine.saveRecord(col, id, MutationType.INSERT, payload)
                    println("saved $id (hlc=${change.hlcTimestamp})")
                }
            }
            "del", "delete" -> {
                if (parts.size < 3) {
                    println("usage: del <collection> <id>")
                } else {
                    val col = parts[1]
                    val id = parts[2]
                    val change = engine.saveRecord(col, id, MutationType.DELETE, byteArrayOf())
                    println("deleted $id (tombstone hlc=${change.hlcTimestamp})")
                }
            }
            "compact" -> {
                val threshold = parts.getOrNull(1) ?: db.getLatestHlc() ?: engine.clock.now().toString()
                db.compact(threshold)
                println("compaction completed against hlc threshold $threshold")
            }
            "log" -> {
                val linuxBle = rawTransport as? LinuxBleTransport
                if (linuxBle == null) {
                    println("driver does not support trace logging.")
                } else if (parts.size < 2) {
                    println("logging is ${if (linuxBle.verbose) "on" else "off"}. usage: log on|off")
                } else {
                    val enable = parts[1].lowercase() in listOf("on", "true", "1")
                    linuxBle.verbose = enable
                    println("logging ${if (enable) "enabled" else "disabled"}")
                }
            }
            else -> {
                println("unknown command: '$cmd'. type 'help' for available commands.")
            }
        }
        printPrompt()
    }

    engine.stop()
    db.close()
    println("node stopped.")
}

private fun printPrompt() {
    print("airdb> ")
    System.out.flush()
}
