(ns cargohandling.scope-exclusion-test
  "Dedicated regression test for a self-tripping bug class multiple
  sibling `cloud-itonami-isic-*` actors in this fleet independently
  hit and fixed: a governor's scope-exclusion term list phrased as a
  bare noun ('safety', 'clearance', 'hazmat') can accidentally match
  inside the mock advisor's own DEFAULT rationale/disclaimer text for
  a legitimate, allowed proposal, causing the actor to self-block on
  its own happy path.

  `cargohandling.governor/finalize-load-safety-patterns` is phrased as
  the finalization/execution ACTION ('finalize the load-safety
  clearance', not the bare noun 'safety') specifically to avoid this.
  This test asserts the invariant directly: NONE of
  `cargohandling.advisor`'s four default proposals ever trips
  `:finalize-load-safety-attempt`, for every target (including the
  unverified/unregistered ones, which SHOULD hold on other rules but
  must never additionally trip the scope-exclusion check)."
  (:require [clojure.test :refer [deftest is testing]]
            [cargohandling.store :as store]
            [cargohandling.advisor :as advisor]
            [cargohandling.governor :as governor]))

(defn- rule-set [verdict]
  (set (map :rule (:violations verdict))))

(deftest default-proposals-never-self-trip-scope-exclusion
  (let [db (store/seed-db)
        adv (advisor/mock-advisor)
        cases [{:op :log-cargo-record :target-id "shp-1" :detail "container discharge"}
               {:op :log-cargo-record :target-id "shp-2" :detail "pallet unload"}
               {:op :log-cargo-record :target-id "shp-3" :detail "drum handling"}
               {:op :schedule-handling-operation :target-id "shp-1" :resource-request {:crane "fac-1" :dock "B-12"}}
               {:op :schedule-handling-operation :target-id "shp-2" :resource-request {:crane "fac-1"}}
               {:op :flag-cargo-safety-concern :target-id "shp-1"
                :concern-type "weight-limit-exceedance" :description "reported gross weight exceeds crane rated capacity"}
               {:op :flag-cargo-safety-concern :target-id "shp-2"
                :concern-type "load-securing-failure" :description "lashing reported loose after transit"}
               {:op :flag-cargo-safety-concern :target-id "shp-3"
                :concern-type "hazmat-segregation-issue" :description "incompatible drum classes stowed adjacently"}
               {:op :coordinate-equipment-maintenance :target-id "fac-1" :maintenance-type "crane-inspection"}
               {:op :coordinate-equipment-maintenance :target-id "fac-2" :maintenance-type "dock-calibration"}]]
    (doseq [request cases]
      (testing (str (:op request) " on " (:target-id request))
        (let [proposal (advisor/-advise adv db request)
              verdict (governor/check request {:actor-id "op-1"} proposal db)]
          (is (= :propose (:effect proposal)) "advisor always proposes, never commits directly")
          (is (not (contains? (rule-set verdict) :finalize-load-safety-attempt))
              (str "legitimate default proposal must never self-trip the scope-exclusion check: "
                   (pr-str (:violations verdict))))
          (is (not (contains? (rule-set verdict) :op-not-allowed))
              "every default advisor op is in the closed allowlist")
          (is (not (contains? (rule-set verdict) :effect-not-propose))
              "the advisor's own :effect is always literally :propose"))))))

(deftest finalize-load-safety-patterns-do-catch-a-real-attempt
  (testing "sanity check: the patterns are not vacuously non-matching -- they DO catch an actual finalization-action attempt"
    (let [db (store/seed-db)
          attempts
          [{:operation :schedule-handling-operation :effect :propose :target-id "shp-1"
            :confidence 0.9 :rationale "we will finalize the load safety clearance for this shipment"}
           {:operation :schedule-handling-operation :effect :propose :target-id "shp-1"
            :confidence 0.9 :rationale "propose to override the weight limit rule"}
           {:operation :schedule-handling-operation :effect :propose :target-id "shp-1"
            :confidence 0.9 :rationale "recommend we waive the hazmat segregation rule for this drum"}
           {:operation :log-cargo-record :effect :propose :target-id "shp-1"
            :confidence 0.9 :summary "積荷の安全を確定します"}]]
      (doseq [proposal attempts]
        (let [verdict (governor/check {:op (:operation proposal) :target-id "shp-1"}
                                      {:actor-id "op-1"} proposal db)]
          (is (contains? (rule-set verdict) :finalize-load-safety-attempt)
              (str "must catch: " (pr-str proposal))))))))
