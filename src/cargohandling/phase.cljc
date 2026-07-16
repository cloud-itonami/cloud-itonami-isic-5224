(ns cargohandling.phase
  "Phase 0->3 staged rollout for the cargo-handling operations-
  coordination actor.

    Phase 0  read-only            -- no writes, still governor-gated.
    Phase 1  assisted-logging     -- cargo-record logging and
                                      cargo-safety-concern flagging
                                      allowed, every write needs human
                                      approval.
    Phase 2  assisted-scheduling  -- adds crane/dock/warehouse
                                      handling-operation scheduling
                                      proposal writes, still approval.
    Phase 3  supervised-auto      -- governor-clean, high-confidence
                                      `:log-cargo-record` (pure
                                      administrative logging, no
                                      capital risk, no load-safety
                                      determination) may auto-commit.
                                      `:schedule-handling-operation`
                                      and
                                      `:coordinate-equipment-maintenance`
                                      (real resource commitments --
                                      crane/dock/warehouse assignment,
                                      maintenance dispatch) ALWAYS need
                                      human approval, even when
                                      governor-clean, at every phase
                                      including 3.

  `:flag-cargo-safety-concern` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Surfacing a reported
  weight-limit exceedance, hazmat-segregation issue or load-securing
  failure ALWAYS reaches a human; this actor never itself acts on the
  concern (`cargohandling.governor`'s `high-stakes` set enforces the
  same invariant independently -- two layers, not one, agree on this).
  Likewise, no op that would finalize a load-safety clearance decision
  exists ANYWHERE in this domain's op set at all (see
  `cargohandling.governor`'s closed op-allowlist +
  `finalize-load-safety-violations`), so there is no entry to
  accidentally add to `:auto` in the first place -- the strongest
  possible form of 'never auto-commit-eligible'.")

(def read-ops  #{})
(def write-ops #{:log-cargo-record :schedule-handling-operation
                 :flag-cargo-safety-concern :coordinate-equipment-maintenance})

;; NOTE the invariant: `:flag-cargo-safety-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there. Likewise no op
;; that finalizes a load-safety/weight-limit/hazmat-segregation
;; decision exists in `write-ops` at all -- see the namespace
;; docstring.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                                                       :auto #{}}
   1 {:label "assisted-logging"    :writes #{:log-cargo-record :flag-cargo-safety-concern}                          :auto #{}}
   2 {:label "assisted-scheduling" :writes #{:log-cargo-record :flag-cargo-safety-concern :schedule-handling-operation} :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-cargo-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-cargo-safety-concern` is never auto-eligible at any phase,
    so it always escalates once the governor clears it (or holds if
    the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Cargo Handling Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
