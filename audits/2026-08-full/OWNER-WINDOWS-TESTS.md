# Owner test script — the things only PRESTIGE can answer

**Time:** about ten minutes. **Machine:** the Windows box with Huginn Desktop installed.
**Why you:** there is no Windows machine in huginn's dev loop, so everything below
was written and read but never *run*. Each of these fails silently by design, so
"I haven't noticed a problem" is not evidence either way.

Run them in order — test 1 explains most of what the others might show.

Paste results back to huginn (a screenshot of each PowerShell block is fine).

---

## 1. Does the Start Menu shortcut carry the AUMID? (2 min — do this one first)

This is the audit's finding **L10**, and it is the one most likely to be broken.
Windows files a toast under the app's identity and **silently drops it** if that
identity matches no installed shortcut. The app's own code says the installer
must stamp it; the installer does not appear to.

Open **PowerShell** (normal, not admin):

```powershell
$lnk = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Huginn Desktop (Compose)\Huginn Desktop (Compose).lnk"
Test-Path $lnk
$sh = New-Object -ComObject Shell.Application
$item = $sh.Namespace((Split-Path $lnk)).ParseName((Split-Path $lnk -Leaf))
"AUMID = [" + $item.ExtendedProperty("System.AppUserModel.ID") + "]"
```

- **PASS:** `AUMID = [com.silencelen.huginn.desktop-kt]`
- **FAIL:** `AUMID = []` (empty) — confirms L10. Desktop toasts cannot work
  until the installer stamps it, and tests 2 and 3 will also fail.
- If `Test-Path` is `False`, tell huginn — the shortcut is somewhere else and
  the AUMID question moves with it.

---

## 2. Do toasts actually appear, and do the buttons work? (3 min)

Have the desktop app **running and focused** (so it is claiming the notify
route), then from huginn's side ask a question in any tmux session — or simply
let a session hit a permission prompt.

Watch for a Windows toast in the bottom-right with the question and up to three
numbered buttons.

- **PASS:** the toast appears; clicking button `1.` answers the session (check
  the pane — the digit lands and the session continues).
- **FAIL A — no toast at all, but a small tray balloon instead:** the toast
  backend refused at startup and fell back. Expected if test 1 failed.
- **FAIL B — no toast and no balloon, nothing anywhere:** this is the silent
  drop L10 predicts. Confirm with test 2b.
- **FAIL C — toast appears but buttons do nothing:** the `huginn://` scheme is
  not registered. Unexpected (the app re-registers it on every start), so
  capture it — run test 4.

### 2b. What does the app think its notifier is?

In the app: **Help → Copy diagnostics**, paste into Notepad, and look at the
`## Notifications` block:

```
enabled          true
window visible   true
attended         true
claiming route   true
desktop notifier windows-toast        <- what you want
```

- `desktop notifier windows-toast` + no toast on screen = **the silent drop**.
  That combination is the proof for L10; nothing else produces it.
- `desktop notifier NOT WIRED ...` = it never came up; Telegram and the phone
  are your only routes.
- **`claiming route true` while toasts are broken is the bad case** — the
  desktop is telling huginn "I'll handle notifications" and then dropping them,
  which suppresses the Telegram fallback that would otherwise have reached you.
  If tests 1 and 2 both fail, turn **notifications off** in the desktop app's
  settings until it is fixed — that restores Telegram immediately.

---

## 3. Is it rendering on the GPU? (2 min)

Every desktop screenshot so far came from a software renderer under a virtual X
server on Linux, so real GPU behaviour — the terminal grid in particular — has
never been observed.

Open a session, switch to the **Screen** tab, and scroll/type for a few seconds.

- **PASS:** text is crisp, scrolling is smooth, no tearing or ghost glyphs, CPU
  stays low.
- **FAIL:** smeared or doubled glyphs, visible repaint bands, a fan spinning up,
  or the window going white on resize.

Then confirm which pipeline it chose:

```powershell
Get-Content "$env:APPDATA\huginn-desktop-kt\logs\*.log" -Tail 40 | Select-String -Pattern "skiko|Direct3D|OpenGL|software|renderApi"
```

Report whichever line comes back (if the log path is wrong, **Help → Open log
folder** in the app and look for the newest `.log`).

---

## 4. Does `huginn://` survive a fresh install? (1 min)

```powershell
reg query "HKCU\Software\Classes\huginn" /s
```

- **PASS:** shows `URL Protocol` and a `shell\open\command` pointing at
  `...\huginn-desktop-kt.exe "%1"`.
- **FAIL:** `ERROR: The system was unable to find the specified registry key`.

Then check it actually activates — in PowerShell:

```powershell
Start-Process "huginn://open?view=sessions"
```

- **PASS:** the app comes to the front on the Sessions view.
- **FAIL:** nothing happens, or Windows offers to search the Store.

---

## 5. Does the app update itself? (2 min)

Per the audit, the repo has **0.3.2** prepared but the channel is still serving
**0.3.1**, so this test needs huginn to publish 0.3.2 first — ask before running
it.

Once 0.3.2 is published: leave the app running, then **Help → Check for
updates**.

- **PASS:** it offers 0.3.2, downloads, asks to close, installs, and comes back
  as 0.3.2 with your **token still set** (the token-wipe bug was 0.3.1's fix —
  this is its confirmation on real Windows).
- **FAIL — token gone after update:** tell huginn immediately; that is a
  regression of the fix that cost you the token before.
- **FAIL — installer complains files are locked:** the close-the-running-app
  handling did not work.

---

## What to send back

For each test: the number, PASS or FAIL, and the output block. Test 1's single
line and test 2b's `## Notifications` block are the two that matter most — they
decide whether the desktop's whole notification layer works, and whether it is
currently suppressing your Telegram fallback while not working.
