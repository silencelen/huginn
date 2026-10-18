# mobile/

The Kotlin clients and the code they share, built with Gradle:

| Module | What |
|---|---|
| `core/` | shared logic, models and the HTTP client (JVM and Android targets) |
| `ui/` | shared Compose UI: transcript rows, the terminal view, settings rows, theme |
| `app/` | the Android app |
| `app-desktop/` | the desktop app for Windows and Linux (talks to the server through huginn core) |

- What the apps do: [docs/guide/apps.md](../docs/guide/apps.md).
- Install the Android app: [docs/install/android.md](../docs/install/android.md).
- Build and test: [docs/dev/building.md](../docs/dev/building.md),
  [docs/dev/testing.md](../docs/dev/testing.md) and
  [docs/dev/kotlin-clients.md](../docs/dev/kotlin-clients.md).
- The daemon's HTTP API: [docs/reference/api.md](../docs/reference/api.md).
