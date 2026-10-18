# Install the Android app

The Android app shows your sessions and chats, answers permission prompts, and notifies you when a
session needs you, including from the lock screen. It pairs with the server directly; it does not
need huginn core or the `huginn` command. What it does: [The apps](../guide/apps.md).

## Requirements

- Android 10 or later, on a 64-bit ARM phone or tablet (arm64-v8a).
- The [server](server.md), reachable from the phone over your LAN or a VPN you control
  ([Networking](networking.md)).
- The server's access token: `sudo cat /etc/huginn-appd/token` on the server.

## Install

1. On the phone, open <https://github.com/silencelen/huginn/releases> and find the newest release
   whose tag starts with `app-v` (for example `app-v3.11.1`). The releases page also lists the
   other components; the Android app is only in `app-v` releases.
2. Download `Huginn-<version>.apk` from its assets.
3. Open the downloaded file. Android asks whether to allow installing apps from this source (your
   browser or file manager); allow it, then tap **Install**.

Each release also carries `latest.json`, whose `sha256` field is the APK's checksum. To check a
download on a computer, compare it with `sha256sum Huginn-<version>.apk`; see
[Verify a download](verify.md).

## Pair with your server

There is no QR code: you enter the address and paste the token.

1. Open Huginn, then **Settings** (the gear) **> Host & sign-in**.
2. Under **Routes**, tap **Add a route** and enter the server's address with the port, for
   example `192.168.1.20:8787`. The app always connects over HTTPS (plain HTTP only to the phone
   itself), so the address is stored as `https://192.168.1.20:8787`.
3. Paste the token into **Token** and tap **Save**.

Before it sends the token anywhere, the app checks that the address proves it holds the same
token, and pins the server's certificate. If the check fails, the app says *this address could
not prove it is your huginn daemon; someone may be intercepting the connection* and does not
connect.

**More than one way in.** Add a second route for the address your VPN gives the server; routes
are tried in order, up to eight, and **Switch automatically** moves to the next one that answers.
Android runs one VPN at a time, so which address works can change.

**The token is root.** Anyone holding it controls the server. If you lose the phone, change the
token; see [Security](../security.md).

Then open **Settings > Notifications** and allow notifications and background use; see
[Notifications](../guide/notifications.md).

## Updates

**Settings > Updates & diagnostics > Software update** shows the installed version. Opening
Settings checks the project's GitHub releases for a newer `app-v` release. Nothing downloads or
installs without a tap:

1. **Download** fetches the APK and checks it against the `sha256` in that release's
   `latest.json`. A mismatch is discarded.
2. **Install and restart** hands the verified file to Android's installer. The first time,
   Android asks you to allow Huginn to install apps.

You can also install a newer APK from the releases page over the old one. Either way, Android
installs an update only when it is signed with the same key as the app already installed.

## Notifications: the prebuilt APK and your own build

Instant notifications to a sleeping phone use Firebase Cloud Messaging (FCM). A server can only
push to an app built against the same Firebase project as the key it holds, and the prebuilt APK
is built against the project's own Firebase project, whose key your server does not have. So the
prebuilt APK never receives push from your server. It notifies you by watching the server itself.

| | Prebuilt APK | Your own build, with push |
|---|---|---|
| Phone awake, **Watch continuously** on | within seconds | within seconds |
| Phone asleep | at the next background check, about every 10 minutes | within seconds |
| A long session went idle | only through the server's [relay script](../guide/notifications.md#the-relay-script) | yes |
| Answer buttons on the lock screen | yes | yes |
| **Settings > Notifications > Delivery** reads | check only | push |

Nothing is missed with the prebuilt APK; asleep, it is slower. If that is enough, you are done.

## Build your own with push

You need a Google account for Firebase, a clone of the repository, and the Android build
toolchain ([Building](../dev/building.md)).

1. **Create a Firebase project** in the [Firebase console](https://console.firebase.google.com/).
   In it, add an Android app with the package name `com.silencelen.huginn` (it must be exactly
   this). Download the `google-services.json` it offers.
2. **Add it to the source:**

   ```bash
   git clone https://github.com/silencelen/huginn.git
   cp ~/Downloads/google-services.json huginn/mobile/app/google-services.json
   ```

   The build fails without this file; `mobile/app/google-services.json.example` shows its shape.
   It is ignored by git, so it stays yours.
3. **Create a signing key** once, and keep it safe; every later build must be signed with it to
   install as an update:

   ```bash
   mkdir -p ~/.huginn-app
   keytool -genkeypair -keystore ~/.huginn-app/release.jks -alias huginn-release \
     -keyalg RSA -keysize 4096 -validity 10000
   cat > ~/.huginn-app/keystore.properties <<EOF
   storeFile=$HOME/.huginn-app/release.jks
   storePassword=<the keystore password>
   keyAlias=huginn-release
   keyPassword=<the key password>
   EOF
   chmod 600 ~/.huginn-app/keystore.properties
   ```

   `HUGINN_KEYSTORE_PROPERTIES` points the build at a properties file elsewhere. Without one, the
   release APK is built unsigned and cannot be installed. (A debug build, `:app:assembleDebug`,
   is signed with the debug key in the repository instead. Anyone can sign with that key, so
   anyone could build an "update" your phone would accept; prefer your own key.)
4. **Build:**

   ```bash
   cd huginn/mobile
   ./gradlew :app:assembleRelease
   ```

   The APK is copied to `mobile/dist/` as `Huginn-release-<versionCode>-<time>.apk`.
5. **Put the project's key on the server.** In the Firebase console, open **Project settings >
   Service accounts** and click **Generate new private key**. Copy the JSON file to the server and,
   as root:

   ```bash
   install -m 600 -o root -g root fcm-key.json /etc/huginn-appd/fcm-service-account.json
   systemctl restart huginn-appd
   journalctl -u huginn-appd -t huginn-appd | grep 'push:'
   ```

   Look for `push: FCM ready for project <your-project-id>`. `push: FCM not configured (…)` says
   what is wrong. To keep the key elsewhere, set `HUGINN_FCM_KEY` to its path in
   `/etc/systemd/system/huginn-appd.service.d/site.conf`:

   ```ini
   [Service]
   Environment=HUGINN_FCM_KEY=/etc/huginn-appd/my-firebase-key.json
   ```

6. **Install your APK on the phone.** If the prebuilt APK is installed, uninstall it first:
   Android refuses to install an app signed with a different key over it. Uninstalling removes the
   app's settings, so pair again.
7. **Check push.** The app registers for push each time it starts. **Settings > Notifications >
   Delivery** should read **push**.
8. **Turn on server alerts**, which push uses for sessions and chats: **Settings > Notifications >
   Message me when a session needs me**, or from the server if you have no relay script; see
   [Server alerts](../guide/notifications.md#turn-them-on).

### Updating your own build

Your build and the prebuilt APK are signed with different keys, so neither can update the
other.

- The app's **Software update** still looks at the project's releases and compares version codes
  only. Your build's version code is the time it was built, so once a newer release is published
  the app offers it, and Android refuses to install it. Ignore that offer.
- To update, pull the new source and repeat the build with the same `google-services.json` and
  the same signing key, then install the new APK over the old one. Your settings and pairing are
  kept.
- To go back to the prebuilt APK, uninstall your build first, then install and pair the prebuilt
  one. Its notifications work as described for the prebuilt APK; the server's Firebase key cannot
  reach it, so remove the key file and restart the daemon if nothing else uses it.
