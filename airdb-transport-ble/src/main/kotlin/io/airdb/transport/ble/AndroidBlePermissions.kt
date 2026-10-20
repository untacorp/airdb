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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Utility for verifying and managing Bluetooth runtime permissions across Android API levels.
 */
object AndroidBlePermissions {

    /** Permissions required on Android 12 (API 31) and higher. */
    val PERMISSIONS_API_31: List<String> = listOf(
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE"
    )

    /** Permissions required on Android 11 (API 30) and lower. */
    val PERMISSIONS_LEGACY: List<String> = listOf(
        "android.permission.BLUETOOTH",
        "android.permission.BLUETOOTH_ADMIN",
        "android.permission.ACCESS_FINE_LOCATION"
    )

    /**
     * Resolves the list of permissions required for the specified Android API level.
     */
    fun getRequiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        return if (sdkInt >= 31) PERMISSIONS_API_31 else PERMISSIONS_LEGACY
    }

    /**
     * Verifies whether all required Bluetooth permissions have been granted by the user.
     */
    fun hasPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        val required = getRequiredPermissions(sdkInt)
        return required.all { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Returns a list of permissions currently missing or ungranted.
     */
    fun getMissingPermissions(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val required = getRequiredPermissions(sdkInt)
        return required.filter { permission ->
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        }
    }
}
