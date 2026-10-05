# ADR-030: A TicketLink Is Saved PENDING Before the Provider Is Called

## Status
Accepted

## Context
Linking an incident, service request or problem to a ServiceNow or Jira ticket means two writes that cannot be one transaction: our own record of the pairing, and the creation of the ticket at the provider. If the provider call succeeds and our save then fails, a ticket exists that nobody knows about; if we save only after the call, a retry creates a second ticket.

## Decision
- A `TicketLink` has a lifecycle `PENDING -> LINKED`, `PENDING -> FAILED -> LINKED` (a sync retries it) and `PENDING|LINKED|FAILED -> DETACHED` (terminal).
- **The link is inserted `PENDING` first**, guarded by a unique index on `(organisationId, connectionId, subjectType, subjectId)`: an item has one link per connection, and two staff members linking the same item at once cannot both win (the loser gets 409 `ERR-ETK-00409`).
- Only then is the ticket created. Success saves the link `LINKED` with the provider ticket id; a provider failure saves it `FAILED` with a short sanitised error and answers 502 `ERR-ETK-00502`. A later `sync/execute` finds a link without a ticket and creates it, so a retry never makes a second ticket for a link that has one.
- A second unique index on `(organisationId, connectionId, externalId)`, partial on `externalId` being a string (links without a ticket must not collide with each other), makes a provider ticket belong to one link.
- Detaching is terminal and leaves the provider ticket as it is. An item is linked once per connection: linking it again after a detach is refused as a duplicate, deliberately, so the history of the pairing is kept.
- The same routine serves "link" and "sync now": create the ticket if there is none, push the status if it differs from the one last pushed, push the public comments not yet pushed.

## Consequences
- Positive: no orphan tickets from our side, no duplicate tickets on retry, the failure is visible on the link and retryable.
- Negative: a crash between the provider answer and our save of `LINKED` leaves a `PENDING` link whose ticket exists at the provider; the next sync would create a second ticket. The window is one write long and the provider ticket carries the platform item id as a reference, so it is detectable and fixable by hand.
