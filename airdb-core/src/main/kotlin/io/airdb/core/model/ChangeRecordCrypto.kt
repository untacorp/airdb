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

package io.airdb.core.model

import io.airdb.adapter.ChangeRecord
import io.airdb.crypto.Ed25519KeyPair
import io.airdb.crypto.Ed25519Signer

/**
 * Signs a [ChangeRecord] using the provided author Ed25519 key pair.
 */
fun ChangeRecord.sign(keyPair: Ed25519KeyPair): ChangeRecord {
    val digest = Ed25519Signer.canonicalChangeDigest(
        id = id,
        collection = collection,
        typeOrdinal = type.ordinal.toByte(),
        hlcTimestamp = hlcTimestamp,
        payload = payload
    )
    val sig = Ed25519Signer.sign(digest, keyPair.privateKeyRaw)
    return copy(
        authorPublicKey = keyPair.publicKeyRaw,
        signature = sig
    )
}

/**
 * Verifies the authenticity of this [ChangeRecord] using its embedded Ed25519 signature and public key.
 */
fun ChangeRecord.verifySignature(): Boolean {
    val pubKey = authorPublicKey ?: return false
    val sig = signature ?: return false
    val digest = Ed25519Signer.canonicalChangeDigest(
        id = id,
        collection = collection,
        typeOrdinal = type.ordinal.toByte(),
        hlcTimestamp = hlcTimestamp,
        payload = payload
    )
    return Ed25519Signer.verify(digest, sig, pubKey)
}
