(ns cargohandling.registry
  "Pure-function record construction for the cargo-handling
  operations-coordination actor -- an append-only book-of-record
  draft for each of the four coordination ops.

  Like every sibling actor's registry, there is no single
  international reference-number standard for a load/unload-manifest
  log entry, a crane/dock/warehouse scheduling proposal, a
  maintenance-coordination record or a cargo-safety-concern record --
  every terminal operator/stevedoring licensee assigns its own
  reference format. This namespace does NOT invent one beyond a
  jurisdiction-scoped sequence number; it drafts the record's required
  fields honestly, the same non-fabricating discipline every sibling
  registry uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real terminal-operating-system / port-community system.
  It builds the RECORD an operator would keep, not a real-world act.
  Every record produced here is UNSIGNED and explicitly `:kind
  :*-draft` -- it is never, itself, a load-safety clearance, a
  weight-limit override or a hazmat-segregation waiver. That authority
  stays outside this actor entirely (see `cargohandling.governor`'s
  `finalize-load-safety-violations`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn- require-field! [op-label field-name v]
  (when-not (and v (not= v ""))
    (throw (ex-info (str op-label ": " field-name " required") {}))))

(defn- draft-record
  "Shared record-draft shape for all four coordination record kinds.
  `label` names the record kind (e.g. \"cargo-log\"); `target-id` is
  the shipment/facility id the record concerns."
  [label prefix target-id jurisdiction sequence]
  (require-field! label "target_id" target-id)
  (require-field! label "jurisdiction" jurisdiction)
  (when (< sequence 0)
    (throw (ex-info (str label ": sequence must be >= 0") {})))
  (let [record-number (str (str/upper-case jurisdiction) "-" prefix "-" (zero-pad sequence 6))
        record {"record_id" record-number
                "kind" (str label "-draft")
                "target_id" target-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "record_number" record-number
     "certificate" (unsigned-certificate label record-number record-number)}))

(defn register-cargo-record
  "Draft a load/unload/manifest CARGO-LOG entry -- administrative
  record-keeping only, never a load-safety clearance decision."
  [shipment-id jurisdiction sequence]
  (draft-record "cargo-log" "CARGO" shipment-id jurisdiction sequence))

(defn register-schedule-record
  "Draft a crane/dock/warehouse SCHEDULING PROPOSAL -- a proposal
  only, never an equipment-dispatch authorization."
  [shipment-id jurisdiction sequence]
  (draft-record "schedule-proposal" "SCHEDULE" shipment-id jurisdiction sequence))

(defn register-maintenance-record
  "Draft a crane/handling-equipment MAINTENANCE-COORDINATION record --
  coordination only, never an equipment fitness certification."
  [facility-id jurisdiction sequence]
  (draft-record "maintenance-coordination" "MAINT" facility-id jurisdiction sequence))

(defn register-concern-record
  "Draft the record of a CARGO-SAFETY-CONCERN flag, written only after
  mandatory human sign-off -- the flag itself, never a load-safety or
  equipment status change."
  [shipment-id jurisdiction sequence]
  (draft-record "cargo-safety-concern" "CONCERN" shipment-id jurisdiction sequence))

(defn append [history result]
  (conj (vec history) (get result "record")))

;; ─────── Inbound Cross-Actor Handoff (optional, isic-5210 -> isic-5224) ───────
;;
;; A `shipment` record MAY OPTIONALLY carry a `:shipment/handoff` map
;; when the upstream storage-terminal actor (cloud-itonami-isic-5210,
;; petroleum/bulk storage) transferred custody of the stock to this
;; actor. Reuses the SAME `:handoff/*` wire shape already established
;; fleet-wide by the isic-1075<->jsic-4721 handoff (superproject ADR-
;; 2607177600, extended by ADR-2800000500/2800000700/2800000800) --
;; documented for this actor pair by superproject ADR-2800002100. A
;; `:shipment/handoff` is OPTIONAL, not required: every existing
;; shipment record and every existing `:log-cargo-record` call keeps
;; working unchanged with no `:shipment/handoff` attached at all --
;; absence is never flagged (see `cargohandling.governor`'s
;; `storage-handoff-suspect-escalation`, a SOFT check, never a HARD
;; hold).
;;
;;   {:handoff/id "..."
;;    :handoff/source-actor "cloud-itonami-isic-5210"
;;    :handoff/batch-id "..."
;;    :handoff/product-type-id :some/keyword-or-string
;;    :handoff/quantity-kg 120.5
;;    :handoff/dispatched-at-iso "..."}

(defn handoff-record-well-formed?
  "Positive-sense convenience predicate: does `handoff` carry every
  REQUIRED `:handoff/*` field (id/source-actor/batch-id/product-type-id/
  quantity-kg/dispatched-at-iso) with a plausible value (quantity-kg a
  positive number, the string fields non-blank)? Never validates the
  OPTIONAL cold-chain/unspsc/gtin/carrier pass-through fields."
  [handoff]
  (boolean
   (and (map? handoff)
        (seq (:handoff/id handoff))
        (seq (:handoff/source-actor handoff))
        (seq (:handoff/batch-id handoff))
        (some? (:handoff/product-type-id handoff))
        (number? (:handoff/quantity-kg handoff))
        (pos? (:handoff/quantity-kg handoff))
        (seq (:handoff/dispatched-at-iso handoff)))))

(def storage-handoff-source-actors
  "Which upstream cloud-itonami actors this cargo-handling actor recognizes
  as a legitimate :handoff/source-actor for an inbound consignment (ADR-2800002100)."
  #{"cloud-itonami-isic-5210"})

(defn storage-handoff-source-actor-known?
  [source-actor]
  (boolean (contains? storage-handoff-source-actors source-actor)))
