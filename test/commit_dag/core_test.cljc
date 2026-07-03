(ns commit-dag.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [cbor.core :as cbor]
            [ipld.core :as ipld]
            [commit-dag.core :as cd]))

(defn- mem-store []
  (let [store (atom {})]
    {:put! (fn [cid bytes] (swap! store assoc cid bytes))
     :get-fn (fn [cid] (get @store cid))
     :store store}))

(deftest genesis-commit
  (let [{:keys [put! get-fn]} (mem-store)
        c0 (cd/commit! put! get-fn "root-a" nil)]
    (is (some? c0))
    (is (= {:cid c0 :state "root-a" :prev nil :seq 0} (cd/commit-info get-fn c0)))))

(deftest chain-links-and-increments-seq
  (let [{:keys [put! get-fn]} (mem-store)
        c0 (cd/commit! put! get-fn "root-a" nil)
        c1 (cd/commit! put! get-fn "root-b" c0)
        c2 (cd/commit! put! get-fn "root-c" c1)
        chain (cd/chain get-fn c2)]
    (is (= 3 (count chain)))
    (is (= [0 1 2] (map :seq chain)))
    (is (= ["root-a" "root-b" "root-c"] (map :state chain)))
    (is (= c2 (:cid (cd/head get-fn c2))))))

(deftest state-can-be-a-multi-index-map
  (testing "state is opaque -- a map of {index cid} works exactly like a bare CID string,
            which is what Wave 2's 5-index Arrangement will pass through here unchanged"
    (let [{:keys [put! get-fn]} (mem-store)
          c0 (cd/commit! put! get-fn {"eavt" "cid-1" "aevt" "cid-2"} nil)]
      (is (= {"eavt" "cid-1" "aevt" "cid-2"} (:state (cd/commit-info get-fn c0)))))))

(deftest verify-chain-accepts-honest-chain
  (let [{:keys [put! get-fn]} (mem-store)
        c0 (cd/commit! put! get-fn "root-a" nil)
        c1 (cd/commit! put! get-fn "root-b" c0)]
    (is (true? (cd/verify-chain get-fn c1)))
    (is (true? (cd/verify-chain get-fn c0)))))

(deftest verify-chain-rejects-a-store-that-lies
  (let [{:keys [put! get-fn store]} (mem-store)
        c0 (cd/commit! put! get-fn "root-a" nil)
        c1 (cd/commit! put! get-fn "root-b" c0)
        c2 (cd/commit! put! get-fn "root-c" c1)]
    (is (true? (cd/verify-chain get-fn c2)) "untampered 3-commit chain verifies")
    ;; corrupt the bytes returned for c0 WITHOUT changing c0's own cid key --
    ;; a store implementer's bug or an actively dishonest backend.
    (swap! store assoc c0 (cbor/encode {"state" "root-EVIL" "prev" nil "seq" 0}))
    (is (false? (cd/verify-chain get-fn c2)) "tampering anywhere in the chain is caught")))

(deftest verify-chain-detects-seq-gaps
  (let [{:keys [put! get-fn store]} (mem-store)
        c0 (cd/commit! put! get-fn "root-a" nil)
        c1 (cd/commit! put! get-fn "root-b" c0)]
    ;; splice a bogus seq into c1's own record and re-house it under a freshly
    ;; recomputed cid, so the tamper-evidence check in the PREVIOUS test
    ;; wouldn't catch this on its own -- only the seq-monotonicity check does.
    (let [bogus-bytes (ipld/encode {"state" "root-b" "prev" (ipld/link c0) "seq" 5})
          bogus-cid (ipld/cid bogus-bytes)]
      (swap! store assoc bogus-cid bogus-bytes)
      (is (false? (cd/verify-chain get-fn bogus-cid))))))

(deftest head-is-o1-and-matches-chain-last
  (let [{:keys [put! get-fn]} (mem-store)
        c0 (cd/commit! put! get-fn "root-a" nil)
        c1 (cd/commit! put! get-fn "root-b" c0)
        c2 (cd/commit! put! get-fn "root-c" c1)]
    (is (= (last (cd/chain get-fn c2)) (cd/head get-fn c2))
        "head == (last (chain ...)), proven equivalent by chain's own construction")
    (is (= {:cid c2 :state "root-c" :prev c1 :seq 2} (cd/head get-fn c2)))
    (testing "head at a non-tip cid returns THAT commit's own info, not the true tip's --
              consistent with head's contract (\"the commit-info at cid\"), and exactly
              what (last (chain get-fn c1)) also returns"
      (is (= (last (cd/chain get-fn c1)) (cd/head get-fn c1)))
      (is (= {:cid c1 :state "root-b" :prev c0 :seq 1} (cd/head get-fn c1))))
    (testing "nil cid -> nil, not an error"
      (is (nil? (cd/head get-fn nil))))))

(deftest prev-is-a-real-ipld-link-on-block
  (let [{:keys [put! get-fn]} (mem-store)
        c0 (cd/commit! put! get-fn "root-a" nil)
        c1 (cd/commit! put! get-fn (ipld/link c0) c0)   ; state with a link in it
        node (ipld/decode (get-fn c1))]
    (is (ipld/link? (get node "prev")))
    (is (= c0 (ipld/link-cid (get node "prev"))))
    (testing "genesis prev is null, not empty string"
      (is (nil? (get (ipld/decode (get-fn c0)) "prev"))))
    (testing "a linked state is walkable generically: prev + state both surface"
      (is (= [c0 c0] (ipld/links node))))
    (testing "commit-info returns the state Link intact"
      (is (= (ipld/link c0) (:state (cd/commit-info get-fn c1)))))))
