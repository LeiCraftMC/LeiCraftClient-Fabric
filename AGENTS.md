# AGENTS.md

LeiCraftClient — a client-side Fabric mod for Minecraft 26.3 that renders LeiCraft
capes and cosmetics, links Minecraft accounts to LeiCraft accounts and offers an
in-game wardrobe, all through the LeiCraft Client API V2
(`https://api.client.leicraftmc.de`). The API contract is
`../API-Server/docs/client-integration.md`; exact schemas are in the API's
`/docs/v1/openapi`.

## House style

This project follows the [LeiCraftMC Style Guides](https://git.leicraftmc.de/LeiCraftMC/Style-Guides).
That guide is written for the TypeScript/Bun stack; the parts that carry over to
Kotlin are the ones that matter here:

- **Static-class services, no DI.** A logical service is a Kotlin `object` with
  its state `private`, the direct equivalent of the guide's `static` classes
  (`DB`, `Logger`, `ConfigHandler`). Applies to `LeiCraftApi`, `BackendGate`,
  `ClientAuth`, `AccountLinking`, `PlayerProfiles`, `AssetCache`, `TextureStore`,
  `ClientConfig`, `CpmParser`, `Notices`, `LeiCraftCommands`.
- **Namespace-for-types.** A service's types are nested inside it rather than
  scattered across the package — `CpmModel.Node`, `ApiModels.PlayerProfile`,
  `ClientAuth.State`, `ClientAuth.StoredSession`, `PlayerProfiles.Loaded`,
  `LookupBatch.Request`, `ClientConfig.Snapshot` — mirroring
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
./gradlew test                           # unit tests
./gradlew runClient                      # dev client (offline account)
./gradlew runClient -PdevUsername=Name   # dev client looking like that player
```

Needs **JDK 25** — Minecraft 26.3 requires it.

## Structure

Under `src/client/kotlin/de/leicraftmc/leicraftclient/client/`:

| Package | What lives there |
| --- | --- |
| `api` | `LeiCraftApi` (all HTTP; suspend functions on `Dispatchers.IO`), `ApiResult` (envelope → `Ok` / `Error(status, message, retryAfter)`), `ApiModels` (response types read by hand from Gson's tree), `BackendGate` (concurrency limit, per-route `429` back-off) |
| `auth` | `ClientAuth` (Mojang handshake, `config/leicraftclient-session.json`, the `State` flow), `AccountLinking` (link / unlink, polling) |
| `profiles` | `PlayerProfiles` (per-UUID cache, 250 ms batched lookups, wardrobe preview override), `LookupBatch` (pure: chunks of ≤ 100, name fallback for v3 UUIDs, answer matching), `VariantSelector` (pure), `OwnPlayer` |
| `assets` | `AssetCache` (download once, SHA-256 check, `<gameDir>/leicraftclient/cache/assets`), `AssetIntegrity` (pure) |
| `cosmetics` | `CpmParser`, `CpmModel`, `CosmeticsRenderLayer` |
| `gui` | options screen, `WardrobeScreen`, `WardrobeSelection` (pure rules), `MenuEntry` (title-screen and pause-menu button) with `MenuLayout` (pure), tab icon, `Notices` |
| `command` | `/leicraftclient` with `status`, `link`, `wardrobe` |

Rules of thumb:

- **Render-time code only reads maps.** `PlayerProfiles.profile/capeFor/cosmeticsFor`,
  `AssetCache.texture/model` and `TextureStore.identifierFor` run per player per
  frame; misses queue background work and return null. Never block or do I/O there.
- **Everything network goes through `LeiCraftApi`** and therefore through
  `BackendGate`. Client-session calls (`/v1/client/…`) get their token from
  `ClientAuth.token()` and re-run the handshake once on a `401`.
  `/v1/players/lookup` and assets are public — never send the token there (an
  invalid token on a public route answers `401`).
- **Assets are content-addressed.** An id is the SHA-256 of the bytes and doubles
  as a file name, so `AssetIntegrity.isValidId` is checked before any disk access.
  Never re-download a known id, never key a cache by URL or player name.
- **Back to the render thread** with `Minecraft.execute` or a screen's
  `screenExecutor`; screens opened from a chat command go through
  `Notices.nextTick` (the chat screen closes itself right after the command).
- **Pure logic stays Minecraft-free** (`LookupBatch`, `VariantSelector`,
  `AssetIntegrity`, `WardrobeSelection`, `ApiResult`, `ApiModels`,
  `ClientConfig.Snapshot`), so it is unit-tested on a plain JVM.

## Tests

`src/test/kotlin/...` covers the CPM transform, envelope and model parsing,
lookup batching, variant selection, asset hashing, config migration, the
wardrobe's selection rules and the session file. API fixtures in
`src/test/resources/api/` are **real responses** captured from the API-Server
(in-process, with its own test helpers); keep them real rather than hand-edited.
`src/test/resources/assets/` holds stored assets named by their SHA-256 —
`.gitattributes` keeps them byte-exact, so do not reformat them.

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
  reliably, so each mixin is a thin Java shim that calls into Kotlin objects via
  `INSTANCE`. There are three, all plain `@Inject` at `RETURN` on vanilla
  methods: `AbstractClientPlayerMixin` (cape), `PlayerTabOverlayMixin` (tab
  badge) and `MinecraftTitleMixin` (window title). Prefer a Fabric API hook over
  adding a fourth.
- **26.3 is unobfuscated.** The build uses the `net.fabricmc.fabric-loom`
  plugin (not the short `fabric-loom` id, which is the legacy remapping
  variant), declares no `mappings` dependency, and uses `implementation`
  rather than `modImplementation`.
- **Never call OpenGL.** Go through vanilla's `VertexConsumer` / `RenderType` /
  `submitCustomGeometry` (and `GuiGraphicsExtractor` in screens), so the Vulkan
  transition does not break the mod.
- **Texture identifiers.** `ClientAsset.ResourceTexture(id)` rewrites the path to
  `textures/<id>.png`; pass both components when the texture is one we registered
  ourselves.
- **26.3 GUI names.** Screens draw in `extractRenderState` / `extractBackground`
  with a `GuiGraphicsExtractor`, and screens are switched with
  `minecraft.gui.setScreen(...)`.
- **No `/*` in KDoc.** Kotlin comments nest, so a path like `` `/v1/client/*` ``
  inside a doc comment opens a nested comment and breaks the build.
- **Offline accounts** (dev runs, cracked launchers) have version 3 UUIDs and
  cannot sign in; `ClientAuth` reports them as `State.Offline`. Test linking and
  the wardrobe with a real account.
