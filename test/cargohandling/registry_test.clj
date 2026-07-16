(ns cargohandling.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [cargohandling.registry :as registry]))

(deftest register-cargo-record-drafts-a-record
  (let [r (registry/register-cargo-record "shp-1" "JPN" 0)]
    (is (= "JPN-CARGO-000000" (get r "record_number")))
    (is (= "JPN-CARGO-000000" (get-in r ["record" "record_id"])))
    (is (= "cargo-log-draft" (get-in r ["record" "kind"])))
    (is (true? (get-in r ["record" "immutable"])))
    (is (false? (get-in r ["certificate" "issued_by_registry"])) "never a real registry issuance")))

(deftest register-schedule-record-drafts-a-record
  (let [r (registry/register-schedule-record "shp-1" "JPN" 3)]
    (is (= "JPN-SCHEDULE-000003" (get r "record_number")))
    (is (= "schedule-proposal-draft" (get-in r ["record" "kind"])))))

(deftest register-maintenance-record-drafts-a-record
  (let [r (registry/register-maintenance-record "fac-1" "JPN" 0)]
    (is (= "JPN-MAINT-000000" (get r "record_number")))
    (is (= "maintenance-coordination-draft" (get-in r ["record" "kind"])))))

(deftest register-concern-record-drafts-a-record
  (let [r (registry/register-concern-record "shp-1" "JPN" 0)]
    (is (= "JPN-CONCERN-000000" (get r "record_number")))
    (is (= "cargo-safety-concern-draft" (get-in r ["record" "kind"])))))

(deftest missing-target-id-throws
  (testing "honest failure -- never silently drafts a record with no target"
    (is (thrown? Exception
                 (registry/register-cargo-record nil "JPN" 0)))
    (is (thrown? Exception
                 (registry/register-cargo-record "" "JPN" 0)))))

(deftest missing-jurisdiction-throws
  (is (thrown? Exception
               (registry/register-cargo-record "shp-1" nil 0)))
  (is (thrown? Exception
               (registry/register-cargo-record "shp-1" "" 0))))

(deftest negative-sequence-throws
  (is (thrown? Exception
               (registry/register-cargo-record "shp-1" "JPN" -1))))

(deftest append-conj-record-onto-history
  (let [r (registry/register-cargo-record "shp-1" "JPN" 0)]
    (is (= [(get r "record")] (registry/append [] r)))
    (is (= [:x (get r "record")] (registry/append [:x] r)))))
