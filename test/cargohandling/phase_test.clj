(ns cargohandling.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-cargo-safety-concern` must NEVER be a member of
  any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [cargohandling.phase :as phase]))

(deftest flag-cargo-safety-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a cargo-safety-concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-cargo-safety-concern))
          (str "phase " n " must not auto-commit :flag-cargo-safety-concern")))))

(deftest no-finalize-load-safety-op-exists-anywhere
  (testing "structural invariant: no phase's :writes or :auto set contains any op that would finalize a load-safety clearance -- no such op exists in the domain at all"
    (doseq [[n {:keys [writes auto]}] phase/phases]
      (is (= #{} (set/intersection writes #{:finalize-load-clearance :finalize-cargo-clearance :override-weight-limit-rule}))
          (str "phase " n " writes must never contain a finalize/override op"))
      (is (= #{} (set/intersection auto #{:finalize-load-clearance :finalize-cargo-clearance :override-weight-limit-rule}))
          (str "phase " n " auto must never contain a finalize/override op")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-cargo-record carries no capital risk or load-safety determination -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-cargo-record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-cargo-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-handling-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-equipment-maintenance} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-cargo-safety-concern} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-cargo-record} :commit)))))

(deftest gate-auto-commits-a-clean-eligible-write-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :log-cargo-record} :commit)))))
