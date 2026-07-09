# Governance

`cloud-itonami-5224` is an OSS open-business blueprint for community
cargo handling operations, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Cargo Handling Governor remains independent of the advisor.
- hard policy violations (out-of-scope handling dispatch, out-of-scope
  hazmat lift, uninspected lift-plan execution) cannot be overridden
  by human approval.
- every dispatch, sign-off, hazmat and reconciliation path is
  auditable.
- sensitive shipper and consignment data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or hazmat-scope checks
- mishandling shipper or consignment data
- misrepresenting certification status
- failing to respond to safety incidents
