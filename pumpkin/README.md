<div align="center">

# LuckPerms — Pumpkin

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](https://opensource.org/licenses/MIT)
[![Build](https://github.com/Sqrilizz/LuckPerms/actions/workflows/build.yml/badge.svg)](https://github.com/Sqrilizz/LuckPerms/actions)

A [LuckPerms](https://luckperms.net/) platform adapter for [PumpkinMC](https://pumpkinmc.org/) via [PatchBukkit](https://github.com/Pumpkin-MC/PatchBukkit).

Developed by [Sqrilizz](https://github.com/Sqrilizz) on top of LuckPerms by [Luck](https://github.com/lucko).

</div>

> [!IMPORTANT]
> This adapter is currently under active development and tracks PatchBukkit compatibility as it evolves.

## About

LuckPerms-Pumpkin brings the full LuckPerms permissions system to PumpkinMC servers. It is **not** a rewrite — it's a platform adapter that preserves LuckPerms' mature architecture while adapting it for Pumpkin's multi-threaded, async-oriented runtime.

## Features

- [x] Plugin loading via PatchBukkit
- [x] `/luckperms` command and all subcommands
- [x] Web editor integration
- [x] Storage backends — H2, SQLite, MySQL, PostgreSQL, MongoDB
- [x] Permission resolution — inheritance, wildcards, negation, cached calculations
- [x] Per-player `LuckPermsPermissible` injection (1:1 Bukkit parity)
- [x] Server-level `PermissionMap` / `DefaultsMap` / `SubscriptionMap` injection
- [x] Child-permission resolution via `PumpkinPermissionMap`
- [x] Contexts — gamemode, world
- [x] Groups, tracks, meta, prefixes, suffixes
- [x] Multi-language translations
- [x] Cross-server messaging (`luckperms:update` channel)
- [x] Diagnostics & benchmarks (`pumpkin-diag`, `pumpkin-benchmark`)
- [x] Native `BukkitScheduler` (implemented in PatchBukkit — async tasks use native scheduler)
- [x] `ServicesManager` / `LuckPerms` API registration (implemented in PatchBukkit)
- [ ] Vault integration (Vault plugin not yet available for PatchBukkit)
- [ ] Plugin messaging (`getMessenger()` not yet implemented in PatchBukkit)

## Installation

1. **Requirements**: [PumpkinMC](https://pumpkinmc.org/) with [PatchBukkit](https://github.com/Pumpkin-MC/PatchBukkit) installed, Java 21+
2. **Download**: Grab `LuckPerms-Pumpkin-*.jar` from the [releases](https://github.com/Sqrilizz/LuckPerms/releases)
3. **Deploy**: Place the JAR into `patchbukkit/patchbukkit-plugins/`
4. **Start**: Start your Pumpkin server — config generates on first run

```
patchbukkit/
  └── patchbukkit-plugins/
        └── LuckPerms-Pumpkin-*.jar
```

## Building

> [!NOTE]
> Requires Java 21 JDK or newer and Git.

```sh
git clone https://github.com/Sqrilizz/LuckPerms.git
cd LuckPerms/
./gradlew :pumpkin:loader:shadowJar
```

Output: `pumpkin/loader/build/libs/LuckPerms-Pumpkin-*.jar`

## Commands

```
/luckperms user <name>       # Manage user permissions
/luckperms group <name>      # Manage group permissions
/luckperms track <name>      # Manage tracks
/luckperms editor            # Open web editor
/luckperms sync              # Sync data from storage
/luckperms verbose           # Verbose permission checking
/luckperms tree              # Permission tree viewer
/luckperms log               # View action log
/luckperms info              # Plugin info
/luckperms pumpkin-diag      # Thread & cache diagnostics  (requires luckperms.admin)
/luckperms pumpkin-benchmark # Permission benchmarks        (requires luckperms.admin)
```

> [!NOTE]
> Aliases `lp`, `perm`, `perms` are also registered.

## PatchBukkit Compatibility

| API | Status |
|---|---|
| Commands | ✅ Supported |
| Player events (login/quit/gamemode/world) | ✅ Supported |
| `PermissibleBase` injection | ✅ Supported |
| `PluginManager` map injection | ✅ Supported |
| `ServicesManager` | ✅ Implemented — LP API registered on startup |
| `BukkitScheduler` | ✅ Implemented — native async scheduling |
| `getMessenger()` | ❌ Not implemented in PatchBukkit |
| `getWorlds()` | ❌ Not implemented — world context skipped |

## Development Roadmap

- [x] **Phase 1** — Initial port (plugin loading, commands, permissions, storage)
- [x] **Phase 2** — Compatibility fixes (threading, player lifecycle, events)
- [x] **Phase 3** — Performance layer (caches, concurrent reads, benchmarks, diagnostics)
- [x] **Phase 3.5** — Full server-level permission infrastructure (1:1 Bukkit parity)
- [ ] **Phase 4** — Pumpkin-native improvements (native scheduler ✅, plugin messaging pending)

## Contributions

Contributions are welcome! Please follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) and try to match the style of the file you're editing.

## License

LuckPerms is licensed under the permissive [MIT License](https://github.com/LuckPerms/LuckPerms/blob/master/LICENSE.txt).
