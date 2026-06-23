# App Store charging — scoping (hosted, multi-tenant licensekit)

*Status: scoping. Goal is a build/no-build decision, not a commitment to launch.*
*Date: 2026-06-20*

## Objective

Decide whether Immortal can offer **hosted paid-app billing** — where a third-party developer
connects their own Stripe account and sells through the Immortal App Store with Immortal running
the infrastructure — **without taking on liability we can't manage**. Build it in a way that we can
ship-but-gate: complete the engineering, keep it dark, and switch it on only when we have enough
interested developers to justify it.

## Guiding principle (front and center)

> **Liability containment is the gating requirement, not a feature.** If we cannot structure this
> so that Immortal is *not* the merchant of record, *not* a money transmitter, and *not* the bearer
> of refund/chargeback/fraud risk, **we do not launch it.** Revenue is secondary to not creating an
> open-ended financial or regulatory exposure for a hobbyist-scale project.

The good news from scoping: Stripe Connect has a configuration that meets this bar. The rest of this
doc is about confirming that and pricing the work.

## Where we are today

[licensekit](../../licensekit) already solves the hard technical parts — signed device-bound tokens
verified offline, the purchase→license→unlock flow, anti-piracy (device limits, idempotency,
no forgeable tokens), a drop-in Android upgrade UI. It is **single-seller per deployment**: each dev
runs their own Supabase project + their own Stripe account (see
[selling-your-app-on-immortal.md](../../licensekit/docs/selling-your-app-on-immortal.md)).

The *only* structural gap to a hosted platform is **multi-tenancy**: the Edge Functions read a single
`LK_STRIPE_SECRET_KEY` / `LK_STRIPE_PRICE_ID` / signing key from project env, so one deployment can't
serve many sellers with their own Stripe accounts. That's the engineering this doc scopes.

## The liability-first decision: how money flows

Stripe Connect offers combinations of **account type** and **charge type**. They differ enormously in
who bears liability. The recommendation below is chosen to push liability onto Stripe and the
developer, away from us.

**Recommendation: Standard connected accounts + direct charges.**

| Concern | With Standard + direct charges | Why it protects us |
|---|---|---|
| **Merchant of record** | The **developer** (connected account) | We are explicitly *not* the MoR; the dev owns the customer/transaction |
| **Disputes & chargebacks** | Debited from the **developer's** Stripe balance, not ours | A bad sale is the dev's loss, not Immortal's |
| **Refunds** | Issued by/against the **developer's** balance | Same |
| **KYC / identity / onboarding** | **Stripe** handles it (Standard accounts onboard through Stripe's own flow) | We never collect or verify seller identity docs |
| **Negative balances** | Stripe can be assigned responsibility; Standard accounts are only debited above a small threshold | New platforms should let **Stripe** own negative-balance risk |
| **Money in our possession** | Funds settle directly to the **developer**; we never take custody | Strong argument we are **not a money transmitter** |
| **Our revenue** | `application_fee` (a platform cut) taken off each charge | We still earn without being in the money path |

Contrast with **destination charges** (and to a degree Express/Custom accounts): there the *platform*
becomes merchant of record and refunds/disputes hit the *platform's* balance. **We explicitly do not
want that.** It would put exactly the liability we're trying to avoid onto Immortal.

Caveats to confirm during build (see open questions):
- **Dispute fees can't be passed to connected accounts.** Stripe bills the dispute *fee* to the
  platform in some configurations even when the disputed *amount* hits the dev. This is a small,
  bounded cost (per-dispute fee, not the transaction value) but it's nonzero and must be modeled.
- **MoR must be clearly disclosed to the buyer.** Direct charges require the checkout to identify the
  developer as the seller. With Standard + direct charges this is largely automatic (the dev's
  Stripe account brands the checkout), but we must verify the buyer never sees "Immortal" as the
  seller of record.

## What changes in licensekit (multi-tenant refactor)

The single-tenant primitives stay; we add a tenant layer:

1. **`tenants` table** — one row per developer/app: app id, Stripe connected-account id, price id,
   tier/device-limit/exp config, branding, and the app's **own signing keypair** (each app gets its
   own keys so a leak is contained to one app).
2. **App id on every request** — `license-activate` / `license-claim` / `license-price` /
   `license-key` take an app id and look up that tenant's config instead of reading a single env set.
3. **Stripe Connect onboarding** — a small flow that creates a Standard connected account and runs
   Stripe's hosted onboarding link, storing the resulting account id on the tenant. We never see the
   dev's secret key; we make API calls on their behalf via Connect using the platform key +
   `Stripe-Account` header, and take `application_fee_amount` per charge.
4. **Webhook routing** — a single platform webhook endpoint that resolves events to the right tenant
   (via connected-account id) and binds devices accordingly. Replaces the per-app webhook secret.
5. **Per-tenant key management** — generate/store each app's signing key server-side; expose only the
   public key to the client. (Encrypt private keys at rest; they're the one true secret here.)
6. **Client change** — `LicenseConfig` gains an `appId`; the app still embeds only its public key.
   Backwards-compatible with the self-hosted single-tenant config.

Architecture (hosted):

```
Dev ──Connect onboarding──▶ Stripe creates Standard account ──▶ tenants row (account id, price, keys)
Buyer ──pays──▶ Stripe checkout (dev = merchant of record) ──application_fee──▶ Immortal
                         │ funds settle to DEV's balance (we never hold them)
                         ▼
Platform webhook ──resolve tenant──▶ bind device ──▶ sign token with THAT app's key
App ──{appId, device}──▶ license-claim ──▶ token ──▶ verify OFFLINE against embedded public key
```

## Liability & compliance matrix (recommended model)

| Risk | Who bears it | Our exposure |
|---|---|---|
| Chargeback / dispute amount | Developer | None (their balance) |
| Refund | Developer | None |
| Dispute *fee* | Platform (Stripe rule) | Small, bounded, per-dispute — model it |
| Fraud / negative balance | Stripe (if assigned) | Low if we let Stripe own it for new platforms |
| Seller identity / KYC | Stripe | None — Stripe onboards Standard accounts |
| Sales tax / VAT on the sale | Developer (they're MoR) | None — but document this clearly to devs |
| Holding customer funds | Nobody (direct settlement to dev) | None — supports "not a money transmitter" |
| PCI / card data | Stripe (hosted checkout) | None — we never touch card data |
| Platform Connect agreement | Immortal accepts Stripe's Connect terms | Standard platform obligations — review them |

## Legal / policy checklist (do before switch-on, not before build)

- [ ] **Developer agreement** — devs are independent merchants of record; Immortal provides infra +
      listing only; we may remove apps; we take an `application_fee`.
- [ ] **Confirm money-transmitter posture** — with direct settlement we expect to be out of scope, but
      get this looked at before turning on real charges (jurisdiction-dependent). One-time legal check.
- [ ] **Buyer-facing MoR disclosure** — verify checkout shows the developer as seller, not Immortal.
- [ ] **Refund/dispute policy** — published, and clearly "handled by the developer."
- [ ] **Tax responsibility notice** — devs handle their own sales tax/VAT (offer Stripe Tax as an
      option they can enable on their own account).
- [ ] **Review Stripe's Connect platform agreement** for our obligations as the platform.

## Build phases (so we can ship-but-gate)

1. **Multi-tenant core** — `tenants` table, app-id-keyed functions, per-tenant key management.
   *(Pure refactor of existing licensekit; no Connect yet. Testable with manually-seeded tenants.)*
2. **Connect integration** — Standard account creation + hosted onboarding, charges via
   `Stripe-Account` + `application_fee`, platform webhook routing.
3. **Admin + dev onboarding UX** — a minimal "connect your Stripe, configure your app" flow; an
   internal view of tenants/sales.
4. **Compliance gate** — complete the legal/policy checklist above. **This is the go/no-go.**
5. **Dark launch** — deploy disabled / behind a flag; dogfood with one friendly dev (e.g. k3sbp if
   he opts in) in Stripe test mode.
6. **Activate** — only when (a) the compliance gate is cleared and (b) we have enough interested devs.

Phases 1–3 are the engineering; they can be built and parked. Phase 4 is where we can still say no.

## Go / no-go criteria

**Build phases 1–3 if** we want this as a long-term platform option. Low regret — it's mostly a
refactor of code we already own, and it strengthens licensekit either way.

**Switch on (phase 6) only if ALL of:**
- Compliance gate cleared: confirmed not MoR, confirmed not a money transmitter for our flow,
  developer agreement in place, MoR disclosure verified.
- Stripe assigned negative-balance/fraud responsibility (or our exposure explicitly bounded and
  acceptable).
- Enough developer demand to justify the ongoing operational/support load.

**Do not switch on if** any liability item can't be cleanly assigned away from Immortal. Per the
guiding principle, an unmanageable liability profile is a hard stop regardless of revenue potential.

## Open questions (to resolve during phases 1–4)

1. Confirm dispute-fee billing under Standard + direct charges and model the cost.
2. Confirm Standard-account onboarding UX is acceptable for hobbyist devs (vs. Express).
3. Decide `application_fee` level (or zero at launch to maximize dev adoption, add later).
4. Money-transmitter check — which jurisdictions, do we need an opinion before dark launch.
5. Key management: where/how to encrypt per-tenant private signing keys at rest.
6. App-store listing tie-in: how a "paid" app is flagged/surfaced in the existing App Store UI.

## Recommendation

Proceed with **phases 1–3** (the multi-tenant + Connect engineering) as a parked, gated capability —
low regret, mostly refactoring code we own, and it future-proofs licensekit. Treat **phase 4
(compliance)** as a genuine decision point with authority to kill the launch. The chosen money-flow
model (Standard accounts + direct charges + application fee) appears to satisfy the liability-first
principle; phases 1–4 exist to confirm that before any real money moves.

---

### Sources (Stripe liability model)

- [Merchant of record in a Connect integration](https://docs.stripe.com/connect/merchant-of-record)
- [Understand how charges work in a Connect integration](https://docs.stripe.com/connect/charges)
- [Create destination charges](https://docs.stripe.com/connect/destination-charges)
- [Disputes on Connect platforms](https://docs.stripe.com/connect/disputes)
- [Risk and liability management with Connect](https://docs.stripe.com/connect/risk-management)
- [Understanding Connect account balances](https://docs.stripe.com/connect/account-balances)
- [Connected account types](https://docs.stripe.com/connect/accounts)
- [Recommended Connect integrations and charge types](https://docs.stripe.com/connect/integration-recommendations)
