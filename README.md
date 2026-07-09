# cloud-itonami-5224

Open Business Blueprint for **ISIC Rev.5 5224**: cargo handling
(stevedoring, terminal loading/unloading and cargo-handling services
at ports, airports and rail yards).

This repository designs a forkable OSS business for community cargo
handling: dock/terminal-safety-scope management, robotics-assisted
gantry-crane/reach-stacker/forklift loading and unloading, and
consignment booking/reconciliation records — run by a qualified
operator so a terminal operator keeps its own safety-certification
and handling history instead of renting a closed cargo-handling
platform.

## Scope note: cargo handling, not carriage

`cloud-itonami-isic-5020` (marine cargo/tanker carriage),
`cloud-itonami-isic-4912` (freight rail), `cloud-itonami-isic-4920`
(road freight), `cloud-itonami-isic-5110` (passenger air) and
`cloud-itonami-isic-4911`/`cloud-itonami-isic-5011` (passenger rail/
water) are all CARRIERS — businesses that move goods or people
between two points aboard their own vehicle or vessel. This
repository is deliberately scoped to the SEPARATE business of cargo
handling: a fixed-location terminal/stevedoring SERVICE that loads,
unloads and handles cargo on behalf of multiple carriers, typically
under its own independent licensing regime (Japan's 港湾運送事業法
licenses stevedoring companies separately from shipping lines; US
longshoring is regulated under OSHA 29 CFR Parts 1917/1918
independently of vessel-operator safety rules; the ISPS Code and ILO
Convention 152 (Dock Work) both regulate the terminal/dock-work
activity itself, not the carrying vessel). Also distinct from
`cloud-itonami-isic-5210` (warehousing and storage, specialized to
petroleum terminal custody) — that is storage of goods at rest;
cargo handling is the active loading/unloading motion itself.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (gantry cranes,
reach-stackers, forklifts, container-lashing/unlashing) operate under
an actor that proposes actions and an independent **Cargo Handling
Governor** that gates them. The governor never dispatches a handling
operation itself; `:high`/`:safety-critical` actions (any handling
operation outside the terminal's own verified safety scope, any
overweight/hazmat cargo movement outside its verified handling scope,
any lift plan that has not passed inspection) require human sign-off.

## Core Contract

```text
intake + identity + terminal safety/hazmat scope + booking
        |
        v
Cargo Handling Advisor -> Cargo Handling Governor -> lift plan, dispatch, reconciliation record, or human approval
        |
        v
robot actions (gated) + handling record + reconciliation record + audit ledger
```

No automated advice can dispatch a handling operation the governor
refuses, approve an overweight/hazmat lift outside its verified
scope, or publish a reconciliation record without governor approval
and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5224`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
