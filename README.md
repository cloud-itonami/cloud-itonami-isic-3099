# cloud-itonami-isic-3099: Manufacture of other transport equipment n.e.c.

Open Business Blueprint for **ISIC 3099**: manufacture of other transport equipment not elsewhere classified — a residual class covering animal-drawn vehicles (carts, wagons, carriages) and hand-propelled vehicles (hand-carts, hand-trucks, wheelbarrows, rickshaws, sledges, toboggans, pushcarts) — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **other-transport-equipment plant operations**: production-batch data logging (product-category/weight-capacity/quantity/assembly-defect-rate), assembly-line-equipment maintenance scheduling, safety-concern flagging, and outbound shipment coordination.

This repository designs a forkable OSS business for other-transport-
equipment plant operations: run by a qualified operator so a plant keeps
its own operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not assembly-line control

ISIC 3099 is a **residual class** covering the manufacture of vehicles
drawn by animal or pushed/pulled by hand: carts, wagons, carriages,
rickshaws, sledges, toboggans, hand-trucks, wheelbarrows and pushcarts.
It EXCLUDES bicycles and invalid carriages (manual/power wheelchairs,
mobility scooters), which are ISIC 3092 (`cloud-itonami-isic-3092`), a
distinct, already-implemented actor in this fleet.

This actor coordinates the back-office record keeping around the plant
that assembles other-transport-equipment on a chassis/axle/wheel/body
assembly line and inspects the resulting products on structural/load
test benches — it never touches the assembly-line equipment directly,
and it is never the certification/regulatory authority that issues
transport-equipment safety/roadworthiness approval marks.

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — assembly batch, output-quality data logging (administrative, not an operational decision)
- `:schedule-maintenance` — assembly-line-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a materials-safety/structural-integrity concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(assembly-line equipment, materials-safety and structural-integrity
hazard, transport-equipment safety/roadworthiness certification,
direct road-user/load-safety consequence):

- Does NOT control assembly-line equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate assembly-line equipment (human plant supervisor decides)
- Does NOT self-issue a transport-equipment safety/roadworthiness certification mark (the accredited certification/regulatory body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation
- Does NOT cover bicycles or invalid carriages (wheelchairs/mobility scooters) — that scope belongs to `cloud-itonami-isic-3092`

## Architecture

Classic governed-actor pattern (`otmfg.operation/build`, a langgraph-clj StateGraph):
1. **`otmfg.advisor`** (sealed intelligence node, `OtherTransportAdvisor`): proposes decisions only, never commits
2. **`otmfg.governor`** (independent, `Other Transport Equipment Plant Operations Governor`): validates against domain rules, re-derived from `otmfg.registry`'s pure functions and `otmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct assembly-line-equipment control)
     - Directly actuating assembly-line equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing a transport-equipment safety/roadworthiness certification (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-category` value on a production-batch patch
     - No physically implausible `:weight-capacity-kg` value on a production-batch patch
     - No physically implausible `:assembly-defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`otmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`otmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
