# School email enrollment contract (proposed v1)

Status: offline certificate model/verifier implemented and tested. No live email service, registration screen, credential storage, or mesh possession exchange is wired up yet. This module does not change campus access or turn self-selected roles into verified membership.

## Online enrollment

POST /v1/enrollment/challenges accepts schoolId, email, and subjectSigningKey (a raw Ed25519 public key in canonical padded base64). The server validates the school's exact email-domain allowlist and issues an opaque challenge ID with expiry. Return a uniform response to avoid exposing account membership. Send a single-use code to the mailbox. The request must not log codes or email addresses in general application logs.

POST /v1/enrollment/complete accepts challengeId, code, and a device signature over the server's fresh nonce and the bound request context. The server checks expiry, attempts, single-use status, exact key binding, and device signature, then signs a SchoolCredential. Consume the challenge atomically with issuance; retries need idempotency. Rate-limit by mailbox, source, and challenge. Email ownership grants only the school-configured student eligibility, never staff permissions.

Production backend and proof wire encoding remain to be implemented; these endpoint names are a design contract, not callable services. Never accept a client-supplied issuer key as trusted, and never ship the issuer private key in the app.

## Offline credential format

See SchoolCredential.signingBytes for the exact signed binary encoding: UTF-8 fields prefixed by signed 32-bit big-endian byte lengths, fixed field order, version as 32-bit integer, timestamps as 64-bit epoch seconds, and a protocol domain separator. Ed25519 signature and raw subject public key use canonical padded base64. All strings in signing data are bounded to 512 UTF-8 bytes. No email, name, or student number is included.

The verifier accepts only configured school issuer keys, configured maximum lifetime, expected subject public key, current epoch seconds, and a caller-provided set of revoked credential IDs. VALID means the certificate is valid for that key against those inputs. It does not prove live possession, campus presence, current enrollment beyond school policy, or freshness of the caller's revocation set/time.

Before a peer earns a verified badge, authenticate possession of the credential's signing key using a fresh one-time challenge bound to the encrypted session and credential. Existing Noise X25519 transport keys must not be passed off as Ed25519 signing keys; explicitly bind the signing identity to the Noise session. Do not change existing transport keys implicitly.

## Pending deployment decisions

Confirm student email domain and alumni-account policy, issuer ownership and rotation, semester expiry policy, signed revocation distribution, hosting/email delivery service, and recovery procedure. Device clock rollback and stale revocation information require a defined offline acceptance policy. Keep verification unavailable until trusted configuration exists; do not simulate successful enrollment.
