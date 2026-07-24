(ns cargohandling.store
  "SSoT for the cargo-handling operations-coordination actor (ISIC
  5224), behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every prior `cloud-itonami-isic-*` actor in
  this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/cargohandling/store_contract_test.clj), which is the whole
  point: the actor, the Cargo Handling Governor and the audit ledger
  never know which SSoT they run on.

  Two entity directories:
    - `shipment`  -- a manifest/consignment record (an independently
                     registered + verified handling-safety scope entry
                     for one shipment's terminal call: cargo
                     description, gross weight, hazmat class if any).
                     Targeted by `:log-cargo-record`,
                     `:schedule-handling-operation` and
                     `:flag-cargo-safety-concern`. MAY carry an
                     optional `:shipment/handoff` (the upstream
                     storage-terminal actor's own outbound `:handoff`
                     record, passed through unchanged -- see
                     `cargohandling.registry`'s Inbound Cross-Actor
                     Handoff section and `cargohandling.governor`'s
                     `storage-handoff-suspect-escalation`, ADR-2800002100).
    - `facility`  -- a crane/dock/warehouse record (gantry crane, reach
                     stacker, quay berth, warehouse bay, etc.).
                     Targeted by `:coordinate-equipment-maintenance` (a
                     facility-level op, the same 'facility-level ops
                     don't need a per-shipment record' exemption
                     `cloud-itonami-isic-561`'s governor establishes for
                     its own non-reservation ops).

  This actor is deliberately an OPERATIONS COORDINATION layer, not a
  load-safety authority: every commit below is a LOG / PROPOSAL /
  COORDINATION record, never a load-safety clearance, a weight-limit
  override or a hazmat-segregation waiver -- see
  `cargohandling.governor`'s `finalize-load-safety-violations` (a
  hard, permanent block) and the closed op-allowlist, which together
  make 'directly finalize a load-safety clearance' structurally
  unreachable from this actor.

  The ledger stays append-only on every backend: which shipment/
  facility was screened, which proposal committed or held, and on
  what basis, is always a query over an immutable log -- the audit
  trail a terminal operator, stevedoring licensee or regulator trusts
  this actor with."
  (:require [cargohandling.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (shipment [s id])
  (all-shipments [s])
  (facility [s id])
  (all-facilities [s])
  (ledger [s])
  (cargo-log [s] "the append-only committed :log-cargo-record history")
  (schedule-log [s] "the append-only committed :schedule-handling-operation history")
  (maintenance-log [s] "the append-only committed :coordinate-equipment-maintenance history")
  (concern-log [s] "the append-only committed :flag-cargo-safety-concern history (post human sign-off)")
  (next-sequence [s jurisdiction kind] "next record-number sequence for a jurisdiction + record kind")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-shipments [s shipments] "replace/seed the shipment directory (map id->shipment)")
  (with-facilities [s facilities] "replace/seed the facility directory (map id->facility)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained shipment + facility set covering the happy
  path plus the governor's own hard checks, so the actor + tests run
  offline. Each violation entity isolates exactly ONE failure mode
  (the rest stay clean), the 'exercise the failure mode directly,
  never only via a happy-path actuation' discipline every sibling
  governor's demo data establishes."
  []
  {:shipments
   {"shp-1" {:id "shp-1" :cargo-desc "containerized general cargo"
             :weight-kg 18500 :hazmat-class nil
             :jurisdiction "JPN" :registered? true :verified? true}
    "shp-2" {:id "shp-2" :cargo-desc "palletized machinery parts"
             :weight-kg 9200 :hazmat-class nil
             :jurisdiction "JPN" :registered? true :verified? false}
    "shp-3" {:id "shp-3" :cargo-desc "bulk chemical drums"
             :weight-kg 4200 :hazmat-class "8"
             :jurisdiction "JPN" :registered? false :verified? false}}
   :facilities
   {"fac-1" {:id "fac-1" :name "Gantry Crane 4" :kind "crane"
             :jurisdiction "JPN" :registered? true}
    "fac-2" {:id "fac-2" :name "Warehouse B Reefer Dock" :kind "warehouse"
             :jurisdiction "JPN" :registered? false}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- commit-kind!
  "Backend-agnostic record draft for `kind` (:cargo | :schedule |
  :maintenance | :concern) -- drafts the append-only record for
  `target-id`/`jurisdiction`/`seq-n` and returns {:result ..} for the
  caller to persist. Pure w.r.t. any particular backend's transaction
  mechanics (the backend has already resolved `jurisdiction` and
  `seq-n` via the protocol before calling this)."
  [kind target-id jurisdiction seq-n]
  (let [result (case kind
                 :cargo       (registry/register-cargo-record target-id jurisdiction seq-n)
                 :schedule    (registry/register-schedule-record target-id jurisdiction seq-n)
                 :maintenance (registry/register-maintenance-record target-id jurisdiction seq-n)
                 :concern     (registry/register-concern-record target-id jurisdiction seq-n))]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (shipment [_ id] (get-in @a [:shipments id]))
  (all-shipments [_] (sort-by :id (vals (:shipments @a))))
  (facility [_ id] (get-in @a [:facilities id]))
  (all-facilities [_] (sort-by :id (vals (:facilities @a))))
  (ledger [_] (:ledger @a))
  (cargo-log [_] (:cargo-log @a))
  (schedule-log [_] (:schedule-log @a))
  (maintenance-log [_] (:maintenance-log @a))
  (concern-log [_] (:concern-log @a))
  (next-sequence [_ jurisdiction kind] (get-in @a [:sequences kind jurisdiction] 0))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :shipment/upsert
      (swap! a update-in [:shipments (:id value)] merge value)

      :facility/upsert
      (swap! a update-in [:facilities (:id value)] merge value)

      (:cargo/log :schedule/propose :maintenance/coordinate :concern/record)
      (let [kind (case effect
                   :cargo/log :cargo
                   :schedule/propose :schedule
                   :maintenance/coordinate :maintenance
                   :concern/record :concern)
            target-id (first path)
            jurisdiction (or (:jurisdiction (shipment s target-id))
                             (:jurisdiction (facility s target-id)))
            seq-n (next-sequence s jurisdiction kind)
            {:keys [result]} (commit-kind! kind target-id jurisdiction seq-n)
            log-key (case kind
                      :cargo :cargo-log :schedule :schedule-log
                      :maintenance :maintenance-log :concern :concern-log)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences kind jurisdiction] (fnil inc 0))
                       (update log-key registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-shipments [s shipments] (when (seq shipments) (swap! a assoc :shipments shipments)) s)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s))

(defn seed-db
  "A MemStore seeded with the demo shipment/facility set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :sequences {}
                           :cargo-log [] :schedule-log []
                           :maintenance-log [] :concern-log []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

;; Schema, the EDN-blob codec and the shipment/facility entity
;; map<->tx<->pull are the shared kotoba-lang/langchain-store machinery
;; (ADR-2607141600) -- the seam ~190 actors hand-roll. Only the
;; shipment/facility field specs and the ledger/log/sequence attrs
;; (custom query shapes) are per-domain wiring.
(def ^:private schema
  (ls/identity-schema [:shipment/id :facility/id :ledger/seq
                       :cargo/seq :schedule/seq :maintenance/seq :concern/seq
                       :sequence/key]))

(def ^:private shipment-spec
  {:id {:attr :shipment/id}
   :cargo-desc {:attr :shipment/cargo-desc}
   :weight-kg {:attr :shipment/weight-kg}
   :hazmat-class {:attr :shipment/hazmat-class}
   :jurisdiction {:attr :shipment/jurisdiction}
   :registered? {:attr :shipment/registered? :coerce boolean}
   :verified? {:attr :shipment/verified? :coerce boolean}})

(def ^:private facility-spec
  {:id {:attr :facility/id}
   :name {:attr :facility/name}
   :kind {:attr :facility/kind}
   :jurisdiction {:attr :facility/jurisdiction}
   :registered? {:attr :facility/registered? :coerce boolean}})

(defn- shipment->tx [m] (ls/map->tx shipment-spec m))
(def ^:private shipment-pull (ls/pull-pattern shipment-spec))
(defn- pull->shipment [m] (ls/pull->map shipment-spec :id m))

(defn- facility->tx [m] (ls/map->tx facility-spec m))
(def ^:private facility-pull (ls/pull-pattern facility-spec))
(defn- pull->facility [m] (ls/pull->map facility-spec :id m))

(defn- seq-key [jurisdiction kind] (str (name kind) "::" jurisdiction))

(defrecord DatomicStore [conn]
  Store
  (shipment [_ id]
    (pull->shipment (d/pull (d/db conn) shipment-pull [:shipment/id id])))
  (all-shipments [_]
    (->> (d/q '[:find [?id ...] :where [?e :shipment/id ?id]] (d/db conn))
         (map #(pull->shipment (d/pull (d/db conn) shipment-pull [:shipment/id %])))
         (sort-by :id)))
  (facility [_ id]
    (pull->facility (d/pull (d/db conn) facility-pull [:facility/id id])))
  (all-facilities [_]
    (->> (d/q '[:find [?id ...] :where [?e :facility/id ?id]] (d/db conn))
         (map #(pull->facility (d/pull (d/db conn) facility-pull [:facility/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (cargo-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :cargo/seq ?s] [?e :cargo/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp ls/dec* second))))
  (schedule-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :schedule/seq ?s] [?e :schedule/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp ls/dec* second))))
  (maintenance-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :maintenance/seq ?s] [?e :maintenance/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp ls/dec* second))))
  (concern-log [_]
    (->> (d/q '[:find ?s ?r :where [?e :concern/seq ?s] [?e :concern/record ?r]] (d/db conn))
         (sort-by first) (mapv (comp ls/dec* second))))
  (next-sequence [_ jurisdiction kind]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :sequence/key ?k] [?e :sequence/next ?n]]
            (d/db conn) (seq-key jurisdiction kind))
        0))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :shipment/upsert
      (d/transact! conn [(shipment->tx value)])

      :facility/upsert
      (d/transact! conn [(facility->tx value)])

      (:cargo/log :schedule/propose :maintenance/coordinate :concern/record)
      (let [kind (case effect
                   :cargo/log :cargo
                   :schedule/propose :schedule
                   :maintenance/coordinate :maintenance
                   :concern/record :concern)
            target-id (first path)
            jurisdiction (or (:jurisdiction (shipment s target-id))
                             (:jurisdiction (facility s target-id)))
            seq-n (next-sequence s jurisdiction kind)
            {:keys [result]} (commit-kind! kind target-id jurisdiction seq-n)
            next-n (inc seq-n)
            seq-attr (case kind :cargo :cargo/seq :schedule :schedule/seq
                          :maintenance :maintenance/seq :concern :concern/seq)
            rec-attr (case kind :cargo :cargo/record :schedule :schedule/record
                          :maintenance :maintenance/record :concern :concern/record)
            log-count (case kind
                        :cargo (count (cargo-log s)) :schedule (count (schedule-log s))
                        :maintenance (count (maintenance-log s)) :concern (count (concern-log s)))]
        (d/transact! conn
                     [{:sequence/key (seq-key jurisdiction kind) :sequence/next next-n}
                      {seq-attr log-count rec-attr (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-shipments [s shipments]
    (when (seq shipments) (d/transact! conn (mapv shipment->tx (vals shipments)))) s)
  (with-facilities [s facilities]
    (when (seq facilities) (d/transact! conn (mapv facility->tx (vals facilities)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:shipments .. :facilities ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [shipments facilities]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-shipments shipments) (with-facilities facilities)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo shipment/facility set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
