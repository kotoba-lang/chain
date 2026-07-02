;; commit-dag.core — parent-linked, content-addressed commit chain.
;;
;; The removed Rust `kotoba-graph::commit` (an append-only, parent-linked chain
;; of `Commit{root, index_roots, prev, seq}` blocks -- "the CommitDag IS the
;; write-ahead log", per ADR-2606041151) had no CLJC successor. This is that
;; primitive, deliberately decoupled from any particular index structure: a
;; commit wraps an opaque, dag-cbor-encodable `state` value -- today that's
;; typically a single `kotoba-lang/prolly-tree` root CID string, and once Wave
;; 2's 5-index Arrangement lands it can just as well be a map of
;; `{"eavt" cid "aevt" cid ...}` -- this namespace never looks inside `state`,
;; it only chains and verifies it. See ADR-2607022600 (Wave 1/2).
;;
;; Storage is injected the same way `prolly-tree.core` does it: `put!` (cid,
;; bytes -> ignored) and `get-fn` (cid -> bytes), so a caller building on both
;; libraries shares one block store without any adapter glue.
(ns commit-dag.core
  (:require [multiformats.core :as mf]
            [cbor.core :as cbor]))

(defn- encode-commit [state prev-cid seq]
  (cbor/encode {"state" state "prev" (or prev-cid "") "seq" seq}))

(defn commit!
  "Append a `{state, prev, seq}` commit, calling `(put! cid bytes)`. Returns
   the new commit's CID. `prev-cid` is nil for the genesis commit (seq 0);
   otherwise `seq` is `(inc (:seq prev-commit))`."
  [put! get-fn state prev-cid]
  (let [seq (if prev-cid
              (inc (long (get (cbor/decode (get-fn prev-cid)) "seq")))
              0)
        bytes (encode-commit state prev-cid seq)
        cid (mf/cidv1-dag-cbor bytes)]
    (put! cid bytes)
    cid))

(defn commit-info
  "Decode the commit at `cid` into `{:cid :state :prev :seq}`. `:prev` is nil
   at genesis."
  [get-fn cid]
  (let [m (cbor/decode (get-fn cid))
        prev (get m "prev")]
    {:cid cid :state (get m "state") :prev (when (seq prev) prev) :seq (get m "seq")}))

(defn chain
  "Walk commit history from `cid` back to genesis via `:prev` links. Returns a
   seq of `commit-info` maps, oldest (seq 0) first."
  [get-fn cid]
  (loop [cid cid acc ()]
    (if-not cid
      acc
      (let [{:keys [state prev seq]} (commit-info get-fn cid)]
        (recur prev (cons {:cid cid :state state :prev prev :seq seq} acc))))))

(defn verify-chain
  "True iff every commit in the chain rooted at `cid`:
     (a) re-derives to its own CID from its own {state, prev, seq} bytes --
         tamper-evidence against a store that lies about a CID's contents;
     (b) has `:seq` increasing by exactly 1 per step, starting at 0.
   False on the first violation found; also false if the chain is empty
   (a bare `cid` that doesn't decode)."
  [get-fn cid]
  (let [entries (chain get-fn cid)]
    (and (seq entries)
         (every? (fn [{:keys [cid state prev seq]}]
                   (= cid (mf/cidv1-dag-cbor (encode-commit state prev seq))))
                 entries)
         (= (map :seq entries) (range (count entries))))))

(defn head
  "The most recent (highest-seq) commit-info in the chain rooted at `cid`."
  [get-fn cid]
  (last (chain get-fn cid)))
