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

---

# Plan: packet capture for selected hosts

## Constraint
Hotspot traffic is forwarded inside the kernel. Without root, no app can see other
devices' packets (VpnService only sees the phone's own traffic; tethering bypasses it).
So capture requires root. Stock Android has no `tcpdump`, so the app ships its own helper.

## Design
- **Native helper `hscapture`** (C, no dependencies), built with the NDK for
  arm64-v8a / armeabi-v7a / x86_64 / x86 and packaged as `libhscapture.so` in jniLibs
  (legacy packaging so it is extracted to `nativeLibraryDir` and executable). Run as root via `su`.
  - `AF_PACKET` raw socket bound to the hotspot interface, both directions.
  - Userspace filter: match Ethernet src/dst MAC or IPv4 src/dst (incl. ARP sender/target)
    against the selected hosts; no filter = all hotspot traffic.
  - pcap (libpcap format, microsecond) written to **stdout**, so the file is created by
    the app itself (avoids root-owned / SELinux-mislabelled files in app storage).
  - Status on **stderr**, one line each: `S` stats every second (packets, bytes, kernel drops),
    `P` rate-limited per-packet summaries (proto, addresses, ports, TCP flags, DNS name), `E` errors, `X` end.
  - Stops on `q` / EOF on stdin, on EPIPE, or on `-d` duration / `-c` count limits.
    poll() on socket + stdin, so no busy loop. Never needs to be killed by the app.
- **`CaptureSession`** (Kotlin): starts the helper through `su`, pumps stdout to
  `files/captures/<name>.pcap` with a size cap, parses stderr into a `StateFlow`.
- **`CaptureManager`** (process singleton): one active session, list of saved captures,
  delete, share via `FileProvider`.
- **`CaptureService`**: foreground service so capture continues with the app in the
  background; notification with live counters and a Stop action.
- **UI**: new **Capture** tab — pick hosts (checkboxes over current clients, or all),
  limits (duration, size, headers-only snaplen), Start/Stop, live stats and packet list,
  saved captures with Share / Delete. Client cards get "Capture this device".
- Input validation: interface, IPs and MACs checked by regex before building the command.

## Verification
- Compile the helper with host gcc and run it as root on `lo` in this container:
  ping filtered vs unfiltered traffic, check the pcap with Python and the stderr protocol.
- JVM tests for the stderr line parser and argument building / validation.
- `testDebugUnitTest assembleDebug lintDebug`; confirm the APK contains `libhscapture.so` for every ABI.
