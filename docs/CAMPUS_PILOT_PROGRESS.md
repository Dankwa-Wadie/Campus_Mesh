# Campus pilot implementation

First increment: accuracy-aware campus presence, bounded grace and expiry, and home-screen presence feedback. Existing campus posting checks consume the resulting app mode; private mesh conversations retain their existing routing.

Defaults for field testing: maximum accepted accuracy 100 m; fresh evidence 60 seconds; confirmed-outside grace 120 seconds; presence expires 180 seconds after the last confirmed inside fix; exit buffer 30 m. Uses monotonic time and the configured campus circles. These are soft participation rules, not authenticated location proof. Location permission recovery uses the existing app flow.

Still to implement: online school-email enrollment, offline credential verification and authenticated peer binding, trusted anchor evidence, offline map assets, campus-first navigation, and a full direct-message delivery/abuse-control audit. Do not label existing self-selected roles as verified student membership. Production enrollment needs a real school-controlled signing authority and public trust configuration; never ship its private key in the app.

Device validation: walk both campus boundaries, test indoors with weak location, deny location permission, stop location updates, and verify private conversations outside school. Check font scaling and screen sizes. Actual mesh reach needs multiple devices.

## Enrollment direction: online once, offline afterwards

Use a school-issued student email for initial enrollment. The exact student domain and whether alumni retain accounts must be confirmed with the school. Never infer current enrollment from a mailbox if the school does not guarantee that relationship.

The app creates a device identity and submits an enrollment request bound to its public key. A backend sends a short-lived, single-use verification code to an allowlisted school domain. Completion requires the code and proof of possession of that device key. Only the backend may issue a signed school credential; its signing key must never be embedded in the Android app. Rate-limit requests and attempts, avoid account enumeration, expire challenges, and protect delivery credentials on the server.

The resulting credential carries school, authorized role, device public key, expiry, and revocation identifier. It does not expose the email address or student number to mesh peers. Offline peer validation checks the signature, expiry, device proof, and latest available signed revocation data. Student email enrollment does not grant lecturer/admin authority.

Online access is needed again for renewal or replacement after losing a device. Disconnected peers cannot immediately learn new revocations. Staff-assisted enrollment may be retained as a separately authorized exception, not a reusable bypass code.

Pending deployment inputs: authorized student domain, school email account lifecycle, backend hosting/email service, school-controlled signing authority, credential validity policy, and existing student-portal integration availability. No email has been sent and no live enrollment service has been deployed.
