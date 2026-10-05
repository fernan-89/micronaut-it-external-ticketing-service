# ADR-031: Two-Way Sync, the Platform Is the System of Record

## Status
Accepted

## Context
Staff work in two tools. The integration must keep them aligned without ever letting the provider make the platform do something its own rules forbid, and without ping-pong (a change pushed out comes back in and is pushed out again).

## Decision
- **Triggers are explicit.** Outbound: a staff member links or syncs (`initiate`, `sync/execute`). Inbound: the provider calls `POST /webhook/{connectionId}/receive`. No change to the incident, request or problem services, no events, no polling.
- **Outbound mapping.** `outboundStatus` maps a platform status to the provider status; a status with no entry is not pushed. The last status pushed is remembered, so an unchanged status is not pushed again and a status that came from the provider is not pushed back. Only **public** comments are pushed, once each (remembered by id, capped at 500, at most 50 per sync); internal notes never leave the platform, and neither do comments written by the integration itself. A problem comment has no public flag, so it is treated as internal.
- **Inbound mapping.** `inboundActions` maps the provider status to an action (`ACKNOWLEDGE`, `START`, `RESUME`, `RESOLVE`, `CLOSE`, `CANCEL`), applied through the control route of the item as the service identity `external-ticketing:<connectionId>`, so it is attributed in the audit trail of that service. A status with no entry is ignored. A provider comment is added as an **internal** note, prefixed with its origin (`[Jira ITSM-1] ...`) and cut at 4000 characters.
- **The platform wins.** An action the item refuses (illegal transition, already closed) or does not model is a recorded conflict on the link (`INBOUND_CONFLICT`), never forced; the webhook still answers 200 with `CONFLICT_PLATFORM_WINS`, so the provider does not retry something that was decided on purpose.
- **No echo.** An event caused by the `integrationActor` of the connection (the account the credentials act as) is ignored. Events are idempotent: a unique index on `(connectionId, eventId)` makes a redelivery answer `DUPLICATE` and apply nothing (entries expire after 30 days by a TTL index).
- Jira moves through the transition that ends in the wanted status (matched by its name); ServiceNow patches the `state` code. Both default maps are overridable per connection.

## Consequences
- Positive: no loops, no forced states, a full trail of every exchange including the refused ones.
- Negative: a provider that changes a status the platform refuses stays out of sync until staff act, by design; there is no business-hours calendar and no retry queue for a provider that is down (a staff sync retries).
