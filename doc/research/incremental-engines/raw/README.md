# Raw research sources — incremental analytics engines

Fetched 2026-07-23 during deep research on what ComputeNet can borrow from
Feldera/DBSP, Differential/Timely Dataflow, Materialize, and LASP/CRDT-lattice
systems. Findings with provenance live in `doc/research/incremental-engines/`.

## Papers (PDF)

| File | Source | What it is |
|---|---|---|
| `dbsp-vldb2023-budiu.pdf` | vldb.org/pvldb/vol16/p1601-budiu.pdf | DBSP paper (Budiu, Chajed, McSherry, Ryzhyk, Tannen), VLDB 2023 Best Research Paper. Z-sets, I/D/z⁻¹/lift basis, incrementalization theorem, nested time for recursion. |
| `dbsp-vldbj2025-budiu-extended.pdf` | mihaibudiu.github.io/work/budiu-vldb25.pdf | Extended VLDB Journal 2025 version of the DBSP paper (Algorithm 1, complexity tables). |
| `naiad-sosp2013-murray.pdf` | sigops.org (SOSP 2013) | Naiad: timely dataflow, multidimensional timestamps, loop contexts, distributed progress protocol. |
| `shared-arrangements-vldb2020.pdf` | arxiv.org/pdf/1812.02639 | Shared Arrangements (McSherry et al., VLDB 2020): traces, batches, reader-frontier compaction theorems, post-hoc index sharing. |
| `lasp-ppdp2015-meiklejohn.pdf` | christophermeiklejohn.com | Lasp (Meiklejohn & Van Roy, PPDP 2015): functional transformations over CRDT metadata. |
| `delta-state-crdts-almeida-1603.01529.pdf` | arxiv.org/pdf/1603.01529 | Delta State Replicated Data Types (Almeida, Shoker, Baquero): delta-mutators, delta-intervals, causal anti-entropy. |
| `pure-op-crdts-baquero-1710.04469.pdf` | arxiv.org/pdf/1710.04469 | Pure Operation-Based RDTs (Baquero, Almeida, Shoker): PO-Log, causal redundancy, causal stability for metadata GC. Extended version of the DAIS 2014 paper. |
| `keeping-calm-hellerstein-alvaro-1901.01930.pdf` | arxiv.org/pdf/1901.01930 | Keeping CALM (Hellerstein & Alvaro): consistency-as-logical-monotonicity theorem. arXiv version of the CACM 2020 article (cacm.acm.org returned 403). |
| `riak-dt-map.pdf` | lenary.co.uk/publications/riak_dt_map.pdf | Riak DT map: composable convergent map design (OR-map lineage). |
| `causality-to-stability-mplr2020.pdf` | soft.vub.ac.be (~jibauwen) | From Causality to Stability (Bauwens & Gonzalez Boix, MPLR 2020): practical causal-stability-based CRDT memory reclamation. |
| `crdt-gc-web-thesis-rehn2020.pdf` | diva-portal.org diva2:1441174 | "Garbage Collected CRDTs on the Web" (Rehn, Uppsala MSc 2020): CRDT memory-efficiency/GC study. |

## Vendor docs & blogs (HTML/MD)

| File | Source | What it is |
|---|---|---|
| `feldera-readme.md` | github.com/feldera/feldera (raw README) | Feldera claims: full-SQL incremental evaluation, strong (batch-prefix) consistency. |
| `feldera-blog-lateness.html` | feldera.com/blog/lateness-in-streaming-programs | LATENESS annotation → waterline → GC (author: Budiu). |
| `feldera-blog-time-series.html` | feldera.com/blog/time-series | Waterline circuit construction (z⁻¹-delayed max), time-series state bounding. |
| `materialize-blog-delta-joins.html` | materialize.com/blog/delta-joins | Delta joins / dogsdogsdogs: zero intermediate join state via arrangement reuse. |
| `materialize-blog-virtual-time.html` | materialize.com/blog/virtual-time-consistency-scalability | Virtual time: total-order timestamp assignment for internal consistency. |
| `materialize-blog-strong-consistency.html` | materialize.com/blog/strong-consistency-in-materialize | Strict serializability / real-time recency and its coordination cost. |
| `materialize-blog-recursion.html` | materialize.com/blog/recursion-in-materialize | WITH MUTUALLY RECURSIVE — incremental recursive SQL in Materialize. |
| `flink-docs-event-time.html` | nightlies.apache.org/flink (concepts/time) | Flink event time & watermark semantics. |
| `flink-docs-streaming-analytics.html` | nightlies.apache.org/flink (learn-flink) | Flink windows, allowed lateness, side outputs, state cleanup. |
| `scattered-thoughts-internal-consistency.html` | scattered-thoughts.net | Jamie Brandon: internal consistency in streaming systems — which engines emit states that never existed. |
| `muratbuffalo-dbsp-review.html` | muratbuffalo.blogspot.com (2024-11) | Murat Demirbas' independent review/summary of the DBSP paper. |

## Not archived

- `dl.acm.org/doi/pdf/10.1145/3642976.3653031` — 403 paywall (PaPoC 2024 item surfaced during search; no open mirror found).
- CACM rendering of Keeping CALM — 403; arXiv version archived instead.
