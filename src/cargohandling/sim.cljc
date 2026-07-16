(ns cargohandling.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean shipment through
  cargo-record logging (auto-commits at phase 3) -> crane/dock/
  warehouse handling scheduling (escalates/approve/commit) -> a
  cargo-safety-concern flag (ALWAYS escalates/approve/commit) ->
  equipment maintenance coordination (escalates/approve/commit), then
  shows HARD-hold scenarios: an unverified shipment, an unregistered
  shipment, an unregistered facility, a hallucinated (non-allowlisted)
  op, and a proposal whose text tries to finalize a load-safety
  clearance (scope-exclusion hard block).

  Each check is exercised directly and independently, one shipment/
  facility per HARD-hold scenario, the same 'exercise the failure mode
  directly, never only via a happy-path actuation' discipline every
  sibling actor's sim establishes."
  (:require [langgraph.graph :as g]
            [cargohandling.store :as store]
            [cargohandling.operation :as op]
            [cargohandling.governor :as governor]))

(def operator {:actor-id "op-1" :actor-role :terminal-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-cargo-record shp-1 (clean, JPN -- auto-commits at phase 3) ==")
    (println (exec-op actor "t1" {:op :log-cargo-record :target-id "shp-1"
                                  :detail "container discharge completed"} operator))

    (println "== schedule-handling-operation shp-1 (escalates -- real resource commitment) ==")
    (let [r (exec-op actor "t2" {:op :schedule-handling-operation :target-id "shp-1"
                                 :resource-request {:crane "fac-1" :dock "B-12" :shift 1}} operator)]
      (println r)
      (println "-- human terminal operator approves --")
      (println (approve! actor "t2")))

    (println "== flag-cargo-safety-concern shp-1 (ALWAYS escalates) ==")
    (let [r (exec-op actor "t3" {:op :flag-cargo-safety-concern :target-id "shp-1"
                                 :concern-type "weight-limit-exceedance"
                                 :description "reported gross weight exceeds crane rated capacity"} operator)]
      (println r)
      (println "-- human terminal operator signs off --")
      (println (approve! actor "t3")))

    (println "== coordinate-equipment-maintenance fac-1 (escalates -- real maintenance dispatch) ==")
    (let [r (exec-op actor "t4" {:op :coordinate-equipment-maintenance :target-id "fac-1"
                                 :maintenance-type "crane-inspection"} operator)]
      (println r)
      (println "-- human terminal operator approves --")
      (println (approve! actor "t4")))

    (println "== log-cargo-record shp-2 (unverified shipment -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-cargo-record :target-id "shp-2"
                                  :detail "pallet unload"} operator))

    (println "== schedule-handling-operation shp-3 (unregistered shipment -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :schedule-handling-operation :target-id "shp-3"
                                  :resource-request {:crane "fac-1"}} operator))

    (println "== coordinate-equipment-maintenance fac-2 (unregistered facility -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :coordinate-equipment-maintenance :target-id "fac-2"
                                  :maintenance-type "dock-calibration"} operator))

    (println "== defense-in-depth: hallucinated op / smuggled finalize action (governor/check directly) ==")
    (println "hallucinated op not in closed allowlist:"
             (governor/check {:op :log-cargo-record :target-id "shp-1"}
                             operator
                             {:operation :finalize-load-clearance :effect :propose
                              :target-id "shp-1" :confidence 0.9}
                             db))
    (println "proposal text smuggling a finalize-load-safety action:"
             (governor/check {:op :schedule-handling-operation :target-id "shp-1"}
                             operator
                             {:operation :schedule-handling-operation :effect :propose
                              :target-id "shp-1" :confidence 0.9
                              :rationale "recommend to finalize the load safety clearance now"}
                             db))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft cargo-log records ==")
    (doseq [r (store/cargo-log db)] (println r))

    (println "== draft schedule-log records ==")
    (doseq [r (store/schedule-log db)] (println r))

    (println "== draft concern-log records ==")
    (doseq [r (store/concern-log db)] (println r))

    (println "== draft maintenance-log records ==")
    (doseq [r (store/maintenance-log db)] (println r))))
