# ADR-033: Every Change Is a Guarded Write, and the Unique Indexes Are the Backstop

## Status
Accepted

## Context
Staff, retries and webhooks touch the same connection and link at the same time.

## Decision
- A `Connection` or `TicketLink` is saved together with its audit entry in **one atomic update, and only while it still has the status it had when it was loaded**; if someone else moved it, nothing matches and the caller gets 409 `ERR-ETK-00409` (read again and retry), never a lost update.
- The unique indexes decide the races the application cannot: `(organisationId, name)` for connections, `(organisationId, connectionId, subjectType, subjectId)` and the partial `(organisationId, connectionId, externalId)` for links, `(connectionId, eventId)` for processed webhook events. A duplicate-key error is mapped to a domain 409. Indexes are created at startup (fail-open, idempotent, `thinklab.mongo.create-indexes=false` turns them off).
- The pushed comments are saved one by one, each right after the provider has it, so a failure half way never makes a comment go twice.
- Guarded by status means two changes that do not move the status (two syncs at the same instant) are last-writer-wins on the bookkeeping fields, acceptable because the remembered ids and the last pushed status only ever move forward.

## Consequences
- Positive: no lost updates, no double links, a webhook delivered twenty times at once is applied once.
- Negative: the loser of a race must retry.
