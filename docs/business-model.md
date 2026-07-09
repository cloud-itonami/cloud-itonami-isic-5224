# Business Model: Community Cargo Handling Operations

## Classification
- Repository: `cloud-itonami-5224`
- ISIC Rev.5: `5224` — cargo handling
- Social impact: port/terminal safety, supply-chain resilience,
  dock-worker safety

## Customer
- independent/community terminal operators needing an auditable
  safety-scope and handling-record platform
- shippers and carriers needing verifiable lift-plan, handling and
  reconciliation records for terminal cargo movements
- regulators needing verifiable dock-safety, hazmat-handling-scope
  and inspection records
- programs that cannot accept closed, unauditable cargo-handling
  platforms

## Offer
- terminal safety-scope and hazmat-handling-scope management
- robotics-assisted gantry-crane/reach-stacker/forklift lift-plan
  execution and lashing/unlashing
- consignment booking, handling and reconciliation records
- carrier/shipper billing and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per terminal/berth
- support retainer with SLA
- crane/reach-stacker/forklift robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (handling operation outside verified
  terminal safety scope, overweight/hazmat lift outside verified
  scope, an uninspected lift plan) require human sign-off
- cargo cannot be handled outside its verified safety and hazmat
  scope
- reconciliation records require verified evidence
- sensitive shipper and consignment data stays outside Git
