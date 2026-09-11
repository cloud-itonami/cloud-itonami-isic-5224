(ns cargohandling.operation
  "OperationActor -- one cargo-handling coordination operation = one
  supervised actor run, expressed as a langgraph-clj StateGraph. The
  advisor (CargoHandlingAdvisor) is sealed into a single node
  (:advise); its proposal is ALWAYS routed through the Cargo Handling
  Governor (:govern) and the rollout phase gate (:decide) before
  anything commits to the SSoT.

  Everything the actor depends on is injected, so each is a swap, not
  a rewrite:
    - the Store    (MemStore today; Datomic/kotoba-server is the next seam) - `store` arg
    - the Advisor  (mock | real LLM)                                       - :advisor opt
    - the Phase    (0->3 rollout)                                          - :phase in ctx

  One graph run = one coordination operation (intake -> advise ->
  govern -> decide -> commit | hold | approval). No unbounded inner
  loop -- each operation is auditable and checkpointed.

  Human-in-the-loop = real approval workflow:
  `interrupt-before #{:request-approval}` pauses the actor and hands
  the decision to a human operator (terminal operator / stevedoring
  supervisor). The approver resumes with `{:approval {:status
  :approved}}` (or :rejected). `:flag-cargo-safety-concern` ALWAYS
  reaches this node when the governor is clean -- see
  `cargohandling.phase`."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [cargohandling.advisor :as advisor]
            [cargohandling.governor :as governor]
            [cargohandling.phase :as phase]
            [cargohandling.store :as store]))

(defn- op->store-effect
  "Map a coordination op to its store commit-effect key. The
  proposal's OWN :effect field stays the literal :propose throughout
  -- this mapping is an operation-layer concern, not something the
  advisor ever controls (governor check 2 enforces `:effect :propose`
  on the proposal independent of this)."
  [op]
  (case op
    :log-cargo-record                 :cargo/log
    :schedule-handling-operation      :schedule/propose
    :coordinate-equipment-maintenance :maintenance/coordinate
    :flag-cargo-safety-concern        :concern/record))

(defn- commit-fact [request context proposal]
  {:t          :committed
   :op         (:op request)
   :actor      (:actor-id context)
   :target-id  (:target-id request)
   :disposition :commit
   :summary    (:summary proposal)
   :confidence (:confidence proposal)})

(defn- commit-record [request _context _proposal]
  {:effect (op->store-effect (:op request))
   :path   [(:target-id request)]})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `cargohandling.store/Store`).
  opts:
    :advisor      -- a `cargohandling.advisor/Advisor` (default: mock-advisor)
    :checkpointer -- langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id/role/phase
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}   ; :commit | :hold | :escalate
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; CargoHandlingAdvisor inference (the contained intelligence node) -- proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-advise advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; Cargo Handling Governor -- independent censor (separate system than the LLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal store)}))

      ;; Decide: governor disposition, then the rollout-phase gate (which can
      ;; only add caution). HARD governor violations -> HOLD (no override).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (governor/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :target-id (:target-id request)
                        :reason (or reason
                                    (cond (:high-stakes? verdict) :actuation
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      ;; Approval handoff -- paused by interrupt-before; a human operator
      ;; resumes with :approval. Then route commit/hold.
      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :approved-by (:by approval))
             :audit [{:t :approval-granted :op (:op request)
                      :target-id (:target-id request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (governor/hold-fact request context
                                                (assoc verdict :violations
                                                       [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit -- the ONLY node that writes the SSoT + audit ledger.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      ;; Hold -- write the rejection to the ledger; no SSoT mutation.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:governor-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit   :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer     checkpointer
        :interrupt-before #{:request-approval}})))
