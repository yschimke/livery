title: Signing
order: 21

# Signing

Every production manifest is signed. A compromised host or CDN can't push designs, hide a
revocation or roll a catalog back.

## Keys

```bash
livery keygen acme          # acme.key (secret), acme.pub (embed in apps)
```

- `acme.key` is PKCS#8 in Base64. Keep it in CI secrets; never commit it.
- `acme.pub` is the raw 32-byte Ed25519 public key in Base64. Apps embed it with a key id.

```kotlin
TrustPolicy.Signed(listOf(TrustedKey("acme-1", Base64.decode(ACME_PUB))))
```

```swift
.signed([TrustedKey(keyId: "acme-1", publicKey: Data(base64Encoded: acmePub)!)])
```

## Sign and verify

```bash
livery build catalog out --key acme.key --key-id acme-1
livery verify out --pub acme.pub --key-id acme-1
```

The signature (`manifest.sig.json`) is Ed25519 over the exact bytes of `manifest.json`. Never
re-encode or reformat the manifest after signing; serve it byte for byte.

## Partner keys

A key can be limited to contract prefixes. An ad partner's key that may sign only `ads/`:

```kotlin
TrustedKey("adnet-1", adnetPub, contractPrefixes = setOf("ads/"))
```

A manifest signed by that key that mentions any other contract is rejected.

## Rotation

Ship the new public key in an app release alongside the old one, then sign with the new key once
enough installs have it. Remove the old key from a later release.

## Freshness and the kill switch

- `serial` must grow per channel; a lower one is refused (rollback protection).
- After `expiresAt` (default 14 days after the build) the catalog is stale but still played for a
  grace period; then apps stop using it.
- `revoked: true` on a design or template withdraws it at the next refresh; `minClient` keeps a
  catalog away from apps too old for it.

> `TrustPolicy.AllowUnsigned` exists for development and preview channels only.
