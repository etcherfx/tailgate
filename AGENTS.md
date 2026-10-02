# Tailgate

Tailgate is a client-only Minecraft mod that lets players join a friend's server shared with Tailscale Funnel. An in-game forwarder listens on `127.0.0.1` and carries the connection to the Funnel address over verified TLS. One merged jar runs on Fabric/Quilt 1.14+, Forge 1.7.10+ and NeoForge 1.20.2+, up to 26.3.

## Setup and verification

Gradle runs on JDK 25 (`gradle/gradle-daemon-jvm.properties`); starting `./gradlew` needs JDK 17+ on `JAVA_HOME` or `PATH`. Everything compiles to Java 8 bytecode. Cheapest first:

```sh
./gradlew :core:test -Ptailgate.nodes=none          # core logic, no Minecraft (seconds)
./gradlew build                                      # core tests + the active node (26.3-fabric)
./gradlew build -Ptailgate.nodes=1.20.1-forge,1.12.2-forge   # the nodes your change touches
./gradlew build -Ptailgate.nodes=all                 # every node, then mergeJar + verifyJar (slow; tens of GB of setups)
./gradlew runtimeTest --node 1.20.1-forge -Ptailgate.nodes=none   # launch a real game with the jar in build/libs
```

- `-Ptailgate.nodes` picks the UI nodes Gradle configures: `none`, `all`, or a comma-separated list (the active node is always added). Each node downloads and remaps its own Minecraft, so configure only what you need.
- `-Ptailgate.testJava=<8|17|21|25>` runs the core tests on that runtime.
- `runtimeTest` needs only JDKs; on Linux wrap it in `xvfb-run`. Options: `--timeout`, `--jar`, `--loader-version`; `-Ptailgate.runtimeDir=<dir>` moves its downloads out of `build/runtime-test`.
- `./gradlew ciMatrix -Ptailgate.nodes=none` prints the CI build groups and runtime smoke set.

## Code map

- `core/`: forwarder, TLS connector, Funnel address parsing, server store, form logic, self-test report. Plain Kotlin, no Minecraft classes; the only part with unit tests.
- `entry/`: loader entry points (Java) and `Dispatcher`, which detects the running game and loads the matching UI build.
- `ui/`: the screens, compiled once per node with Stonecutter + Unimined. `ui/src/main/*/mojmap` covers 1.14+; `ui/src/main/*/mcp` covers Forge 1.7.10–1.13.2.
- `stubs/`: compile-only copies of the loader APIs `entry/` touches; never shipped.
- `build-logic/`: the Gradle plugins and tasks. `Targets.kt` reads `targets.toml`; `TailgateNodePlugin` builds a node; `MergeJar`/`Relocator` combine and relocate; `VerifyJar` checks the result; `Metadata.kt` writes every loader's metadata; `CiMatrix` and `RuntimeTest` drive CI.
- `targets.toml`: every (Minecraft release, loader) pair and its loader version, MCP mappings and `[compile-as]` substitutions.
- `.github/workflows/build.yml`: plan → core tests (Java 8/17/21/25) → node groups → merge → runtime tests → release on `v*` tags.

## Constraints and conventions

- Java 8 bytecode everywhere, and no Kotlin stdlib at runtime except the relocated copy `MergeJar` shades in. `verifyJar` enforces both.
- Version differences in `ui/` use Stonecutter comments (`//? if >=1.20 {`), not runtime checks. When you change a cutoff, add the boundary release to the `SMOKE` list in `CiMatrix.kt` so CI launches it.
- New Minecraft releases go in `targets.toml` by hand, from the loader metadata URLs at its top.
- The active node is set in two places that must agree: `Targets.ACTIVE_NODE` and `ui/stonecutter.gradle.kts`.
- Tailgate never disables TLS verification, never listens beyond loopback, and sends no traffic except to the hosts the player added. Keep it that way.
- The version lives only in `gradle.properties` (`mod.version`); the release workflow fails if the tag doesn't match it.
- Commits use Conventional Commits with a DCO sign-off (`git commit -s`).

## Gotchas

- Tasks that don't build the mod (`ciMatrix`, `runtimeTest`, `:core:test`) should run with `-Ptailgate.nodes=none`; otherwise Gradle sets up the active node's Minecraft first.
- The root project can't resolve another project's configuration (Gradle fails with "attempted without an exclusive lock"); `TailgateMergePlugin` declares its own `tailgateSelfTestFixture` configuration instead.
- Under Xvfb, Minecraft 26.3's SDL3 window needs `SDL_VIDEO_FORCE_EGL=1` and `libegl1`; LWJGL 2 (1.12 and older) needs `xrandr`.
- Forge and NeoForge installers report success after a failed library download, and Forge before 1.13 shows a Swing error dialog. `RuntimeTest` runs installers headless, times each install out and retries it.
- `RuntimeTest` installs the loader build in `targets.toml`, not the newest. HeadlessMC can't find Forge 1.10's installers (their `-1.10.0` suffix), so those go through its bundled forge-cli; Forge 1.11–1.12.1 write a broken Mercurius library entry that `RuntimeTest` repairs; Forge 1.16.3/1.16.4 crash on Java 8u321+, so they run on Mojang's own Java 8 runtime.
- maven.minecraftforge.net, maven.neoforged.net and Mojang's servers drop requests now and then. In CI, re-run the failed job before debugging.
- The Actions cache is capped at 10 GB. Node jobs leave Unimined's setups and transforms out of their cache, and `GROUP_SIZE` in `CiMatrix.kt` keeps the group count down; more groups push the cache over and dependencies get evicted.

## Topic docs

- Read the README's [Development](README.md#development) section before changing the build, and its [Security model](README.md#security-model) before touching `core/` networking or TLS.
- Read [CONTRIBUTING.md](CONTRIBUTING.md) before cutting a release.
