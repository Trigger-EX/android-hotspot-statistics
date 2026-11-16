# Plan: Hotspot Statistics app

## Goal
An Android app that shows everything it can learn about the Wi-Fi hotspot hosted on the
device: hotspot state and configuration, the tethering interface, connected clients (IP,
MAC, hostname, latency, signal), traffic counters and the upstream network that the
hotspot shares, including its signal strength.

## Constraint that shapes the design
Android hides hotspot internals from ordinary apps:

| Data | Official API | Status for a normal app |
|---|---|---|
| Hotspot on/off | `WifiManager.isWifiApEnabled` (hidden) + `WIFI_AP_STATE_CHANGED` broadcast | Reflection works on most builds; broadcast always works |
| SSID / password / band | `getSoftApConfiguration` (system API) | Not available, except for a LocalOnlyHotspot the app starts itself |
| Client list / client RSSI | `SoftApCallback.onConnectedClientsChanged` (system API) | Not available |
| `/proc/net/arp`, `ip neigh` | – | Blocked by SELinux on Android 10+/13+ (varies) |
| Interface IPs / MTU | `java.net.NetworkInterface` | Available |
| Interface byte counters | `/sys/class/net/<if>/statistics` | Often readable, sometimes not |
| Upstream network & signal | `ConnectivityManager`, `NetworkCapabilities`, `TelephonyManager.getSignalStrength` | Available |

So every data point uses layered sources, and the UI always says where a value came
from or why it is missing:

1. **Unprivileged** (all devices): reflection + broadcast for state, `NetworkInterface`
   for the tethering interface, an active subnet sweep (TCP probes + `ping`) to discover
   clients, round-trip latency per client, reverse DNS + NetBIOS name query for
   hostnames, best-effort ARP/neighbour reads for MACs.
2. **Root** (optional, detected via `su`): `/proc/net/arp`, `ip neigh`,
   `iw dev <if> station dump` (per-client signal dBm, bitrates, connected time, bytes)
   and the saved soft-AP config XML (SSID, passphrase, security, band, channel).
3. **Local-only hotspot** (optional): the app can start its own LocalOnlyHotspot, for
   which Android hands back the full SSID/passphrase/security configuration.

## Stack
Kotlin 2.1, Jetpack Compose + Material 3, single `AndroidViewModel`, coroutines.
minSdk 26, compile/targetSdk 35. AGP 8.7.3, Gradle 8.14.3 wrapper.

## Files
- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, wrapper
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, resources (theme, icon, strings)
- `data/Models.kt` – all data classes
- `data/Parsers.kt` – pure parsers (arp, ip neigh, iw station dump, soft-AP XML, NetBIOS reply, MAC helpers) – JVM unit tested
- `data/ShellRunner.kt` – run commands with/without `su`, with timeouts
- `data/HotspotStateReader.kt` – AP state (reflection + sticky broadcast), tethering interface detection
- `data/InterfaceReader.kt` – `NetworkInterface` + sysfs counters + rate calc
- `data/ClientScanner.kt` – subnet sweep, ARP/neigh merge, ping RTT, hostname resolution, iw merge
- `data/UpstreamReader.kt` – active network, transports, bandwidth, signal, cellular details
- `data/LocalHotspotController.kt` – start/stop LocalOnlyHotspot, expose config
- `data/HotspotRepository.kt` – composes a snapshot
- `ui/HotspotViewModel.kt` – periodic refresh, scan trigger, root toggle
- `ui/MainActivity.kt`, `ui/screens/*` – Overview, Clients, Interface, Upstream tabs; theme
- `app/src/test/...ParsersTest.kt`
- `README.md` – features, what needs root and why, build instructions

## Order
1. Gradle skeleton → 2. models + parsers + tests → 3. readers/scanner/root → 4. repository + VM →
5. UI → 6. README.

## Verification
`./gradlew testDebugUnitTest assembleDebug lintDebug` must pass. No device in the container,
so on-device behaviour is checked only by reasoning; README states this.
