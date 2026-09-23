# Aurora for Android

Android client of Aurora VPN — a fork of [SFA](https://github.com/SagerNet/sing-box-for-android),
the sing-box client, under the same GPL-3.0 license.

What Aurora adds on top of SFA:

- **Share links and subscriptions.** `vless://`, `vmess://`, `trojan://`, `ss://`,
  `hysteria2://`, `socks://` and subscription URLs are imported from a QR code or the
  clipboard, including the QR codes Aurora for Windows shows. Every server lands in a
  `selector` + `urltest` group.
- **Subscriptions that actually run.** Panels often answer sing-box clients with JSON
  that sing-box 1.13 refuses to start; such profiles are rebuilt from the share links.
- **Ping all.** One button checks every server of the profile by sending real traffic
  through it, with or without the VPN connected.
- **Updates from GitHub.** Settings → check for updates pulls releases of this
  repository and installs the APK matching the device's CPU.

## Building

The core is sing-box built as `libbox.aar`, with additions in
`patches/sing-box-aurora.patch`: probing every server for *Ping all*, and an
`xray` outbound that runs one server in an embedded xray-core — for XHTTP, the
transport Russian whitelist-bypass servers use. It needs Go 1.26 or newer.

```sh
git clone https://github.com/SagerNet/sing-box && cd sing-box
git checkout v1.13.21           # the core version this release was built with
git am /path/to/aurora-android/patches/sing-box-aurora.patch
git tag v1.13.21-aurora-xray    # the core reports this; Aurora for Windows sends
                                # XHTTP servers only to a phone that does
go run ./cmd/internal/build_libbox -target android   # needs ANDROID_NDK_HOME
cp libbox.aar libbox-legacy.aar /path/to/aurora-android/app/libs/
```

Then `./gradlew assembleOtherDebug`. Releases are signed with the same key every
time — an APK signed with another key will not install over an existing one.

## Upstream

## Documentation

https://sing-box.sagernet.org/installation/clients/sfa/

## License

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

Under the license, that forks of the app are not allowed to be listed on F-Droid or other app stores
under the original name.
