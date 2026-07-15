# ADR-0001: OtherTransportAdvisor ⊣ Other Transport Equipment Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-3099` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-3099` publishes an OSS blueprint for other-
transport-equipment (animal-drawn and hand-propelled vehicle) **plant
operations coordination** (production-batch product-category/weight-
capacity/quantity/assembly-defect-rate data logging, assembly-line-
equipment maintenance scheduling, safety-concern flagging, and
outbound shipment coordination). Like every actor in this fleet, the
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor +
Phase 0->3 rollout pattern established across the cloud-itonami
fleet.

ISIC 3099 ("Manufacture of other transport equipment n.e.c.") is a
RESIDUAL class: animal-drawn vehicles (carts, wagons, carriages) and
hand-propelled vehicles (hand-carts, hand-trucks, wheelbarrows,
rickshaws, sledges, toboggans, pushcarts). It explicitly EXCLUDES
bicycles and invalid carriages (manual/power wheelchairs, mobility
scooters), which are ISIC 3092 (`cloud-itonami-isic-3092`), a
distinct, already-implemented actor in this fleet — confirmed by
reading `kotoba-lang/industry`'s live registry entries for both
`"3092"` and `"3099"` before any code was written for this repo.

The closest domain analog is `cloud-itonami-isic-3092` (Manufacture
of bicycles and invalid carriages): both are back-office coordination
actors for a fixed assembly plant with a real physical safety
dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/
`:flag-safety-concern`/`:coordinate-shipment`) and the same
two-entity verified/registered gate structure (equipment for
maintenance scheduling, batch for shipment coordination). This build
mirrors `cloud-itonami-isic-3092`'s architecture closely but adapts
the hazard profile, equipment vocabulary and product-category set to
the other-transport-equipment plant: this vertical's central physical
hazard is chassis/axle/wheel/body assembly-line work and structural/
load test-bench inspection (materials-safety and structural-integrity
hazard, not 3092's frame-welding/brake-safety hazard); its permanent
equipment-actuation block guards generic assembly-line equipment
(`:actuate-equipment?`) rather than a welding/assembly/test-bench
LINE specific to bicycle frames; its production-batch record declares
a `:product-category` (closed set spanning animal-drawn vehicles --
cart/wagon/carriage -- and hand-propelled vehicles -- hand-cart/
hand-truck/wheelbarrow/rickshaw/sledge/toboggan/pushcart, explicitly
EXCLUDING bicycle and invalid-carriage categories) and a
`:weight-capacity-kg` (a physically plausible rated maximum load, 0-
3000 -- a wider ceiling than 3092's 0-300 to admit heavy-duty
animal-drawn farm wagons) in addition to an
`:assembly-defect-rate-percent` (renamed from 3092's
`:weld-defect-rate-percent` since assembly here is not necessarily
weld-based); and its shipment quantity is tracked in finished-product
UNITS (`:units`/`:quantity-units`/`:shipped-units`), the same
counted-not-weighed shape as 3092.

This vertical additionally has a permanent certification-authority
block, generalized rather than naming a specific standard: unlike
3092 (which names real ISO 4210/ISO 7176 standards specific to
bicycles/wheelchairs), no single globally-recognized certification
standard applies uniformly across this residual class's heterogeneous
product set (animal-drawn carts vs. hand-trucks vs. sledges). This
actor is never the certification authority — any proposal (regardless
of op) that declares `:issue-certification? true` is a HARD,
PERMANENT, unconditional block
(`otmfg.governor/certification-authority-blocked-violations`),
described generically as "a transport-equipment safety/roadworthiness
certification mark" (the accredited certification/regulatory body's
exclusive authority) rather than a fabricated specific standard
number — the same "no phase, no human override" posture as the
equipment-actuation block.

This vertical has NO pre-existing `kotoba-lang/otmfg`-style capability
library to wrap (verified: no such repo exists). This build therefore
uses self-contained domain logic — pure functions in `otmfg.registry`
(equipment/batch verification, shipment-quantity recompute,
product-category validation, weight-capacity plausibility validation,
assembly-defect-rate plausibility validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-3092`'s `bikemfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:other-transport-equipment-plant-operations-governor`, is
grep-verified UNIQUE fleet-wide (`gh search code
"other-transport-equipment-plant-operations-governor"`, zero hits
before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external other-transport-equipment-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
other-transport-equipment vertical has NO pre-existing capability
library to wrap. The equipment/batch-verification / shipment-quantity
/ product-category / weight-capacity / assembly-defect-rate
validation functions live as pure functions in `otmfg.registry` and
are re-verified independently by `otmfg.governor` — the same "ground
truth, not self-report" discipline established across prior actors
(most directly `cloud-itonami-isic-3092`'s `bikemfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of other-
transport-equipment plant operations. It does NOT:
- Control assembly-line equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate assembly-line equipment
- Self-issue a transport-equipment safety/roadworthiness certification mark

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: other-transport-equipment manufacturing
is a safety-critical domain (materials-safety and structural-integrity
hazard, transport-equipment safety/roadworthiness certification,
direct road-user/load-safety consequence). Safety-concern flagging
NEVER auto-commits. All safety concerns escalate immediately to human
review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (materials-safety concern, structural-integrity
concern) ALWAYS escalates, never auto-commits. This is not a
"low-stakes proposal" — it is a circuit-breaker that must reach human
authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-3092`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own
`:verified?`/`:registered?` fields; `:coordinate-shipment`
independently verifies the referenced **batch**'s own
`:verified?`/`:registered?` fields. Both are the same "plant/batch
record must be independently verified/registered before any action"
HARD invariant applied to the two distinct record kinds this domain
actually has. `:coordinate-shipment` additionally independently
recomputes whether a batch's own recorded shipped-to-date unit
quantity plus the proposal's own claimed unit quantity would exceed
the batch's own recorded production quantity — never taken on the
advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `otmfg.governor`, mirroring `cloud-itonami-isic-3092`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct assembly-line-equipment control, equipment actuation, or self-issued transport-equipment safety/roadworthiness certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Other-transport-equipment plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no certification mark can ever be
self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(+) Scope is disambiguated from the neighboring `cloud-itonami-isic-3092`
actor at both the blueprint level (product-category closed set
explicitly excludes bicycle/invalid-carriage categories) and the test
level (`otmfg.registry-test`'s
`bicycle-and-wheelchair-categories-are-out-of-scope-for-this-vertical`
asserts the exclusion directly).

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, plant operation, and
certification issuance remain human-/institution-controlled via
external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-3099`: `clojure -M:test` green -- `Ran 78 tests
  containing 218 assertions. 0 failures, 0 errors.` (verified from an
  independent fresh clone; see the superproject ADR and
  `kotoba-lang/industry` registry entry for the exact re-verification
  output), demo narrative (`clojure -M:dev:run`) exercises proposal
  submission, escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded,
  equipment-actuate-blocked, certification-authority-blocked,
  already-scheduled, invalid-product-category,
  invalid-weight-capacity, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
