# AirDB Coding & Engineering Standards

These standards ensure that AirDB maintains high architectural integrity, safety, and readability as an enterprise-grade open-source project.

---

## 1. Concurrency & Thread-Safety

1. **State Isolation**:
   - Internal collections must be thread-safe (`ConcurrentHashMap`, `CopyOnWriteArrayList`, or atomic primitives).
   - Any mutable critical sections across multiple fields must be protected by explicit locks (`synchronized(lock) { ... }`).
2. **Non-Blocking Delegates**:
   - Never call `Thread.sleep()` or blocking I/O inside transport callbacks or event listeners.
   - Use Kotlin Coroutines (`CoroutineScope`, `launch`, `delay`) with appropriate dispatchers (`Dispatchers.Default` for CPU/state machine, `Dispatchers.IO` for disk adapters).

---

## 2. API Design & Seams

1. **Clean Seams**:
   - High-level business logic must never know about Android Bluetooth classes (`BluetoothGatt`, `BluetoothDevice`).
   - All I/O must enter and leave through `AirDbTransport` and `AirDbAdapter` interfaces.
2. **KDoc Coverage**:
   - Every public interface, class, and method MUST have comprehensive KDoc comments describing:
     - Purpose and invariants
     - Thread-safety guarantees
     - Parameter descriptions
     - Return values

---

## 3. Testing Rules

1. **Deterministic Tests**:
   - No `Thread.sleep` in tests. Use coroutine test dispatchers (`runTest`, `advanceTimeBy`, `StandardTestDispatcher`) or `CountDownLatch` with reasonable timeouts.
2. **Property & Fuzz Testing**:
   - Binary serialization and reassembly logic must be tested with edge cases: 0-byte arrays, maximum integer limits, and out-of-order chunk arrival.
