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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidBlePermissionsTest {

    @Test
    fun testPermissionsForApi31AndAbove() {
        val perms31 = AndroidBlePermissions.getRequiredPermissions(31)
        assertEquals(3, perms31.size)
        assertTrue(perms31.contains("android.permission.BLUETOOTH_SCAN"))
        assertTrue(perms31.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(perms31.contains("android.permission.BLUETOOTH_ADVERTISE"))

        val perms34 = AndroidBlePermissions.getRequiredPermissions(34)
        assertEquals(perms31, perms34)
    }

    @Test
    fun testPermissionsForApi30AndBelow() {
        val perms30 = AndroidBlePermissions.getRequiredPermissions(30)
        assertEquals(3, perms30.size)
        assertTrue(perms30.contains("android.permission.BLUETOOTH"))
        assertTrue(perms30.contains("android.permission.BLUETOOTH_ADMIN"))
        assertTrue(perms30.contains("android.permission.ACCESS_FINE_LOCATION"))

        val perms28 = AndroidBlePermissions.getRequiredPermissions(28)
        assertEquals(perms30, perms28)
    }

    @Test
    fun testConstantsIntegrity() {
        assertEquals(AndroidBlePermissions.PERMISSIONS_API_31, AndroidBlePermissions.getRequiredPermissions(33))
        assertEquals(AndroidBlePermissions.PERMISSIONS_LEGACY, AndroidBlePermissions.getRequiredPermissions(29))
    }
}
