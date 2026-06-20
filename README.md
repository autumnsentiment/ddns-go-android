# ddns-go Android

Android APK wrapper for [ddns-go](https://github.com/jeessy2/ddns-go).

This repository is forked/adapted from the upstream ddns-go project and packages
the ddns-go service for Android devices with a local WebView management UI.

Upstream source:

- ddns-go: https://github.com/jeessy2/ddns-go

## What This App Fixes

- Runs the bundled ddns-go arm64 binary from Android's native library directory
  instead of copying and executing it from the app files directory.
- Keeps ddns-go running as a foreground service with CPU and Wi-Fi keepalive
  locks.
- Restarts the ddns-go process if it exits unexpectedly.
- Restarts the service after the Android task is removed.
- Enables LAN access by listening on port `9876`.
- Supports IPv4 and IPv6 access URLs in the foreground notification.
- Enables cleartext HTTP for the local ddns-go Web UI.
- Improves WebView rendering compatibility for the ddns-go UI.
- Fixes Android WebView JavaScript errors such as
  `ReferenceError: defaultDnsConf is not defined`.
- Defaults new Android WebView configurations to IPv6-first and avoids saving
  stale IPv4 update fields when IPv4 is disabled or no IPv4 domain is set.
- Preserves WebView cookies for login/session flow.

## Access

After installing and opening the APK:

- Local WebView: `http://127.0.0.1:9876/`
- LAN IPv4: `http://<phone-ipv4>:9876/`
- LAN IPv6: `http://[<phone-ipv6>]:9876/`

The app notification shows the detected LAN addresses.

## Build

Requirements:

- Android SDK
- JDK 17
- Gradle wrapper included in this repository

Build a debug APK:

```powershell
.\gradlew.bat assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- The bundled executable is currently arm64-v8a only.
- The ddns-go service and Web UI are provided by the upstream ddns-go project.
- This Android wrapper is intended to improve Android runtime behavior,
  background service stability, LAN access, IPv6 access, and WebView rendering.
- If a hostname still resolves to IPv4 after IPv4 is disabled, remove any stale
  A record at the DNS provider and wait for DNS cache/TTL expiry.

## License

This Android wrapper is released under the MIT License. See [LICENSE](LICENSE).

The bundled ddns-go service is from the upstream MIT-licensed ddns-go project:
https://github.com/jeessy2/ddns-go

See [NOTICE.md](NOTICE.md) for upstream copyright and license attribution.
