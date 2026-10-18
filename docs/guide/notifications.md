# Notifications

Huginn tells you when a session needs you and when work you left running is done. It reaches you
three ways, and they are designed not to say the same thing twice:

- **huginn core** on each of your computers shows desktop notifications.
- **The Android app** shows phone notifications, with answer buttons on the lock screen.
- **The server** can push to the Android app and, optionally, hand alerts to a relay script you
  provide (Telegram, or anything else), for when no app is listening.

## What notifies

| Event | Computer | Android app | Server alert (push, relay) |
|---|---|---|---|
| A session needs you: a question or permission prompt is waiting | yes | yes, with answer buttons | yes, with the question |
| A chat finished | yes | yes, with a reply box | yes |
| A session that ran 5 minutes or more went idle, with no terminal attached | no | only by push | yes |
| A session stopped at a usage limit, resumed, or was moved to another model | yes | yes | yes |
| Claude account switched, or a saved login is expiring | no | only by push | yes |
| A computer you watch (`huginn devices watch <name> on`) stopped checking in, or came back | no | no | relay only |

Usage-limit notices are described in [Usage limits](usage-limits.md) and watched computers in
[Devices](devices.md); [rounds](rounds.md) and [project](projects.md) proposals can notify too.
On Android the channels are **Needs you**, **Finished**, **Proposals**, and **Watching** (the
quiet ongoing notification of the watcher), so you can silence finished work without silencing
questions.

Some things never notify:

- the chat or session you are looking at, because it is already on your screen;
- a question that was answered before the notification went out.

Opening a chat or session clears its notification. The server does not send a second "needs
you" alert for the same session within 30 minutes, unless the earlier question was answered
first.

## Answered elsewhere

When a waiting question is answered somewhere else (in a terminal, on another device), or the
session ends, its "needs you" notification is taken down on your phone and your computers. A
notification never sits there inviting a tap on a question that no longer exists. The relay
script never carries these withdrawals.

## On a computer

huginn core watches the server and shows notifications through its tray icon: Windows
notifications on Windows, your desktop's notification service on Linux. Clicking one opens that
session or chat in the desktop app. Desktop notifications open the question; they do not carry
answer buttons.

While its tray icon is running and your session is unlocked, huginn core tells the server it is
delivering notifications. The server then holds back the relay script, so you get one
notification, not two. When the screen is locked, or nobody is signed in, huginn core stops
claiming and the server falls back to push and the relay.

If you hide huginn core's tray icon, the desktop app shows notifications itself while it runs. It
claims delivery only while its window is open and has been used within the last 10 minutes, and
only while **Settings > Notifications > Claim the notification route** is on.

## On Android

The Android app has its own watcher, which needs no server setup. In **Settings >
Notifications**:

| Setting | What it does |
|---|---|
| Tell me when a session needs me | Turns the app's own notifications on. |
| Watch continuously | Keeps a live connection while the phone is awake, so alerts arrive within seconds. Android requires the quiet **Watching** notification for this. |
| Allow background use | Exempts the app from battery optimisation. Without it, Android suspends the app's network while the phone sleeps, and checks reach nothing. |
| Android permission | Whether Android lets the app show notifications at all. |
| Delivery | How alerts reach this phone: **push**, **not registered**, or **check only**. Below it, when the app and the server last heard from each other, and how often the background check runs. |

**While the phone sleeps**, the app wakes itself about every 10 minutes to check the server (an
alarm Android honours even in Doze, though it may stretch the gap). That is the delivery path of
the prebuilt APK.

**Push** reaches a sleeping phone within seconds. It needs an APK built with your own Firebase
project and that project's key on the server; the prebuilt APK cannot receive push from your
server. See [Build your own with push](../install/android.md#build-your-own-with-push). While push
is working, the background check relaxes to hourly; if the server reports a push the phone never
received, it goes back to every 10 minutes at once.

### Answer from the lock screen

A "needs you" notification carries the question and up to three of its options as buttons, with
long labels shortened. Tapping one answers without opening the app or unlocking the phone. The
answer carries a fingerprint of the question, and the server refuses it if the question has
changed or was answered elsewhere; the notification then reads **The question moved on**.
Questions that take several answers have no buttons; open the session to answer them.

A finished chat's notification is a conversation thread. **Reply** sends your answer as the next
message in that chat. Free text needs the phone unlocked; the answer buttons do not.

When a session is moved to a smaller model because a usage window ran out, the notification
carries **Undo**.

## Server alerts: push and the relay

The server watches every session and chat itself, every 10 seconds, so it can reach you when no
app is running. These session and chat alerts (a session needs you, a chat finished, a long
session went idle) are **off** until you turn them on.

For each alert, the server:

1. pushes it to every Android app registered for push, if push is set up;
2. hands it to the relay script, if one is configured and the mode says so.

The server's other notices (usage limits, model moves, account switches, an expiring login) do
not depend on this switch: each is pushed, and goes to the relay when no push was delivered.
Watched computers going offline or coming back go to the relay only.

| Mode | The relay gets an alert when |
|---|---|
| Only when the app is out of contact (default) | no push reached a phone, and no app is listening: no Android app streamed in the last 3 minutes or checked in within the last 22 minutes, and no huginn core (or desktop app) is claiming. An Android app whose notifications Android blocks does not count. |
| Always | every alert, in addition to push and the apps. |

A session or chat alert that reached nobody (no push delivered, and the relay exited non-zero or
timed out) is tried again on the next pass.

### Turn them on

In the Android app, **Settings > Notifications**:

- **Message me when a session needs me** turns server alerts on. It also covers finished chats
  and long sessions.
- **Only when the app is out of contact** is the mode: on for the default, off for always.

The app offers the first switch only when the server has a relay script. Without one (push
only), turn alerts on from the server, as root:

```bash
TOKEN=$(cat /etc/huginn-appd/token)
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"enabled":true}' http://127.0.0.1:8787/v1/alerts
```

`GET /v1/alerts` shows the state: whether alerts are on, the mode, how many have been delivered,
whether a relay is configured (`channel`), whether an app is listening (`appOnline`), and whether
push is configured and how many phones are registered. `POST` takes `enabled` and `mode`
(`fallback` or `always`). See [API](../reference/api.md).

## The relay script

The relay is a bash script you write. The daemon runs it as

```text
bash <script> --message <text> --source huginn-app
```

and counts the alert as delivered when it exits 0 within 30 seconds. It runs as root inside the
daemon's sandbox: it can reach the network and read files, but write only under `/root`, `/tmp`,
`/run` and `/var/lib/huginn-appd`. The variable is named for Telegram, but the script can deliver
anywhere: Telegram, ntfy, email, Matrix.

The text is a plain statement, never a question, because a relay has no way to carry a reply:

```text
▲ build needs you
Asked: Do you want to proceed?
1) Yes
2) Yes, and don't ask again for this command
3) No
```

```text
■ chat finished: disk usage
/var/log/journal is using 3.1 GB; the rest is under 200 MB.
```

### Example: Telegram

This example sends alerts through a Telegram bot with `curl`. It is a starting point; adapt it to
your setup.

1. Create a bot with Telegram's @BotFather and note its token. Send your bot a message, then read
   your chat id from `https://api.telegram.org/bot<token>/getUpdates`.
2. Keep both out of the unit files (anyone on the server can read those). As root:

   ```bash
   mkdir -p /etc/huginn
   install -m 600 /dev/null /etc/huginn/telegram.env
   cat > /etc/huginn/telegram.env <<'EOF'
   TELEGRAM_BOT_TOKEN=123456789:replace-with-your-bot-token
   TELEGRAM_CHAT_ID=123456789
   EOF
   ```

3. Save the script as `/usr/local/bin/huginn-relay` (`chmod 755` it, so you can also run it by
   hand):

   ```bash
   #!/usr/bin/env bash
   # Example alert relay for huginn-appd: sends --message to a Telegram chat.
   # Called as: bash huginn-relay --message "<text>" --source huginn-app
   set -euo pipefail

   # shellcheck source=/dev/null
   [ -r /etc/huginn/telegram.env ] && . /etc/huginn/telegram.env
   : "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN is not set}"
   : "${TELEGRAM_CHAT_ID:?TELEGRAM_CHAT_ID is not set}"

   message=""
   while [ $# -gt 0 ]; do
     case "$1" in
       --message) message="${2-}"; shift ;;
       --source)  shift ;;
     esac
     shift
   done
   [ -n "$message" ] || { echo "huginn-relay: no --message" >&2; exit 2; }

   # Exit status is what tells huginn-appd whether the alert was delivered.
   curl -fsS --max-time 20 -o /dev/null \
     --data-urlencode "chat_id=${TELEGRAM_CHAT_ID}" \
     --data-urlencode "text=${message}" \
     "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage"
   ```

4. Test it by hand:

   ```bash
   bash /usr/local/bin/huginn-relay --message "test from huginn" --source huginn-app
   ```

5. Point the daemon at it in `/etc/systemd/system/huginn-appd.service.d/site.conf`:

   ```ini
   [Service]
   Environment=HUGINN_APPD_TELEGRAM_SCRIPT=/usr/local/bin/huginn-relay
   ```

   ```bash
   sudo systemctl daemon-reload && sudo systemctl restart huginn-appd
   ```

6. Turn server alerts on (see [Turn them on](#turn-them-on)).

If alerts do not arrive, the daemon's log says why:

```bash
journalctl -u huginn-appd -t huginn-appd | grep 'alerts:'
```
