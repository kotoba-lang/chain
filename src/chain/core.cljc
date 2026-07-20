;; chain.core — parent-linked, content-addressed commit chain.
;;
;; Renamed from `commit-dag` (ADR-2607050800): "chain" names what this
;; actually is -- a parent-linked chain of commits -- and lines up cleanly
;; with this namespace's own `chain`/`verify-chain`/`head` functions, without
;; the collision `log` would have had with the unrelated, already-existing
;; `kotoba-lang/log` (structured logging/telemetry, `kotoba.lang.log`).
;; Deliberately decoupled from any particular index structure: a commit
;; wraps an opaque, dag-cbor-encodable `state` value -- today that's
;; typically a single `kotoba-lang/prolly-tree` root CID string, or
;; `kotoba-lang/arrangement`'s 4-index roots as a map of
;; `{"eavt" cid "aevt" cid ...}` -- this namespace never looks inside
;; `state`, it only chains and verifies it. See ADR-2607022600 (Wave 1/2).
;;
;; Storage is injected the same way `prolly-tree.core` does it: `put!` (cid,
;; bytes -> ignored) and `get-fn` (cid -> bytes), so a caller building on both
;; libraries shares one block store without any adapter glue.
(ns chain.core
  (:require [ipld.core :as ipld]))

(defn- verified-block [get-fn expected-cid]
  (when-let [bytes (get-fn expected-cid)]
    (let [actual (ipld/cid bytes)]
      (when-not (= expected-cid actual)
        (throw (ex-info "chain: block CID mismatch"
                        {:type :ipld/cid-mismatch
                         :expected-cid expected-cid :actual-cid actual})))
      bytes)))

;; `prev` is a REAL tag-42 IPLD link (null at genesis) via kotoba-lang/ipld --
;; this replaced the first landing's plain-CID-string ("" at genesis) encoding;
;; every commit CID changed (clean break, pre-production, see superproject ADR).
;; `state` stays opaque: a plain value passes through untouched, and a caller
;; that wants its state's references walkable simply puts `ipld/link` values
;; inside it (kotobase-peer links its arrangement snapshot CID this way).
(defn- encode-commit [state prev-cid seq]
  (ipld/encode {"state" state "prev" (some-> prev-cid ipld/link) "seq" seq}))

(defn commit!
  "Append a `{state, prev, seq}` commit, calling `(put! cid bytes)`. Returns
   the new commit's CID. `prev-cid` is nil for the genesis commit (seq 0);
   otherwise `seq` is `(inc (:seq prev-commit))`."
  [put! get-fn state prev-cid]
  (let [seq (if prev-cid
              (inc (long (get (ipld/decode
                               (verified-block get-fn prev-cid))
                              "seq")))
              0)
        bytes (encode-commit state prev-cid seq)
        cid (ipld/cid bytes)]
    (put! cid bytes)
    cid))

(defn commit-info
  "Decode the commit at `cid` into `{:cid :state :prev :seq}`. `:prev` is nil
   at genesis."
  [get-fn cid]
  (let [m (ipld/decode (verified-block get-fn cid))
        prev (get m "prev")]
    {:cid cid :state (get m "state")
     :prev (some-> prev ipld/link-cid)
     :seq (get m "seq")}))

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
  (try
    (let [entries (chain get-fn cid)]
      (and (seq entries)
           (every? (fn [{:keys [cid state prev seq]}]
                     (= cid (ipld/cid (encode-commit state prev seq))))
                   entries)
           (= (map :seq entries) (range (count entries)))))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn head
  "The commit-info AT `cid` — `cid` is by definition the chain's tip (every
   caller passes the CID they last recorded as \"current head\"), so this is
   ONE block fetch, O(1) — not a walk to genesis. Provably equivalent to the
   original `(last (chain get-fn cid))`: `chain`'s loop conses each ancestor
   onto the FRONT of its accumulator and starts from `cid` itself, so `cid`'s
   own commit-info is always the LAST element chain returns; `head` used to
   pay for the whole walk just to hand back the first thing it read. Reading
   the current state on every write (ADR-2607032430 D1's novelty-log commit!)
   made this the hot path — an O(N) chain-length read on every O(tx) write
   would have defeated the point."
  [get-fn cid]
  (when cid (commit-info get-fn cid)))
