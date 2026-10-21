# ADR 0002: Hybrid Logical Clock (HLC) for Causality and Conflict Resolution

## Status
Accepted

## Context
AirDB operates in partitioned, offline environments (disaster zones, outdoor markets) where devices have no access to NTP time servers. Device wall clocks can drift or be manually changed by users. Relying purely on physical timestamps leads to data loss or incorrect ordering.

## Decision
We implement a monotonic Hybrid Logical Clock (HLC) that tracks both physical epoch milliseconds and an incrementing logical counter. Each HLC timestamp also appends the unique node ID as a deterministic tie-breaker:
`physicalTime - logicalCounter - nodeId`

Conflicts on identical record keys are resolved using Last-Write-Wins (LWW) based on total HLC ordering:
`HlcTimestampA > HlcTimestampB`

## Consequences
- **Positive**: Strict causality preservation, monotonic progression even across clock resets, deterministic convergence without consensus rounds.
- **Negative**: Last-Write-Wins operates at the record level; fine-grained column-level conflict resolution requires field-level CRDTs in future iterations.
