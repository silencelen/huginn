# Verify downloads

Every Huginn release publishes the SHA-256 hash of each file it ships. The installers and
updaters check those hashes for you; this page shows what they check, and how to check a file
yourself.

## What is checked for you

| Download | Checked by | Against |
|---|---|---|
| huginn core (Linux, Windows) | the client installer, `huginn core install`, `huginn core update`, `huginn update` | `core-manifest.json` in the same `cli-v` release |
| Desktop app: Windows installer, portable Linux build, `.deb`, Arch package | `huginn desktop install`, `huginn desktop update`, `huginn update` | `manifest.json` in the same `desktop-v` release |
| Desktop app updates | the desktop app itself | `manifest.json` in the same `desktop-v` release |
| Android app updates | the Android app (Settings → Software update) | `latest.json` in the same `app-v` release |

A file that does not match is deleted, and nothing is installed.

These are not checked by hash:

- **The `huginn` command** (`huginn.sh`, `huginn.ps1`). The client installer downloads it from the
  newest `cli-v` tag over HTTPS and checks that it parses before running it.
- **The server's source.** The server install command downloads the release's source archive from
  GitHub over HTTPS. To review the code before you install, clone a tag and run setup from the
  clone:

  ```bash
  git clone --branch cli-v2.5.8 https://github.com/silencelen/huginn.git
  sudo bash huginn/server/setup.sh
  ```

- **A file you download from the releases page yourself.** Check it as shown below.

**What a hash proves.** The manifests come from the same GitHub release as the files they
describe. A matching hash proves the file arrived intact and is the one that release published.
It does not protect against a compromised release. There is no signature over the manifests yet.

## Check a huginn core binary

`cli-v` releases carry `SHA256SUMS`, one line per binary. Replace the version with the release you
downloaded.

On Linux:

```bash
base=https://github.com/silencelen/huginn/releases/download/cli-v2.5.8
curl -fLO "$base/huginn-2.5.8-linux-x64"
curl -fLO "$base/SHA256SUMS"
sha256sum -c --ignore-missing SHA256SUMS
```

The last command prints `huginn-2.5.8-linux-x64: OK` for a good file. To check the copy already
installed, compare against the release `huginn version` names:

```bash
sha256sum "$(readlink -f ~/.local/bin/huginn)"
```

In PowerShell:

```powershell
$base = 'https://github.com/silencelen/huginn/releases/download/cli-v2.5.8'
$file = 'huginn-2.5.8-windows-x64.exe'
Invoke-WebRequest "$base/$file" -OutFile $file -UseBasicParsing
Invoke-WebRequest "$base/SHA256SUMS" -OutFile SHA256SUMS -UseBasicParsing
$want = ((Select-String -Path SHA256SUMS -SimpleMatch -Pattern $file).Line -split '\s+')[0]
$got  = (Get-FileHash -Algorithm SHA256 $file).Hash.ToLower()
if ($got -eq $want) { 'OK' } else { 'MISMATCH: delete the file' }
```

The installed copy is `%LOCALAPPDATA%\huginn\bin\huginn.exe`:

```powershell
(Get-FileHash -Algorithm SHA256 "$env:LOCALAPPDATA\huginn\bin\huginn.exe").Hash.ToLower()
```

## Check a desktop app download

`desktop-v` releases carry `manifest.json`, which lists every file with its `sha256`.

On Linux, with `jq`:

```bash
base=https://github.com/silencelen/huginn/releases/download/desktop-v2.5.7
curl -fLO "$base/manifest.json"
curl -fLO "$base/huginn-desktop-kt_2.5.7-1_amd64.deb"
jq -r '.artifacts[] | "\(.sha256)  \(.file)"' manifest.json > desktop.sha256
sha256sum -c --ignore-missing desktop.sha256
```

The same `jq` line works on a `cli-v` release's `core-manifest.json`.

In PowerShell:

```powershell
$base = 'https://github.com/silencelen/huginn/releases/download/desktop-v2.5.7'
$file = 'Huginn-Desktop-Setup-2.5.7.exe'
Invoke-WebRequest "$base/$file" -OutFile $file -UseBasicParsing
Invoke-WebRequest "$base/manifest.json" -OutFile manifest.json -UseBasicParsing
$want = (Get-Content -Raw manifest.json | ConvertFrom-Json).artifacts.'windows-x64'.sha256
$got  = (Get-FileHash -Algorithm SHA256 $file).Hash.ToLower()
if ($got -eq $want) { 'OK' } else { 'MISMATCH: delete the file' }
```

The manifest's keys are `windows-x64` (installer), `linux-x64` (`.deb`), `linux-x64-arch` (Arch
package) and `linux-x64-portable` (portable `.tar.gz`). `huginn desktop` prints the download links
with the start of each hash.

## Check the Android app

`app-v` releases carry `latest.json`, whose `sha256` field is the APK's hash:

```bash
base=https://github.com/silencelen/huginn/releases/download/app-v3.11.1
curl -fLO "$base/Huginn-3.11.1.apk"
curl -fLO "$base/latest.json"
jq -r .sha256 latest.json
sha256sum Huginn-3.11.1.apk
```

The two hashes must be equal. Android also refuses an update signed with a different key from the
installed app.

## Windows signatures

huginn core (`huginn-<version>-windows-x64.exe`), the desktop installer and the desktop app are
signed with Authenticode, but the signing certificate does not chain to a publicly trusted root
yet. Windows therefore treats them as coming from an **unknown publisher**:

- **SmartScreen** may stop an installer you run from your browser's downloads with "Windows
  protected your PC". Check the file's hash first; then choose **More info**, then **Run anyway**.
- To see the signature:

  ```powershell
  Get-AuthenticodeSignature .\Huginn-Desktop-Setup-2.5.7.exe | Format-List Status, StatusMessage
  (Get-AuthenticodeSignature .\Huginn-Desktop-Setup-2.5.7.exe).SignerCertificate |
      Format-List Subject, Issuer, Thumbprint, NotAfter
  ```

  `Status` is not `Valid`, and `StatusMessage` says the chain ends in a root certificate that is
  not trusted. That is expected. `NotSigned` or `HashMismatch` is not: delete that file.

The hash in the release manifest is the hash of the signed file, so a matching hash also tells you
the signature is the one the release shipped.

### Microsoft Defender

huginn core copies itself into your profile and registers a task that starts it at boot, which is
a pattern Defender's behavior monitoring watches for. Defender can therefore quarantine
`huginn.exe` or block `huginn core install`, with a behavior detection such as
`Behavior:Win32/Persistence.A!ml`.

1. Check the hash of the file Defender flagged against the release (see
   [Check a huginn core binary](#check-a-huginn-core-binary)).
2. If it matches, open **Windows Security → Virus & threat protection → Protection history**,
   open the detection, and under **Actions** choose **Allow on device**, or **Restore** if it was
   quarantined. Allow that one detection rather than excluding a folder.
3. Run `huginn core install` again to put the binary and its boot task back.

If the hash does not match, do not allow it. Delete the file and download it again from the
releases page.

## See also

- [Releases and compatibility](../reference/releases.md): which release carries which files.
- [Security](../security.md): what the token protects, and what Huginn does not do.
