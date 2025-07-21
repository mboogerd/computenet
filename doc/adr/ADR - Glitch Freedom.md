# Architectural Decision Record: Local Glitch-Freedom at the Task Level

## Status
Draft

## Context
Reactive and dataflow systems often suffer from "glitches"—intermediate, inconsistent states observed during propagation—especially in fork-join topologies or concurrent executions. Some distributed FRP systems (e.g., Distributed REScala with SID-UP) solve this by enforcing a global propagation lock, allowing only one propagation wave through the entire graph at a time. While effective, such a solution does not scale and introduces unacceptable bottlenecks for high-throughput or highly concurrent systems.

## Decision
Instead of enforcing glitch-freedom globally, our architecture allows tasks to opt into glitch-freedom *locally*. A task that declares itself `glitch-free` performs the following:

1. **Dependency Tracking**: Upon initialization or update, the task performs an upstream traversal to determine its current data dependencies.
2. **Version Buffering**: For each incoming update (e.g., a versioned or timestamped event), the task buffers inputs until it has received a complete set of inputs for that version.
3. **Local Evaluation Barrier**: The task only evaluates when it has a consistent view of all inputs for a given version. No global locks or barriers are required.
4. **Topology Consistency**: Changes in dependency topology are considered part of the update stream and must be causally consistent with value updates.

This allows glitch-freedom to be composable and selectively applied without sacrificing global concurrency or throughput.

Additionally, a glitch-free task does not need to recursively track all upstream dependencies across the entire graph. Instead, it may stop its traversal at the closest upstream glitch-free tasks, which are assumed to already provide consistent outputs for each version. This forms a "glitch-free frontier" beyond which causal consistency is already enforced. However, all edges between those upstream glitch-free tasks and the current task must still be tracked and version-buffered to ensure values arriving along fork-join paths are still synchronized correctly. This enables glitch-freedom to compose cleanly across task boundaries.

## Consequences
- Tasks that do not require glitch-freedom can process eagerly without coordination.
- Tasks that opt in must maintain per-version input buffers and track causal completeness.
- The runtime must propagate version information (e.g., pulse IDs or logical timestamps) with updates to support this model.
- Structural changes in the graph must be versioned and synchronized with value updates to avoid inconsistencies.

## Alternatives Considered
- **Global propagation locks (e.g., SID-UP)**: Provides strong guarantees but imposes severe performance constraints.
- **Naive eager propagation**: Simpler and faster, but introduces glitches in fork-join or concurrent updates.

## References
- SID-UP / Distributed REScala