# NOTICE

## nemix

Copyright (C) 2026 nemix contributors (https://github.com/ShotaXiangtai)

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.

## Based on bitchat-android

The Android app in [`bitchat-android/`](bitchat-android/) is a modified version
of **bitchat-android** by permissionlesstech and contributors, licensed under
the GNU General Public License v3.0.

- Upstream: https://github.com/permissionlesstech/bitchat-android
- Base commit: `998f1684878f1e8aaf2c4d5ad682795acd3cedad` (2026-11-01)
- Modification notice (GPL-3.0 §5(a)): [`bitchat-android/NEMIX_CHANGES.md`](bitchat-android/NEMIX_CHANGES.md)

nemix is an independent project. It is not affiliated with or endorsed by the
bitchat project or permissionlesstech. The application ID was changed to
`im.nemix.android` so that nemix never conflicts with the upstream app.

Third-party components bundled by upstream (for example the Arti/Tor native
libraries under `bitchat-android/tools/arti-build/`) keep their own licenses.

## iOS app

The iOS app in [`nemix-ios/`](nemix-ios/) is based on the **bitchat** iOS app by
permissionlesstech and contributors (https://github.com/permissionlesstech/bitchat),
which is released into the public domain under the Unlicense
([`nemix-ios/UPSTREAM-LICENSE`](nemix-ios/UPSTREAM-LICENSE)). It uses the same mesh and
Nostr protocols as the Android app, so the two can talk to each other.
Base commit: `04995f68e470a18d1fc99f86fa326c2a87bab4f2` (2026-10-25). The macOS target
and macOS binaries were removed. nemix modifications are distributed under GPL-3.0.

## Website

The website in [`website/`](website/) is distributed under GPL-3.0.
