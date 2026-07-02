# commit-dag

**Parent-linked, content-addressed commit chain — pure Clojure, no native deps,
babashka-friendly.** Wave 1/2 of
[ADR-2607022600](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607022600-kotoba-database-crates-cljc-migration-roadmap.md)
(migrating the removed `kotoba-lang/kotoba` Rust database crates to CLJC).

The removed Rust `kotoba-graph::commit` was an append-only, parent-linked chain
of `Commit{root, index_roots, prev, seq}` blocks — per
[ADR-2606041151](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2606041151-kotoba-commitdag-as-wal-and-incremental-query-tier.md),
**the CommitDag IS the write-ahead log**, not a separate journal. This
namespace is that primitive, with no CLJC predecessor: it commits an opaque
`state` value (today, typically a
[`kotoba-lang/prolly-tree`](https://github.com/kotoba-lang/prolly-tree) root
CID string; once Wave 2's 5-index Arrangement lands, just as well a map of
`{"eavt" cid "aevt" cid ...}`) and never looks inside it — only chains and
verifies.

Storage is injected exactly the way `prolly-tree.core` does it (`put!`
`(cid, bytes) -> ignored` / `get-fn (cid) -> bytes`), so a caller using both
libraries shares one block store with no adapter glue.

## Use

```clojure
(require '[commit-dag.core :as cd])

(def store (atom {}))
(def put!   (fn [cid bytes] (swap! store assoc cid bytes)))
(def get-fn (fn [cid] (get @store cid)))

(def c0 (cd/commit! put! get-fn "prolly-root-a" nil))   ; genesis, seq 0
(def c1 (cd/commit! put! get-fn "prolly-root-b" c0))    ; seq 1

(cd/chain get-fn c1)          ;=> ({:cid c0 :state "prolly-root-a" :prev nil :seq 0}
                              ;    {:cid c1 :state "prolly-root-b" :prev c0  :seq 1})
(cd/head get-fn c1)           ;=> the seq-1 entry above
(cd/verify-chain get-fn c1)   ;=> true — false if a store lies about a CID's bytes
                              ;    or a spliced-in commit skips a seq
```

## Correctness

`clojure -M:test` (no network):

- genesis + multi-commit chain linking, `:seq` values, `head`
- `state` is opaque — a bare CID string and a `{index cid}` map both round-trip
  unchanged (Wave 2 forward-compatibility)
- `verify-chain` accepts an honest chain
- `verify-chain` rejects a store that returns different bytes for an existing
  CID than what was originally content-addressed under it (tamper-evidence)
- `verify-chain` rejects a spliced-in commit with a `:seq` gap, independent of
  the tamper-evidence check above

```
$ clojure -M:test
Ran 6 tests containing 12 assertions.
0 failures, 0 errors.
```

## Scope

This is the commit-chain primitive only. Not in scope for this landing:
snapshotting/checkpoint-from-seq-N (the original Rust engine's "restart loads
the head + checkpoint and walks commits since" — this namespace only walks
from a given `cid`, callers own persisting "the current head cid" themselves),
garbage collection of unreferenced commits, and multi-writer conflict
resolution (a single linear `prev` chain assumes one writer per graph, which
matches the Datom-log-is-canonical decision in
[ADR-2605312345](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2605312345-kotoba-datom-first-class-canonical-state.md)
but not yet any multi-peer merge story).

## License

Apache-2.0.
