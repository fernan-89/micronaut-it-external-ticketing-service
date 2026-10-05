# ADR-032: Credentials Are Never Stored; Addresses Are Policed; the Webhook Proves Its Caller

## Status
Accepted

## Context
The integration holds the keys to another system. A database leak, an audit export or a log line must never expose them, and an address supplied by staff must not turn the service into a way to reach internal hosts.

## Decision
- **No secret is ever stored, returned, logged or audited.** A connection names two environment variables: `secretRef` holds the **whole Authorization header value** (`Basic ...` or `Bearer ...`) and `webhookSecretRef` the webhook token. Only the names are stored; the values are read at the moment of use and travel only into the request they were needed for. Responses say whether each variable is set (`secretConfigured`), and `connection/check/execute` proves the credentials work without echoing them. The two names must differ.
- **What is stored of a ticket:** ids, statuses and times only. No ticket text and no comments are kept; `lastError` is one line of at most 200 characters, and a provider failure message names the operation and the HTTP status, **never the response body** (it could echo the request).
- **Base URL policy.** The address of a connection must be `https`, and not a loopback, link-local or private address literal (including `localhost`, the IPv6 equivalents and decimal-integer hosts), unless its host is listed in `thinklab.external-ticketing.insecure-hosts` (for a test double only). It is checked when the connection is created and updated. This is a literal check, not a DNS one: a hostname that resolves to a private address is not caught (a known limit; run the service where egress is restricted).
- **Webhook authentication.** The provider cannot send our tenant headers, so the connection id in the path names the tenant and the `X-Webhook-Token` header proves the caller, compared in constant time with the variable the connection names. A wrong or missing token is 401 `ERR-ETK-00401` before the payload is read; a disabled connection accepts nothing. The route has no `X-Tenant-Id` or `X-Role`; with platform security on, both the gateway and this service list it as a public path (the provider cannot sign in).
- All other routes are **staff only**: a `REQUESTER` is 403 `ERR-ETK-00403`.

## Consequences
- Positive: nothing to rotate in our database, nothing to leak through audit or logs; rotating a secret is changing an environment variable.
- Negative: some environments need a restart to change what a variable holds; the SSRF defence is literal only.
