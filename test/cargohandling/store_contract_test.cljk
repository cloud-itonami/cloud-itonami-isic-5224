(ns cargohandling.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [cargohandling.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "containerized general cargo" (:cargo-desc (store/shipment s "shp-1"))))
      (is (true? (:registered? (store/shipment s "shp-1"))))
      (is (true? (:verified? (store/shipment s "shp-1"))))
      (is (true? (:registered? (store/shipment s "shp-2"))) "shp-2 registered")
      (is (false? (:verified? (store/shipment s "shp-2"))) "shp-2 unverified")
      (is (false? (:registered? (store/shipment s "shp-3"))) "shp-3 unregistered")
      (is (true? (:registered? (store/facility s "fac-1"))))
      (is (false? (:registered? (store/facility s "fac-2"))) "fac-2 unregistered")
      (is (= ["shp-1" "shp-2" "shp-3"] (mapv :id (store/all-shipments s))))
      (is (= ["fac-1" "fac-2"] (mapv :id (store/all-facilities s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/cargo-log s)))
      (is (= [] (store/schedule-log s)))
      (is (= [] (store/maintenance-log s)))
      (is (= [] (store/concern-log s)))
      (is (zero? (store/next-sequence s "JPN" :cargo)))
      (is (zero? (store/next-sequence s "JPN" :schedule))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "cargo-record commit drafts a record and advances the cargo sequence"
        (store/commit-record! s {:effect :cargo/log :path ["shp-1"]})
        (is (= "JPN-CARGO-000000" (get (first (store/cargo-log s)) "record_id")))
        (is (= "cargo-log-draft" (get (first (store/cargo-log s)) "kind")))
        (is (= 1 (count (store/cargo-log s))))
        (is (= 1 (store/next-sequence s "JPN" :cargo))))
      (testing "schedule-proposal commit drafts a record and advances the schedule sequence"
        (store/commit-record! s {:effect :schedule/propose :path ["shp-1"]})
        (is (= "JPN-SCHEDULE-000000" (get (first (store/schedule-log s)) "record_id")))
        (is (= 1 (count (store/schedule-log s))))
        (is (= 1 (store/next-sequence s "JPN" :schedule))))
      (testing "maintenance-coordination commit drafts a record against a facility"
        (store/commit-record! s {:effect :maintenance/coordinate :path ["fac-1"]})
        (is (= "JPN-MAINT-000000" (get (first (store/maintenance-log s)) "record_id")))
        (is (= 1 (count (store/maintenance-log s)))))
      (testing "cargo-safety-concern commit drafts a record"
        (store/commit-record! s {:effect :concern/record :path ["shp-1"]})
        (is (= "JPN-CONCERN-000000" (get (first (store/concern-log s)) "record_id")))
        (is (= 1 (count (store/concern-log s)))))
      (testing "a second cargo-record commit for the SAME jurisdiction advances the sequence"
        (store/commit-record! s {:effect :cargo/log :path ["shp-2"]})
        (is (= 2 (count (store/cargo-log s))))
        (is (= "JPN-CARGO-000001" (get (second (store/cargo-log s)) "record_id")))
        (is (= 2 (store/next-sequence s "JPN" :cargo))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/shipment s "nope")))
    (is (nil? (store/facility s "nope")))
    (is (= [] (store/all-shipments s)))
    (is (= [] (store/all-facilities s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/cargo-log s)))
    (is (zero? (store/next-sequence s "JPN" :cargo)))
    (store/with-shipments s {"x" {:id "x" :cargo-desc "test cargo" :weight-kg 100
                                  :jurisdiction "JPN" :registered? true :verified? true}})
    (is (= "test cargo" (:cargo-desc (store/shipment s "x"))))
    (store/with-facilities s {"y" {:id "y" :name "Reach Stacker 2" :kind "reach-stacker"
                                   :jurisdiction "JPN" :registered? true}})
    (is (= "Reach Stacker 2" (:name (store/facility s "y"))))))
