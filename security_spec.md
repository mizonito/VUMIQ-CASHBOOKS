# VUMIQ Cashbook — Security Specification (Phase 0 Security TDD)

## 1. Master Source of Truth & Data Invariants

1. **Workspace Isolation (Master Gate)**:
   - Every financial sub-resource (`accounts`, `categories`, `parties`, `transactions`, `auditEvents`, `members`) lives under `/businesses/{businessId}` and MUST verify parent ownership or explicit RBAC membership on single-document reads/writes, and `resource.data.ownerId == request.auth.uid` on `list` queries (zero `get()`/`exists()` inside `allow list`).
2. **PII Split Isolation**:
   - User email is isolated inside `/users/{userId}/private/{docId}` and readable/writable strictly by `request.auth.uid == userId` with `email_verified == true`.
3. **Accounting & Terminal State Integrity**:
   - All monetary values (`amountMinor`, `openingBalanceMinor`, `feeMinor`, `convertedAmountMinor`) are bounded integers (`is int`), never floats or unbounded strings.
   - `transactions` with `status == 'voided'` are in a terminal state: subsequent updates are strictly rejected (`existing().status != 'voided'`).
   - `auditEvents` are strictly append-only (`allow update, delete: if false`) and require `existsAfter` / parent existence checks.
   - Transfers require non-empty distinct `accountId` and `toAccountId` (`data.accountId != data.toAccountId`).
4. **Temporal & Identity Integrity**:
   - `createdAt == request.time` on `create`; `updatedAt == request.time` on `update`.
   - `ownerId`, `businessId`, and `createdAt` are immortal (immutable on `update`).
   - All authenticated writes require `request.auth != null && request.auth.token.email_verified == true`.

## 2. The "Dirty Dozen" Adversarial Payloads

1. **Payload 1 (Identity Spoofing on Business Create)**: Authenticated user `uid_attacker` creates `/businesses/biz_1` with `ownerId: "uid_victim"` -> `PERMISSION_DENIED`.
2. **Payload 2 (Unverified Email Write)**: User with `email_verified: false` attempts to create `/businesses/biz_1` -> `PERMISSION_DENIED`.
3. **Payload 3 (Shadow Field Injection on Transaction)**: Valid owner sends `Transaction` payload with extra field `isVerifiedAdmin: true` -> `PERMISSION_DENIED` (`hasOnly` check).
4. **Payload 4 (Terminal State Unlock)**: Owner attempts to `update` a transaction whose existing `status == 'voided'` back to `'posted'` -> `PERMISSION_DENIED`.
5. **Payload 5 (ID Poisoning / 2KB Path Variable)**: Attacker targets `/businesses/{2000_char_id}` -> `PERMISSION_DENIED` (`isValidId` regex & `<= 128` length).
6. **Payload 6 (Value Poisoning on Update)**: Owner updates whitelisted field `description` with a 50,000-character string or boolean -> `PERMISSION_DENIED` (`isValidTransaction` wraps `allow update`).
7. **Payload 7 (Orphaned Subcollection Write)**: User creates `/businesses/non_existent_biz/accounts/acc_1` where parent business does not exist -> `PERMISSION_DENIED` (`exists` Master Gate).
8. **Payload 8 (Audit Trail Tampering)**: Owner attempts to `update` or `delete` `/businesses/biz_1/auditEvents/evt_1` -> `PERMISSION_DENIED`.
9. **Payload 9 (Cross-Tenant List Scraping)**: Authenticated `uid_attacker` runs `list` on `/businesses/biz_victim/transactions` without `ownerId == request.auth.uid` match -> `PERMISSION_DENIED`.
10. **Payload 10 (PII Leak)**: Authenticated `uid_attacker` attempts `get` on `/users/uid_victim/private/info` -> `PERMISSION_DENIED`.
11. **Payload 11 (Client Timestamp Forgery)**: Owner creates a transaction with backdated `createdAt` != `request.time` -> `PERMISSION_DENIED`.
12. **Payload 12 (Self-Privilege Escalation in Members)**: Viewer member attempts to update their own `/businesses/biz_1/members/uid_viewer` `role` to `'owner'` -> `PERMISSION_DENIED`.
