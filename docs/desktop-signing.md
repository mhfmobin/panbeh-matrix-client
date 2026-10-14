# Windows code signing for Panbeh

Unsigned Windows installers get the SmartScreen "Windows protected your PC" warning, and
`electron-updater` refuses nothing but has nothing to verify beyond the GitHub download — so a
compromised release channel would install silently. Signing fixes both: SmartScreen shows the
verified publisher, and the signature is checked at install time.

## Recommended: Azure Trusted Signing

Microsoft's managed signing service. Cheaper than an OV certificate ($9.99/month, first 5k
signatures free), no certificate file to lose, and signatures count toward SmartScreen reputation
like any OV/EV cert. Needs identity validation (a few days, one-time).

### 1. Set up the account

1. Create an Azure subscription, then a **Trusted Signing** account
   (portal → search "Trusted Signing", or `az trustedsigning create`).
2. Complete **identity validation** for the organization (or individual) — this is the slow part.
3. Create a **certificate profile** (public trust), e.g. `panbeh`.
4. Create a **service principal** with the *Trusted Signing Certificate Profile Signer* role on the
   profile, and note its app id, tenant and a client secret:

   ```sh
   az ad sp create-for-rbac --name panbeh-signing \
     --role "Trusted Signing Certificate Profile Signer" --scopes /subscriptions/<sub>/resourceGroups/<rg>/providers/Microsoft.CodeSigning/codeSigningAccounts/<account>/certificateProfiles/panbeh
   ```

### 2. Add the secrets

GitHub → Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `AZURE_SIGN_ENDPOINT` | the account endpoint, `https://<region>.codesigning.azure.net/` |
| `AZURE_SIGN_ACCOUNT` | the Trusted Signing account name |
| `AZURE_SIGN_PROFILE` | the certificate profile name (e.g. `panbeh`) |
| `AZURE_SIGN_TENANT_ID` | the service principal's tenant id |
| `AZURE_SIGN_CLIENT_ID` | the service principal's app id |
| `AZURE_SIGN_CLIENT_SECRET` | the service principal's client secret |

Once `AZURE_SIGN_ENDPOINT` is set, the Windows job signs with it automatically and ignores
`WIN_CSC_LINK`. `main` and `v*` builds **fail** if neither signing method is configured, so a
release can't slip out unsigned by accident.

### 3. SmartScreen reputation

Even signed, a brand-new certificate starts with little reputation and the SmartScreen warning can
appear for the first days/weeks of downloads. It goes away as installs accumulate — this is true
for any certificate, not something Panbeh-specific.

## Alternative: an OV/EV certificate (.pfx)

If Azure Trusted Signing isn't available (e.g. because of sanctions/regional restrictions on
Azure), buy a Windows code-signing certificate (OV from ~$200/yr, EV from ~$350/yr), export it as
`.pfx`, and set instead:

| Secret | Value |
| --- | --- |
| `WIN_CSC_LINK` | the `.pfx` as base64 (`base64 -w0 cert.pfx`) |
| `WIN_CSC_KEY_PASSWORD` | the `.pfx` password |

OV certificates work but SmartScreen reputation builds slowly; EV certificates carry it from day
one but need a hardware token (or eSigner cloud HSM), which doesn't fit CI without the cloud
option. Azure Trusted Signing is the better fit for this project if it's reachable.

## macOS

Unchanged: needs `MAC_CSC_LINK`, `MAC_CSC_KEY_PASSWORD` and the notarization secrets, all listed
in [DESKTOP.md](../DESKTOP.md). Apple's Developer ID signing is the only option there.

## What changed in CI

`.github/workflows/desktop.yml`:

- **Require Windows signing on main and tags** — errors when neither `AZURE_SIGN_ENDPOINT` nor
  `WIN_CSC_LINK` exists on a release build.
- **Signing keys** — exports the Azure auth env vars (`AZURE_TENANT_ID`, `AZURE_CLIENT_ID`,
  `AZURE_CLIENT_SECRET` + the three config values) when the endpoint secret exists; otherwise the
  old `.pfx` path.
- **Package** — adds `-c.win.azureSignOptions.…` flags to electron-builder when signing is
  configured. `electron-builder.yml` itself stays signing-free, so feature-branch builds without
  secrets remain unsigned without breaking.
