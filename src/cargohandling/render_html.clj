(ns cargohandling.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`cargohandling.operation` -> `cargohandling.governor` ->
  `cargohandling.store`) through a scenario adapted from this repo's own
  `cargohandling.sim` demo driver (`clojure -M:dev:run`, confirmed before
  this file was written to run correctly against the real seeded
  shipment/facility directory -- ids `shp-1`/`shp-2`/`shp-3`/`fac-1`/
  `fac-2` all exist in `cargohandling.store/demo-data`, so, unlike
  `cloud-itonami-isic-851`'s original `schoolops.sim`, no dangling-id bug
  was found here) and rendered deterministically -- no invented numbers,
  no timestamps in the page content, byte-identical across reruns
  against the same seed (verify by diffing two consecutive runs before
  shipping).

  HARD-hold coverage note: this governor has FOUR violation checks
  (`cargohandling.governor` docstring), but only two of them --
  `:shipment-unverified` (unregistered or unverified shipment) and
  `:facility-unregistered` (unregistered facility, for the one
  facility-level op) -- are reachable through a REAL request routed
  through the real `cargohandling.advisor` mock: the other two
  (`:effect-not-propose`, `:op-not-allowed`, `:finalize-load-safety-
  attempt`) are, by this governor's own design, structurally
  unreachable from a legitimate advisor-generated proposal (the mock
  advisor always emits `:effect :propose` and one of the four allowlisted
  ops, and its rationale text is deliberately phrased to never match the
  scope-exclusion patterns -- see `cargohandling.governor` and
  `cargohandling.advisor` docstrings, and
  `test/cargohandling/scope_exclusion_test.clj`). `cargohandling.sim`
  itself only reaches those via a *direct* `governor/check` call with a
  hand-built proposal, not via `exec!` through the actor -- this renderer
  does not invent an op or a proposal to manufacture a third exec!-path
  HARD hold; it exercises exactly the two rule keywords a real request
  can trip.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [cargohandling.store :as store]
            [cargohandling.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness -----------------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :terminal-operator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: shp-1 (registered + verified) clears a
  cargo-record log (auto-commit at phase 3, pure administrative logging,
  no capital risk), then a crane/dock/warehouse handling-operation
  scheduling proposal (a real resource commitment -- ALWAYS escalates,
  even governor-clean, at every phase including 3 -- approved), then a
  cargo-safety-concern flag (ALWAYS escalates per both
  `governor/high-stakes` and `phase/phases` never adding it to any
  `:auto` set -- approved); fac-1 (registered) clears an
  equipment-maintenance coordination proposal (a real maintenance
  dispatch -- ALWAYS escalates -- approved). Then two independent
  HARD-hold reasons, each never reaching a human: shp-2 (registered but
  NOT `:verified?` in the seed data) HARD-holds `:log-cargo-record` on
  `:shipment-unverified`; fac-2 (NOT `:registered?` in the seed data)
  HARD-holds `:coordinate-equipment-maintenance` on
  `:facility-unregistered`. Returns the resulting store -- every field
  read by `render` below is real governor/store output, not a hand-typed
  copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "shp1-log" {:op :log-cargo-record :target-id "shp-1"
                              :detail "container discharge completed"})

    (exec! actor "shp1-schedule" {:op :schedule-handling-operation :target-id "shp-1"
                                   :resource-request {:crane "fac-1" :dock "B-12" :shift 1}})
    (approve! actor "shp1-schedule")

    (exec! actor "shp1-concern" {:op :flag-cargo-safety-concern :target-id "shp-1"
                                  :concern-type "weight-limit-exceedance"
                                  :description "reported gross weight exceeds crane rated capacity"})
    (approve! actor "shp1-concern")

    (exec! actor "fac1-maint" {:op :coordinate-equipment-maintenance :target-id "fac-1"
                                :maintenance-type "crane-inspection"})
    (approve! actor "fac1-maint")

    (exec! actor "shp2-log" {:op :log-cargo-record :target-id "shp-2"
                              :detail "pallet unload"})

    (exec! actor "fac2-maint" {:op :coordinate-equipment-maintenance :target-id "fac-2"
                                :maintenance-type "dock-calibration"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger target-id]
  (last (filter #(= (:target-id %) target-id) ledger)))

(defn- status-cell [ledger target-id]
  (let [f (last-fact-for ledger target-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (case rule
          :shipment-unverified "<span class=\"critical\">HARD hold &middot; shipment unverified</span>"
          :facility-unregistered "<span class=\"critical\">HARD hold &middot; facility unregistered</span>"
          (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- shipment-row [ledger {:keys [id cargo-desc weight-kg hazmat-class jurisdiction registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc cargo-desc) (esc weight-kg) (if hazmat-class (esc hazmat-class) "&mdash;") (esc jurisdiction)
          (cond (and registered? verified?) "<span class=\"ok\">registered &amp; verified</span>"
                registered? "<span class=\"warn\">registered, unverified</span>"
                :else "<span class=\"err\">not registered</span>")
          (status-cell ledger id)))

(defn- facility-row [ledger {:keys [id name kind jurisdiction registered?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc name) (esc kind) (esc jurisdiction)
          (if registered? "<span class=\"ok\">registered</span>" "<span class=\"err\">not registered</span>")
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op target-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc target-id)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Ops` table, `cargohandling.governor`/`cargohandling.phase`)
  ;; -- documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-cargo-record</code></td><td><span class=\"ok\">phase-3 auto when clean &middot; administrative logging only</span></td></tr>"
   "        <tr><td><code>:schedule-handling-operation</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real resource commitment</span></td></tr>"
   "        <tr><td><code>:flag-cargo-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"
   "        <tr><td><code>:coordinate-equipment-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real maintenance dispatch</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        shipments (store/all-shipments db)
        facilities (store/all-facilities db)
        shipment-rows (str/join "\n" (map (partial shipment-row ledger) shipments))
        facility-rows (str/join "\n" (map (partial facility-row ledger) facilities))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-5224 &middot; cargo-handling operations coordination</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Cargo handling operations coordination (ISIC 5224) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never finalizes a load-safety clearance, weight-limit override or hazmat-segregation waiver</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Shipments</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>cargohandling.store</code> via <code>cargohandling.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Shipment</th><th>Cargo</th><th>Weight (kg)</th><th>Hazmat class</th><th>Jurisdiction</th><th>Registration</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     shipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Facilities</h2>\n"
     "    <p class=\"muted\">Cranes, docks and warehouse bays targeted by <code>:coordinate-equipment-maintenance</code> (a facility-level op, independently re-verified against the facility record, not a per-shipment record).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Facility</th><th>Name</th><th>Kind</th><th>Jurisdiction</th><th>Registration</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     facility-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Cargo Handling Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. This actor is an operations-coordination layer, never a load-safety authority — no op in its closed allowlist can finalize a load-safety clearance, override a weight-limit rule or waive a hazmat-segregation rule.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Target</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/cargo-log db)) "cargo-log records,"
             (count (store/schedule-log db)) "schedule records,"
             (count (store/concern-log db)) "concern records,"
             (count (store/maintenance-log db)) "maintenance records )")))
