(ns cargohandling.governor
  "Cargo Handling Governor (`:itonami.blueprint/governor
  :cargo-handling-governor`, matching this repo's own `blueprint.edn`
  -- grep-verified unique within this repo) -- the independent
  compliance layer that earns the CargoHandlingAdvisor the right to
  commit. The LLM has no notion of whether a shipment/facility record
  is actually independently registered and verified, whether a
  proposal actually stays inside the closed coordination-op
  allowlist, whether a proposal's own text quietly tries to finalize a
  load-safety clearance decision, or when a cargo-safety concern must
  escalate rather than commit, so this MUST be a separate system able
  to *reject* a proposal and fall back to HOLD.

  This is an OPERATIONS COORDINATION actor for cargo handling
  (loading/unloading/stevedoring of cargo at ports/airports/
  terminals), not a load-safety authority and not the transport
  carrier itself -- it never itself finalizes a load-safety clearance,
  overrides a weight-limit rule, or waives a hazmat-segregation rule.
  Four checks, in priority order, ALL HARD violations (a human
  approver CANNOT override them):

    1. Shipment/facility record unverified -- the target shipment
       (`:log-cargo-record`, `:schedule-handling-operation`,
       `:flag-cargo-safety-concern`) must be independently
       :registered? AND :verified? in the store, re-derived from the
       REQUEST every time, never from the proposal's self-report.
       `:coordinate-equipment-maintenance` is facility-level (the same
       'facility-level ops don't need per-target verification of the
       SAME kind of record' exemption `cloud-itonami-isic-561`'s
       governor establishes for its own non-reservation ops) -- it
       independently re-verifies the target FACILITY is :registered?
       instead.
    2. Effect not :propose         -- rejected outright, whatever the
                                       advisor claims.
    3. Closed op-allowlist         -- the proposal's own :operation
                                       must be one of the four allowed
                                       ops; anything else (including a
                                       hallucinated op) is a hard,
                                       permanent block.
    4. Finalize-load-safety scope
       exclusion                    -- ANY proposal whose text tries to
                                       finalize a load-safety
                                       clearance, override a
                                       weight-limit rule, or waive a
                                       hazmat-segregation rule is a
                                       hard, permanent block -- this
                                       territory structurally does not
                                       exist as an op in this actor at
                                       all (see closed op-allowlist
                                       above), and this check catches
                                       any attempt to smuggle a
                                       finalization ACTION into an
                                       otherwise-legitimate proposal's
                                       own text.

  A fifth, SOFT (escalate-only, never a hold) check was added
  additively for `:log-cargo-record` (ADR-2800002100): when the target
  shipment's own record carries an OPTIONAL `:shipment/handoff` --
  provenance/audit-trail data the upstream storage-terminal actor
  `cloud-itonami-isic-5210` (petroleum/bulk storage) may attach when it
  transfers custody of stock to this actor -- and that handoff IS
  present but is either structurally malformed or declares a
  `:handoff/source-actor` this actor does not recognize as a
  registered upstream storage terminal, the proposal escalates to a
  human instead of auto-committing (never held). Absence of a
  `:shipment/handoff` is completely normal and is never flagged -- see
  `storage-handoff-suspect-escalation` below and
  `cargohandling.registry`'s `handoff-record-well-formed?`/
  `storage-handoff-source-actor-known?`.

  ONE known self-tripping bug class this check is written to AVOID
  (multiple sibling `cloud-itonami-isic-*` actors in this fleet
  independently hit and fixed the SAME bug): phrasing an exclusion
  term as a bare noun ('safety', 'clearance', 'hazmat') makes it match
  inside the mock advisor's own DEFAULT rationale/disclaimer text for
  a legitimate, allowed proposal -- e.g. a `:flag-cargo-safety-concern`
  proposal's own honest rationale legitimately says the words 'cargo
  safety concern' and 'hazmat', so a bare-noun pattern list would
  self-block the actor's own happy path. Every pattern below is
  phrased as the FINALIZATION/EXECUTION ACTION ('finalize the
  load-safety clearance', 'override the weight-limit rule', 'waive the
  hazmat-segregation rule'), never the bare noun --
  `test/cargohandling/scope_exclusion_test.clj` asserts directly that
  every one of `cargohandling.advisor`'s four default proposals passes
  this check cleanly.

  The confidence/escalation gate is SOFT: it asks a human to look (low
  confidence, or `:flag-cargo-safety-concern` which is ALWAYS
  high-stakes) -- see `cargohandling.phase` for the belt-and-suspenders
  second layer: `:flag-cargo-safety-concern` is never a member of any
  phase's `:auto` set either."
  (:require [cargohandling.store :as store]
            [cargohandling.registry :as registry]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed op-allowlist. Anything else is a hard, permanent block
  (check 3)."
  #{:log-cargo-record
    :schedule-handling-operation
    :flag-cargo-safety-concern
    :coordinate-equipment-maintenance})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Flagging a cargo-safety concern always escalates to human sign-off
  -- never in any phase's :auto set either (belt and suspenders,
  `cargohandling.phase`)."
  #{:flag-cargo-safety-concern})

(def facility-level-ops
  "Ops whose target is a `facility` record, not a per-shipment
  `shipment` record -- exempt from shipment-verification (check 1
  instead independently re-verifies the target FACILITY is
  :registered?)."
  #{:coordinate-equipment-maintenance})

;; ----------------------------- checks -----------------------------

(defn- shipment-unverified-violations
  "Check 1: re-derived from the store, never from proposal
  self-report. Facility-level ops (`:coordinate-equipment-maintenance`)
  independently re-verify the target FACILITY is :registered? instead
  of a per-shipment record."
  [{:keys [op target-id]} st]
  (if (contains? facility-level-ops op)
    (let [f (store/facility st target-id)]
      (when-not (and f (:registered? f))
        [{:rule :facility-unregistered
          :detail (str target-id " は登録済みfacilityとして独立検証できません")}]))
    (let [c (store/shipment st target-id)]
      (cond
        (nil? c)
        [{:rule :shipment-unverified
          :detail (str target-id " のshipment記録が見つかりません")}]

        (not (:registered? c))
        [{:rule :shipment-unverified
          :detail (str target-id " のshipmentは未登録です")}]

        (not (:verified? c))
        [{:rule :shipment-unverified
          :detail (str target-id " のshipmentは未検証です")}]

        :else []))))

(defn- effect-not-propose-violations
  "Check 2: the proposal's own :effect must be :propose, whatever the
  advisor claims elsewhere."
  [proposal]
  (when (not= (:effect proposal) :propose)
    [{:rule :effect-not-propose
      :detail (str "effectが" (:effect proposal) "であり:proposeではありません")}]))

(defn- closed-allowlist-violations
  "Check 3: the proposal's own :operation must be one of the four
  allowed coordination ops. Anything else -- including a hallucinated
  op a real LLM advisor might propose -- is a hard, permanent block."
  [proposal]
  (when-not (contains? allowed-ops (:operation proposal))
    [{:rule :op-not-allowed
      :detail (str (:operation proposal) " はクローズドop許可リストに含まれません")}]))

(def ^:private finalize-load-safety-patterns
  "Forbidden FINALIZATION/EXECUTION ACTION phrases -- phrased as the
  action, never a bare noun, so a legitimate proposal's own honest
  rationale (which may legitimately mention 'cargo safety concern',
  'hazmat', 'weight limit' etc. as NOUNS) never self-trips this check.
  See the namespace docstring's self-tripping-bug note."
  [;; EN -- verb + load/cargo-safety/weight-limit/hazmat/release object
   #"(?i)finalize\s+(the\s+)?(load|cargo)[\w\s-]*safety[\w\s-]*clearance"
   #"(?i)finalize\s+(the\s+)?(load|cargo)[\w\s-]*clearance"
   #"(?i)declare\s+(the\s+)?(load|cargo)\s+(as\s+)?safe(ly\s+secured)?"
   #"(?i)clear\s+the\s+load\s+as\s+safely\s+secured"
   #"(?i)override\s+(the\s+)?weight[\w\s-]*limit[\w\s-]*(rule|requirement)"
   #"(?i)waive\s+(the\s+)?hazmat[\w\s-]*segregation[\w\s-]*(rule|requirement)"
   #"(?i)authorize\s+(the\s+)?cargo[\w\s-]*release"
   #"(?i)grant\s+(the\s+)?(load|cargo)[\w\s-]*safety[\w\s-]*clearance"
   #"(?i)issue\s+(the\s+)?(load|cargo)[\w\s-]*safety[\w\s-]*clearance"
   ;; JA -- same discipline: action verb + object, never a bare noun
   #"積荷.{0,6}安全.{0,6}確定"
   #"荷役.{0,6}安全.{0,6}確定"
   #"重量制限.{0,6}(解除|免除)"
   #"危険物.{0,6}分離.{0,6}(解除|免除)"
   #"貨物リリース.{0,6}許可"])

(defn- finalize-load-safety-violations
  "Check 4: scan the proposal's own text for a smuggled finalization
  ACTION. Structurally unreachable via the closed op-allowlist alone
  (check 3) since no such op exists at all -- this check is
  defense-in-depth against a real LLM advisor embedding a
  finalization claim inside an otherwise-legitimate proposal's
  :summary/:rationale text."
  [proposal]
  (let [text (pr-str proposal)]
    (when (some #(re-find % text) finalize-load-safety-patterns)
      [{:rule :finalize-load-safety-attempt
        :detail "提案テキストが積荷/荷役安全クリアランスの確定アクションを含んでいます -- 恒久ブロック"}])))

;; ----------------------------- SOFT check (escalate, not hold) -----------------------------

(defn- storage-handoff-suspect-escalation
  "SOFT -- only evaluated for :log-cargo-record, and only when the
  target shipment's own record actually carries a :shipment/handoff map
  (ADR-2800002100). Absence is never flagged: attaching a :handoff to a
  shipment is an entirely OPTIONAL provenance/audit-trail attachment
  from the upstream storage-terminal actor (cloud-itonami-isic-5210)
  that transferred custody of the stock, not a precondition of logging
  a cargo record. Escalates (never holds) when the handoff IS present
  but is either structurally malformed
  (`cargohandling.registry/handoff-record-well-formed?` false) or
  declares a `:handoff/source-actor` this actor does not recognize as a
  registered upstream storage terminal
  (`cargohandling.registry/storage-handoff-source-actor-known?`
  false)."
  [{:keys [op target-id]} st]
  (when (= op :log-cargo-record)
    (let [s (store/shipment st target-id)
          handoff (:shipment/handoff s)]
      (when (map? handoff)
        (when-not (and (registry/handoff-record-well-formed? handoff)
                       (registry/storage-handoff-source-actor-known?
                        (:handoff/source-actor handoff)))
          [{:rule :storage-handoff-suspect
            :detail (str target-id " のshipmentに添付された:shipment/handoffが構造不整合、"
                         "または登録済みsource-actorでない(ADR-2800002100)")}])))))

;; ----------------------------- decision logic -----------------------------

(defn check
  "Censors a CargoHandlingAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :soft-violations [..] :confidence c
  :escalate? bool :high-stakes? bool :hard? bool}.

  `:violations` (`hard`) are the four un-overridable HOLDs above,
  unchanged. `:soft-violations` are additional, independently-detected
  concerns (currently just `:storage-handoff-suspect`) that are NOT
  grounds for a hold but DO force `:escalate?` true even when every
  hard check passes, confidence is high, and the op is not itself
  high-stakes -- same effect as low confidence, kept in a separate key
  so `:violations` keeps meaning exactly what it always has (a hard,
  un-overridable hold reason)."
  [request _context proposal st]
  (let [hard (into []
                   (concat (shipment-unverified-violations request st)
                           (effect-not-propose-violations proposal)
                           (closed-allowlist-violations proposal)
                           (finalize-load-safety-violations proposal)))
        soft (into [] (concat (storage-handoff-suspect-escalation request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:op request)))
        hard? (boolean (seq hard))
        soft? (boolean (seq soft))]
    {:ok?             (and (not hard?) (not low?) (not stakes?) (not soft?))
     :violations      hard
     :soft-violations soft
     :confidence      conf
     :hard?           hard?
     :escalate?       (and (not hard?) (or low? stakes? soft?))
     :high-stakes?    stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :target-id  (:target-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
