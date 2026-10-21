# ADR 0003: Single-Flight GATT Queue for Android BLE

## Status
Accepted

## Context
The Android Bluetooth stack (BlueDroid/Fluoride) allows only one outstanding GATT operation per connection. Firing simultaneous writes from background coroutines causes silent packet loss or GATT error codes (e.g. status 133 or GATT_BUSY).

## Decision
We introduce `GattOperationQueue` in `airdb-transport-ble`:
1. Every connection link owns a dedicated FIFO queue.
2. An `inFlight` state flag ensures that only the head operation is submitted to the OS.
3. The next operation is dispatched only when the driver invokes `onCharacteristicWrite` or `onNotificationSent`.
4. Transient errors are retried with exponential backoff before marking a link dead.

## Consequences
- **Positive**: Complete elimination of race conditions and dropped packets on Android BLE.
- **Negative**: Increases queuing latency slightly, bounded by BLE connection intervals (typically 7.5ms–15ms).
