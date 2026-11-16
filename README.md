# Hotspot Statistics

An Android app that shows everything it can learn about the Wi-Fi hotspot running on the
device: whether it is on, its configuration, the devices connected to it, their IP and MAC
addresses, latency and signal strength, live traffic, data usage, and the upstream
connection being shared.

## Screens

| Tab | What it shows |
|---|---|
| **Overview** | Hotspot state, interface, hotspot IP / subnet / IPv6, connected-client count; configuration (SSID, password, security, band, channel); live traffic to and from clients; hotspot data usage today and for the last 30 days; root mode; local-only hotspot |
| **Clients** | Every device found on the hotspot: hostname, device type, IP, MAC (and whether it is a private random MAC), ping latency, open ports, ARP state, first / last seen. With root + `iw`: Wi-Fi signal (dBm and bars), TX/RX link rate, bytes and packets, retries, connected time |
| **Interfaces** | All network interfaces, classified (Wi-Fi hotspot, USB / Bluetooth tethering, cellular, VPN…), with addresses, MAC, MTU, live rates and counters |
| **Upstream** | The network the hotspot shares: transport, validation, metered, bandwidth estimate, gateway, DNS, private DNS; Wi-Fi RSSI / link speed / frequency, or cellular operator, generation (4G / 5G NSA / 5G SA), RSRP / RSRQ / SINR and level; device traffic since boot |

## What Android allows, and how the app works around it

Android deliberately hides hotspot internals from normal apps. The app layers sources and
always says where a value came from or why it is missing.

| Information | Without root | With root |
|---|---|---|
| Hotspot on / off | Hidden `WifiManager.getWifiApState()` via reflection, else the sticky `WIFI_AP_STATE_CHANGED` broadcast | same |
| Hotspot interface | Sticky `TETHER_STATE_CHANGED` broadcast + `NetworkInterface` | same |
| SSID / password / band | Not available (system-only API). Available for the app's own **local-only hotspot** | Read from `WifiConfigStoreSoftAp.xml` |
| Connected clients | Active sweep of the hotspot subnet: ICMP echo and TCP connects (a refused connection still proves the host exists) | + ARP / neighbour table, `iw station dump`, DHCP leases |
| Client MAC | Only if the ARP table is readable (blocked on most Android 10+ devices) | ARP / neighbour table |
| Client hostname | mDNS reverse lookup (Apple, Linux, many IoT) and NetBIOS (Windows) | + DHCP leases (Android ≤ 10) |
| Client signal strength / link rate | **Not available** — Android has no API for it | `iw dev <if> station dump`, if the `iw` binary is installed |
| Interface traffic counters | `/sys/class/net/<if>/statistics` where readable | always |
| Hotspot data usage | `NetworkStatsManager` tethering bucket, after granting **Usage access** | same |
| Upstream signal | `NetworkCapabilities`, `WifiInfo`, `TelephonyManager.getSignalStrength()` | same |

Consequences worth knowing:

- Without root, a client that answers neither ping nor any probed TCP port (some firewalled
  laptops) can be missed. Phones usually answer.
- Clients are swept fully every 45 s or when you tap **Scan**; known clients are re-pinged
  every 8 s. A client that misses a pass stays listed for 90 s, marked with its last-seen time.
- Root mode works with Magisk, KernelSU and similar. Per-client signal additionally needs
  an `iw` binary on the device (not part of stock Android; Magisk modules provide it).

## Permissions

| Permission | Why | Required? |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Probing clients, reading network state | Yes (granted at install) |
| `CHANGE_WIFI_STATE` + `NEARBY_WIFI_DEVICES` (13+) or location (≤ 12) | Starting the local-only hotspot | Only for that feature, asked when used |
| `READ_PHONE_STATE` | Exact cellular network type | Optional, asked from the Upstream tab |
| Usage access (special setting) | Hotspot data usage | Optional, from the Overview tab |

The app collects nothing and sends nothing off the device; all probes stay on the hotspot subnet.

## Building

Requirements: JDK 17+ and the Android SDK (platform 35, build-tools 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
```

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests (parsers for ARP, `ip neigh`, `iw`, soft-AP XML, DHCP leases, NetBIOS and
mDNS packets, plus the client merge logic) and lint:

```bash
./gradlew testDebugUnitTest lintDebug
```

## Project layout

```
app/src/main/java/com/triggerex/hotspotstats/
  data/
    Models.kt               data classes
    Parsers.kt              pure parsers and packet builders (unit tested)
    ClientMerger.kt         merges all client sources (unit tested)
    ClientScanner.kt        subnet sweep, latency, names, ARP / iw / leases
    HotspotStateReader.kt   AP state and tethered interfaces
    InterfaceReader.kt      interfaces, counters, rates
    UpstreamReader.kt       shared network and signal
    UsageReader.kt          hotspot data usage
    LocalHotspotController.kt
    ShellRunner.kt          shell / su with timeouts
    HotspotRepository.kt    composes the above
  ui/
    HotspotViewModel.kt     refresh loops (pause when the app is in the background)
    MainActivity.kt, screens/, theme/
```

Stack: Kotlin 2.1, Jetpack Compose (Material 3), coroutines; minSdk 26, targetSdk 35.

## Status

The app builds, lint passes and the unit tests pass. It has not yet been exercised on a
physical device, and hotspot behaviour varies a lot between manufacturers (interface names,
hidden-API availability, SELinux rules), so expect some device-specific gaps.
