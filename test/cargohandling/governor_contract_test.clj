(ns cargohandling.governor-contract-test
  "The governor contract as executable tests. The single invariant
  under test:

    CargoHandlingAdvisor never commits a coordination record the
    Cargo Handling Governor would reject, `:flag-cargo-safety-concern`
    NEVER auto-commits at any phase, `:log-cargo-record` (no direct
    capital risk) MAY auto-commit when clean, no proposal that tries
    to finalize a load-safety clearance ever commits (or even reaches
    a human), and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [cargohandling.store :as store]
            [cargohandling.governor :as governor]
            [cargohandling.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :terminal-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-cargo-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-cargo-record :target-id "shp-1" :detail "container discharge"} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/cargo-log db))))
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-handling-operation-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-handling-operation :target-id "shp-1"
                                   :resource-request {:crane "fac-1"}} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/schedule-log db))))))))

(deftest coordinate-equipment-maintenance-always-needs-approval
  (testing "equipment maintenance coordination is never in any phase's :auto set -- always human approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :coordinate-equipment-maintenance :target-id "fac-1"
                                   :maintenance-type "crane-inspection"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/maintenance-log db))))))))

(deftest flag-cargo-safety-concern-always-escalates-even-when-clean
  (testing "flagging a cargo-safety concern ALWAYS interrupts for human sign-off, even fresh/clean/high-confidence -- never auto, never a member of any phase's :auto set"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :flag-cargo-safety-concern :target-id "shp-1"
                                   :concern-type "weight-limit-exceedance"
                                   :description "reported gross weight exceeds crane rated capacity"} operator)]
      (is (= :interrupted (:status res)) "pauses for human sign-off even when governor-clean")
      (let [r2 (approve! actor "t4")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/concern-log db))))))))

(deftest unverified-shipment-is-held-and-unoverridable
  (testing "an unverified shipment -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :log-cargo-record :target-id "shp-2" :detail "pallet unload"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:shipment-unverified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/cargo-log db))))))

(deftest unregistered-shipment-is-held-and-unoverridable
  (testing "an unregistered shipment -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :schedule-handling-operation :target-id "shp-3"
                                   :resource-request {:crane "fac-1"}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:shipment-unverified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-log db))))))

(deftest unregistered-facility-is-held-and-unoverridable
  (testing "an unregistered facility (coordinate-equipment-maintenance) -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :coordinate-equipment-maintenance :target-id "fac-2"
                                   :maintenance-type "dock-calibration"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:facility-unregistered} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-log db))))))

(deftest missing-target-is-held
  (testing "a target-id with no shipment record at all -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :log-cargo-record :target-id "shp-nope" :detail "x"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:shipment-unverified} (-> (store/ledger db) last :basis))))))

(deftest hallucinated-op-is-held-and-unoverridable
  (testing "an op outside the closed allowlist (the advisor's own unknown-op fallback) -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t9" {:op :bogus-op :target-id "shp-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:op-not-allowed} (-> (store/ledger db) last :basis))))))

(deftest finalize-load-safety-attempt-is-held-and-unoverridable
  (testing "governor/check directly: a proposal whose text tries to finalize a load-safety clearance -> HARD hold, defense-in-depth against a real (non-mock) advisor"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :schedule-handling-operation :target-id "shp-1"}
                   {:actor-id "op-1"}
                   {:operation :schedule-handling-operation :effect :propose
                    :target-id "shp-1" :confidence 0.9
                    :rationale "recommend to finalize the load safety clearance now"}
                   db)]
      (is (:hard? verdict))
      (is (some #{:finalize-load-safety-attempt} (map :rule (:violations verdict)))))))

(deftest weight-limit-override-attempt-is-held
  (testing "governor/check directly: a proposal whose text tries to override the weight-limit rule -> HARD hold"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :schedule-handling-operation :target-id "shp-1"}
                   {:actor-id "op-1"}
                   {:operation :schedule-handling-operation :effect :propose
                    :target-id "shp-1" :confidence 0.9
                    :rationale "we should override the weight limit rule for this shipment"}
                   db)]
      (is (:hard? verdict))
      (is (some #{:finalize-load-safety-attempt} (map :rule (:violations verdict)))))))

(deftest hazmat-segregation-waiver-attempt-is-held
  (testing "governor/check directly: a proposal whose text tries to waive the hazmat-segregation rule -> HARD hold"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :schedule-handling-operation :target-id "shp-1"}
                   {:actor-id "op-1"}
                   {:operation :schedule-handling-operation :effect :propose
                    :target-id "shp-1" :confidence 0.9
                    :rationale "propose to waive the hazmat segregation rule for this drum"}
                   db)]
      (is (:hard? verdict))
      (is (some #{:finalize-load-safety-attempt} (map :rule (:violations verdict)))))))

(deftest effect-not-propose-is-held
  (testing "governor/check directly: a proposal whose :effect is not :propose -> HARD hold, whatever the op"
    (let [db (store/seed-db)
          verdict (governor/check
                   {:op :log-cargo-record :target-id "shp-1"}
                   {:actor-id "op-1"}
                   {:operation :log-cargo-record :effect :commit
                    :target-id "shp-1" :confidence 0.9}
                   db)]
      (is (:hard? verdict))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-cargo-record :target-id "shp-1" :detail "x"} operator)
      (exec-op actor "b" {:op :log-cargo-record :target-id "shp-2" :detail "y"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ────────────── Inbound Cross-Actor Handoff -- SOFT escalation, not hold (ADR-2800002100) ──────────────

(def ^:private well-formed-handoff
  {:handoff/id "h-1"
   :handoff/source-actor "cloud-itonami-isic-5210"
   :handoff/batch-id "batch-001"
   :handoff/product-type-id :petroleum/diesel
   :handoff/quantity-kg 120.5
   :handoff/dispatched-at-iso "2026-07-24T00:00:00Z"})

(defn- db-with-shipment-handoff
  "A fresh seeded MemStore with `handoff` merged onto shp-1's own
  record (shp-1 is already :registered? true / :verified? true in the
  demo data, so this isolates the handoff check from the pre-existing
  shipment-unverified check)."
  [handoff]
  (let [db (store/seed-db)]
    (store/commit-record! db {:effect :shipment/upsert
                               :value {:id "shp-1" :shipment/handoff handoff}})
    db))

(defn- log-cargo-record-verdict [db]
  (governor/check {:op :log-cargo-record :target-id "shp-1"}
                  {:actor-id "op-1"}
                  {:operation :log-cargo-record :effect :propose
                   :target-id "shp-1" :confidence 0.95}
                  db))

(deftest storage-handoff-suspect-escalation-test
  (testing "no :shipment/handoff at all does not trigger this rule -- absence is normal"
    (let [verdict (log-cargo-record-verdict (store/seed-db))]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)))
      (is (not (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations verdict))))))

  (testing "well-formed handoff from the registered upstream storage-terminal actor does not trigger this rule"
    (let [verdict (log-cargo-record-verdict (db-with-shipment-handoff well-formed-handoff))]
      (is (false? (:hard? verdict)))
      (is (false? (:escalate? verdict)))
      (is (not (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations verdict))))))

  (testing "handoff from an unrecognized source-actor escalates, not holds"
    (let [db (db-with-shipment-handoff (assoc well-formed-handoff
                                               :handoff/source-actor "cloud-itonami-isic-9999"))
          verdict (log-cargo-record-verdict db)]
      (is (false? (:hard? verdict)) "unrecognized source-actor is never a HARD hold")
      (is (true? (:escalate? verdict)))
      (is (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations verdict)))
      (is (empty? (:violations verdict)) "the hard :violations key stays untouched by this SOFT check")))

  (testing "malformed handoff (missing :handoff/quantity-kg) escalates, not holds"
    (let [db (db-with-shipment-handoff (dissoc well-formed-handoff :handoff/quantity-kg))
          verdict (log-cargo-record-verdict db)]
      (is (false? (:hard? verdict)) "a malformed handoff is never a HARD hold")
      (is (true? (:escalate? verdict)))
      (is (some #(= (:rule %) :storage-handoff-suspect) (:soft-violations verdict))))))
