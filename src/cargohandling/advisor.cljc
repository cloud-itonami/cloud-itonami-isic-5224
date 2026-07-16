(ns cargohandling.advisor
  "CargoHandlingAdvisor -- the *contained intelligence node* for the
  cargo-handling operations-coordination actor (ISIC 5224: loading,
  unloading and stevedoring of cargo at ports, airports and
  terminals -- distinct from the carrier that transports it).

  It drafts load/unload/manifest cargo-log entries, crane/dock/
  warehouse scheduling proposals, crane/handling-equipment
  maintenance-coordination proposals, and cargo-safety-concern flags
  (weight-limit exceedance, hazmat-segregation issue, load-securing
  failure). CRITICAL: it is a smart-but-untrusted advisor. It returns
  a *proposal* (with a rationale + the fields it cited), never a
  committed record and never a load-safety clearance, weight-limit
  override or hazmat-segregation waiver. Every output is censored
  downstream by `cargohandling.governor` before anything touches the
  SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  IMPORTANT (self-tripping-bug discipline, this repo's own governor
  history -- see `cargohandling.governor`): none of the rationale text
  below uses a finalize/declare/override/waive/authorize/grant/issue
  verb next to 'clearance'/'weight limit'/'hazmat segregation'/'load
  safe'/'cargo release' -- every disclaimer is phrased as what this
  proposal does NOT do, using verbs the governor's own scope-exclusion
  patterns do not scan for, so a legitimate default proposal never
  matches its own governor's finalization-action patterns.
  `test/cargohandling/scope_exclusion_test.clj` asserts this directly
  for every op below.

  Proposal shape (all ops):
    {:operation  kw             ; one of the closed op-allowlist
     :effect     :propose       ; ALWAYS :propose -- structurally checked too
     :target-id  str            ; the shipment/facility id
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :confidence 0..1}"
  (:require [cargohandling.store :as store]))

(defn advise-log-cargo-record
  "Draft a load/unload/manifest CARGO-LOG proposal -- administrative
  data logging only."
  [db {:keys [target-id detail]}]
  (let [c (store/shipment db target-id)]
    {:operation :log-cargo-record
     :effect :propose
     :target-id target-id
     :detail detail
     :summary (str target-id " の荷役記録(積込/取卸/マニフェスト)ログ提案")
     :rationale "Administrative logging of load/unload/manifest data only; no load-safety determination is made by this proposal."
     :confidence (if c 0.95 0.2)}))

(defn advise-schedule-handling-operation
  "Draft a crane/dock/warehouse SCHEDULING proposal -- a proposal
  only."
  [db {:keys [target-id resource-request]}]
  (let [c (store/shipment db target-id)]
    {:operation :schedule-handling-operation
     :effect :propose
     :target-id target-id
     :resource-request resource-request
     :summary (str target-id " 向けクレーン/バース/倉庫スケジューリング提案")
     :rationale "Crane/dock/warehouse scheduling proposal only; does not commit any equipment dispatch and makes no load-safety determination."
     :confidence (if c 0.9 0.2)}))

(defn advise-flag-cargo-safety-concern
  "Draft a cargo-safety-concern flag -- ALWAYS escalates to human
  sign-off. This is a REAL-WORLD safety-relevant signal (a reported
  weight-limit exceedance, hazmat-segregation issue or load-securing
  failure), never a draft the actor may auto-run and never itself a
  load-safety or equipment status change. See `cargohandling.phase`:
  no phase ever adds this op to a phase's `:auto` set; the governor
  also always escalates on this op. Two independent layers agree,
  deliberately."
  [db {:keys [target-id concern-type description]}]
  (let [c (store/shipment db target-id)]
    {:operation :flag-cargo-safety-concern
     :effect :propose
     :target-id target-id
     :concern-type concern-type
     :description description
     :summary (str target-id " について積荷安全上の懸念(" concern-type ")を提起")
     :rationale "Surfaces a cargo-safety concern for mandatory human review; takes no independent action on load-safety or equipment status."
     :confidence (if c 0.98 0.5)}))

(defn advise-coordinate-equipment-maintenance
  "Draft a crane/handling-equipment MAINTENANCE-COORDINATION proposal
  -- coordination only, never a fitness certification."
  [db {:keys [target-id maintenance-type]}]
  (let [f (store/facility db target-id)]
    {:operation :coordinate-equipment-maintenance
     :effect :propose
     :target-id target-id
     :maintenance-type maintenance-type
     :summary (str target-id " の保守調整(" maintenance-type ")提案")
     :rationale "Crane/handling-equipment maintenance coordination only; does not certify equipment fitness or load-safety status."
     :confidence (if f 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :target-id id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-cargo-record                 (advise-log-cargo-record db request)
    :schedule-handling-operation      (advise-schedule-handling-operation db request)
    :flag-cargo-safety-concern        (advise-flag-cargo-safety-concern db request)
    :coordinate-equipment-maintenance (advise-coordinate-equipment-maintenance db request)
    {:operation :noop :effect :propose :target-id nil
     :summary "未対応の操作" :rationale (str op) :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :target-id  (:target-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :confidence (:confidence proposal)})
