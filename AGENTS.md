# AGENTS.md

LeiCraftClient — a client-side Fabric mod for Minecraft 26.3 that renders LeiCraft
capes and cosmetics from `capesapi.leicraftmc.de`.

## House style

This project follows the [LeiCraftMC Style Guides](https://git.leicraftmc.de/LeiCraftMC/Style-Guides).
That guide is written for the TypeScript/Bun stack; the parts that carry over to
Kotlin are the ones that matter here:

- **Static-class services, no DI.** A logical service is a Kotlin `object` with
  its state `private`, the direct equivalent of the guide's `static` classes
  (`DB`, `Logger`, `ConfigHandler`). Applies to `CapesApi`, `CapeManager`,
  `CosmeticsManager`, `TextureStore`, `ClientConfig`, `CpmParser`, `BackendGate`.
- **Namespace-for-types.** A service's types are nested inside it rather than
  scattered across the package — `CpmModel.Node`, `CpmModel.AttachPoint`,
  `CapesApi.CosmeticEntry`, `CosmeticsManager.Loaded` — mirroring
  `namespace DB.Models` / `AuthHandler.AuthContext`.
- **Naming.** Classes and objects PascalCase, functions and properties
  camelCase, constants `SCREAMING_SNAKE`. Packages are lowercase single words
  (a JVM requirement, so the guide's kebab-case directory rule does not apply).
  Kotlin files are named after their primary type, which is the JVM convention
  and overrides the guide's camelCase-module rule.
- **Conventional Commits** (`feat:`, `fix:`, `chore:`, …), lowercase type,
  imperative summary.
- **AGPL-3.0**, the org default.
- Default branch `main`; CI must pass before merge.

## Commands

```sh
./gradlew build                          # compile + test + jar (build/libs)
./gradlew test                           # CPM transform tests only
./gradlew runClient                      # dev client
./gradlew runClient -PdevUsername=Name   # dev client as a real profile
```

Needs **JDK 25** — Minecraft 26.3 requires it.

## Before you change rendering

Read [`README.md`](README.md) first, in particular *The CPM coordinate transform*.
The three invertAxis rules there are load-bearing and each is pinned by a test in
`src/test/kotlin/.../CpmParserTest.kt`. If you change `CpmParser`, those tests
must still pass — they encode behaviour verified against real OptiFine models,
not guesses.

`CpmParser` deliberately imports no Minecraft types so it stays unit-testable on
a plain JVM. Keep it that way.

## Gotchas

- **Mixins must be Java.** The Mixin processor does not handle Kotlin classes
  reliably; `AbstractClientPlayerMixin` is a thin Java shim that calls into
  Kotlin. There is exactly one mixin — keep it that way if you can.
- **26.3 is unobfuscated.** The build uses the `net.fabricmc.fabric-loom`
  plugin (not the short `fabric-loom` id, which is the legacy remapping
  variant), declares no `mappings` dependency, and uses `implementation`
  rather than `modImplementation`.
- **Never call OpenGL.** Go through vanilla's `VertexConsumer` / `RenderType` /
  `submitCustomGeometry`, so the Vulkan transition does not break the mod.
- **Texture identifiers.** `ClientAsset.ResourceTexture(id)` rewrites the path to
  `textures/<id>.png`; pass both components when the texture is one we registered
  ourselves.
