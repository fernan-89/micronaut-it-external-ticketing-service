# micronaut-it-external-ticketing-service

BIAN-aligned Service Domain **it-external-ticketing** (Control Record: `TicketLink`, secondary aggregate: `Connection`), port `8102`.

The connector between the ITSM core and **ServiceNow** or **Jira**: the last slice of Journey 12. A `Connection` registers one provider
instance; a `TicketLink` pairs a platform **incident, service request or problem** with a ticket there. Staff link an item, and from then
on status and public comments flow both ways. The platform stays the system of record.

## What it guarantees, and what it does not

- **Two-way, and the platform wins** (ADR-031): the provider status is applied to the item through the control route of the item itself,
  as the service identity `external-ticketing:<connectionId>`; an action the item refuses is a recorded conflict, never forced, and the
  webhook still answers 200. What the platform pushed is not pushed back (the last pushed status is remembered, and events caused by the
  integration account are ignored). Webhook events are idempotent.
- **Explicit triggers** (ADR-031): staff link or sync; the provider calls the webhook. No polling, no events, and none of the incident,
  request or problem services changed.
- **No credentials stored** (ADR-032): a connection names two **environment variables**, one holding the whole `Authorization` header value
  and one holding the webhook token. Only names are stored; responses say whether each is set. Nothing secret reaches the database, the
  audit trail, a response or a log. Only ids, statuses and times of a ticket are kept, never its text.
- **Internal never leaves** (ADR-031): only **public** comments are pushed; internal notes and the comments of the integration itself are
  not. A comment coming in is added to the item as an **internal** note.
- **Address policy** (ADR-032): a connection URL must be https and not a loopback, link-local or private address literal, unless its host is
  in `thinklab.external-ticketing.insecure-hosts` (for a test double). It is a literal check; DNS is not checked.
- **Linking is crash-safe** (ADR-030, ADR-033): the link is saved `PENDING` first, a provider failure leaves it `FAILED` and a sync retries
  it; every change is a guarded write.
- **Staff only**: every route but the webhook refuses a `REQUESTER` with 403 `ERR-ETK-00403`.
- **Not here yet:** a retry queue or polling for a provider that is down or missed an event, business-hours calendars, attachments, mapping
  of priorities and assignees, and a DNS rebinding defence.

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call but the webhook; `X-Executor` is mandatory on the actions; `X-Role` is optional and, with platform
security on, comes from the verified token.

| Behavior Qualifier | Route |
|---|---|
| connection/initiate | `POST /it-external-ticketing/v1/connection/initiate` `{"name":"Jira prod","provider":"JIRA","baseUrl":"https://acme.atlassian.net","secretRef":"JIRA_AUTH","webhookSecretRef":"JIRA_HOOK","integrationActor":"svc-thinklab","projectKey":"ITSM"}` (`outboundStatus` and `inboundActions` optional, defaults per provider) |
| connection/retrieve | `GET /it-external-ticketing/v1/connection/{id}/retrieve` and `GET /connection/retrieve` |
| connection/update | `PUT /it-external-ticketing/v1/connection/{id}/update` |
| connection/control | `PUT /it-external-ticketing/v1/connection/{id}/control/enable` and `.../disable` |
| connection/check/execute | `PUT /it-external-ticketing/v1/connection/{id}/check/execute` (are the variables set, does the provider answer; never fails because the provider is down) |
| connection/audit-log/retrieve | `GET /it-external-ticketing/v1/connection/{id}/audit-log/retrieve` |
| initiate | `POST /it-external-ticketing/v1/initiate` `{"connectionId":"<uuid>","subjectType":"INCIDENT","subjectId":"<uuid>"}` (201, creates the ticket; 502 if the provider fails, the link stays `FAILED`) |
| retrieve | `GET /it-external-ticketing/v1/{id}/retrieve` |
| retrieve (collection) | `GET /it-external-ticketing/v1/retrieve?connectionId=&subjectType=&subjectId=&status=` |
| sync/execute | `PUT /it-external-ticketing/v1/{id}/sync/execute` (creates a missing ticket, pushes the changed status and the new public comments) |
| control/detach | `PUT /it-external-ticketing/v1/{id}/control/detach` (terminal; the provider ticket is left as it is) |
| audit-log/retrieve | `GET /it-external-ticketing/v1/{id}/audit-log/retrieve` |
| webhook/receive | `POST /it-external-ticketing/v1/webhook/{connectionId}/receive` with `X-Webhook-Token`, Jira webhook JSON or the ServiceNow Business Rule JSON `{table, sys_id, state, comment, updated_by, event_id}` |

```text
PENDING -> LINKED            PENDING --provider fails--> FAILED --sync--> LINKED
PENDING | LINKED | FAILED --detach--> DETACHED (terminal)
```

Default maps: Jira `NEW|SUBMITTED|ACKNOWLEDGED|APPROVED` -> `To Do`, working states -> `In Progress`, `RESOLVED|FULFILLED|CLOSED` -> `Done`,
`CANCELLED` -> `Cancelled`; inbound `In Progress` -> start, `Done` -> resolve, `Cancelled` -> cancel. ServiceNow uses the `state` codes
(`1`, `2`, `3`, `6`, `7`, `8`). Both are overridable per connection.

```bash
export JIRA_AUTH="Basic $(printf 'bot@acme.com:API_TOKEN' | base64)"; export JIRA_HOOK="a-long-random-token"
curl -X POST http://localhost:8102/it-external-ticketing/v1/initiate -H "X-Tenant-Id: <organisationId>" -H "X-Executor: <userId>" \
  -H "Content-Type: application/json" -d '{"connectionId":"<uuid>","subjectType":"INCIDENT","subjectId":"<incidentId>"}'
```

## Error catalog

| Code | HTTP | Meaning |
|---|---|---|
| `ERR-ETK-00401` | 401 | The webhook token is missing or wrong |
| `ERR-ETK-00403` | 403 | A REQUESTER used the connector (ADR-032) |
| `ERR-ETK-00404` | 404 | Connection, link or platform item not found (another tenant's answers the same) |
| `ERR-ETK-00409` | 409 | Duplicate name or link, illegal transition, a disabled connection, a variable not set, the platform refusing an action, or the record changed while the write was applied (retry) |
| `ERR-ETK-00502` | 502 | The provider refused or could not be reached (the message names the operation and status, never the response body) |
| `ERR-VALIDATION-00400` | 400 | Payload/header/identifier validation failure (unsafe URL, bad variable name, bad webhook payload...) |
| `ERR-INTERNAL-00500` | 500 | Unexpected technical failure |

## Configuration

`thinklab.external-ticketing.insecure-hosts` (env `THINKLAB_EXTERNAL_TICKETING_INSECURE_HOSTS`): hosts allowed over http or at a private address, for
a test double only. `INCIDENT_SERVICE_URL`, `SERVICE_REQUEST_SERVICE_URL`, `PROBLEM_SERVICE_URL`: the platform services the connector reads and acts on.

Do not put personal data in an item that is linked: its title and description are sent to the provider.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 link lifecycle, saved PENDING first · 031 two-way sync, the platform wins · 032 no credentials stored, address policy, webhook
authentication · 033 guarded writes and unique backstops.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
