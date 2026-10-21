# ADR 0001: Sans-I/O Architecture for Core and Protocol

## Status
Accepted

## Context
Mobile peer-to-peer applications frequently suffer from high testing friction, queue deadlocks, and flakey integration tests when network sockets or Bluetooth hardware APIs are interwoven directly into business logic. BitChat's V3 rewrite specifically highlighted the maintenance overhead of callback closures bridging queues into state.

## Decision
We decouple all state machines, causal clocks, data models, and binary protocol serializers completely from network and operating system I/O:
1. `airdb-core`, `airdb-protocol`, and `airdb-adapter-api` do not import any network or Android classes.
2. Inbound network data is ingested as raw byte arrays.
3. Outbound data is emitted as high-level packets or commands.
4. An in-memory virtual radio mesh simulator (`airdb-transport-sim`) is provided to test multi-node sync topologies without hardware.

## Consequences
- **Positive**: 100% testable on pure JVM in milliseconds; 100% Kotlin Multiplatform (KMP) ready.
- **Negative**: Requires clean interface boundaries (`AirDbTransport`, `AirDbAdapter`) between modules.
