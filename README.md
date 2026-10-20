# LeiCraftClient (Fabric)

Capes, cosmetics, account linking and an in-game wardrobe for the LeiCraft
Client, on Minecraft 26.3 / Fabric.

It talks to the LeiCraft Client API (`https://api.client.leicraftmc.de`, V2).
The contract is `API-Server/docs/client-integration.md`; exact schemas are in the
API's OpenAPI document at `/docs/v1/openapi`. The OptiFine-style V1 routes
(`/capes/<name>.png`, `/users/<name>.cfg`) are gone, with no compatibility layer.

## What it does

- **Capes** — every player's cape comes from their profile and is patched into
  their skin, so vanilla handles it (the cape toggle, elytra, chest offsets).
- **Cosmetics** — OptiFine *PlayerItem* models drawn on the vanilla player
  bones. A cosmetic can ship one variant per skin model; the mod draws the one
  that fits the wearer (see *Skin variants*).
- **Tab-list badge** — players who used the LeiCraft Client in the last 30 days
  (`uses_client`) get the LeiCraft mark before their name.
- **Account linking** — links the Minecraft account to a LeiCraft account on the
  website, through a clickable link (options screen or `/leicraftclient link`).
- **Wardrobe** — once linked, pick a cape and up to ten cosmetics in game, with a
  live preview, and save them as the loadout (options screen or
  `/leicraftclient wardrobe`).

### Player profiles

Players are identified by **UUID** (`GameProfile.id()`). Drawing a player is a
map read; a miss or an expired entry queues the player, and every 250 ms the
queue goes out as `POST /v1/players/lookup` batches of at most 100. Players
without a profile are omitted by the API and cached as "nothing to render".
Profiles stay valid for `cacheSeconds` (600 by default); a `429` pauses lookups
for the `Retry-After` time instead of retrying.

On offline-mode servers players get a name-based (version 3) UUID the API cannot
know, so those are looked up by name instead (the API matches the name the
account last verified with). Other UUIDs — NPCs, for instance — are not sent.

### Assets

Asset ids are the SHA-256 of the file and their URLs never change content, so
each id is downloaded once, checked against its id (a mismatch is rejected), and
kept in `<gameDir>/leicraftclient/cache/assets/<id>.<png|json>`. Within a session,
textures are uploaded once per id and models parsed once per id. A changed
texture arrives under a new id, so nothing is re-downloaded on a refresh.

### Skin variants

For each equipped cosmetic the variant drawn is: the pinned `variant_id` if set;
otherwise the first variant whose `skin_model` matches the wearer's skin
(`slim` / `wide`); otherwise the first `any` variant; otherwise the first one.
Models with `usePlayerTexture` are drawn with the wearer's skin.

### Signing in

Linking and the wardrobe need a client session. Once the game has started, the
mod signs in in the background with the game's own Minecraft session, the way a
server checks a joining player — the access token never leaves the game:

1. `POST /v1/client/auth/challenge` returns a one-time `server_id`.
2. The game calls Mojang's `joinServer(profileId, accessToken, serverId)`
   (`Minecraft.services().sessionService()`).
3. `POST /v1/client/auth/verify` returns a client token, valid 7 days, kept in
   `config/leicraftclient-session.json` and reused while it is valid for the
   same account. A `401` later on triggers one new handshake.

Offline and dev accounts can't do this; they still see everyone's capes and
cosmetics. Set `accountServicesEnabled` to `false` to skip signing in entirely.

### Linking and the wardrobe

*Link account* creates a one-time link (valid 10 minutes), opens it in the
browser (through vanilla's confirm-link screen) and also posts it in chat as a
clickable link. You log in with Discord on the website and confirm; the mod
notices within a few seconds and shows "Linked to …".

The wardrobe lists everything the linked account can equip — free listed items
and the exclusives you own (marked *Owned*) — on two tabs, *Capes* and
*Cosmetics*, with a search box and *Load more* for long lists. Click a cape to
choose it (or *No cape*); click a cosmetic to equip or remove it; the *Variant*
button pins a variant of the selected cosmetic or leaves it on *Auto*. The
preview on the left — drag it to turn — and your player in the world show the
selection right away. *Save* replaces the loadout; *Cancel* or Esc discards it.

### Commands

Client-side only:

| Command | Does |
| --- | --- |
| `/leicraftclient status` | sign-in and link state (also the bare `/leicraftclient`) |
| `/leicraftclient link` | link the account; prints the clickable link in chat |
| `/leicraftclient wardrobe` | open the wardrobe (needs a linked account) |

## Requirements

Client-side only — nothing to install on the server. Grab the jar from the
[releases](https://git.leicraftmc.de/LeiCraftMC/LeiCraftClient/Fabric/-/releases)
and drop it in `.minecraft/mods/` together with these:

| Dependency | Version | Notes |
| --- | --- | --- |
| Minecraft | `26.3` | exact version; the mod is built against it |
| Java | 25 | required by Minecraft 26.3 itself |
| [Fabric Loader](https://fabricmc.net/use/installer/) | `>= 0.19.5` | |
| [Fabric API](https://modrinth.com/mod/fabric-api) | `0.163.0+26.3` or newer | |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | `>= 1.14.2+kotlin.2.4.21` | **easy to miss** — a separate mod, not bundled |

All four are declared in `fabric.mod.json`, so Fabric Loader refuses to start
and names whatever is missing rather than crashing later. Fabric Language Kotlin
is the one people forget: it ships the Kotlin runtime plus the coroutines this
mod uses, and no other mod pulls it in for you.

Everyone who wants to *see* cosmetics needs the mod — each client fetches them
from the API itself. Players without it simply see vanilla skins, and your
own cosmetics still show up for anyone who has it.

### Other loaders

The mod is built for Fabric only. Its Fabric surface is small, though — three
plain `@Inject` mixins on vanilla methods and a handful of Fabric API hooks
(render layer, screen and tick events, client lifecycle, client commands) — so
loaders that can run Fabric mods should be able to load it. Neither of the
setups below has been tested yet.

**Quilt — should work.** Quilt Loader runs Fabric mods directly. Install these
instead of Fabric Loader:

| Dependency | Version | Notes |
| --- | --- | --- |
| [Quilt Loader](https://quiltmc.org/en/install/) | `>= 0.30.2-beta.1` | the stable `0.30.1` is too old, see below |
| [Fabric API](https://modrinth.com/mod/fabric-api) | `0.163.0+26.3` or newer | the regular one; Quilted Fabric API (QSL) was discontinued in December 2025 and has no 26.x build |
| [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) | `>= 1.14.2+kotlin.2.4.21` | |

Quilt Loader tells the mods it loads which Fabric Loader version it stands in
for, and both this mod and Fabric Language Kotlin require `>= 0.19.5`. The
latest stable Quilt Loader, `0.30.1`, reports `0.19.3` and refuses to start;
`0.30.2-beta.1` is the first that reports `0.19.5`.

**NeoForge — not yet.** Fabric mods run on NeoForge through
[Sinytra Connector](https://github.com/Sinytra/Connector), which as of October
2026 has no build for Minecraft 26.3 (the newest targets 26.2), and this mod
needs 26.3 exactly. Once Connector supports 26.3, the setup is NeoForge 26.3
with Connector,
[Forgified Fabric API](https://github.com/Sinytra/ForgifiedFabricAPI),
[Launchpad](https://github.com/Sinytra/Launchpad) and Fabric Language Kotlin.
Forgified Fabric API replaces Fabric API, so don't install both. NeoForge
patches some vanilla classes, so the mixins may still clash with it; a native
NeoForge build would be the dependable route.

**Forge — no.** Nothing runs Fabric mods on Forge for current versions;
Connector's Forge support ended with 1.20.1.

## Design, and why

The guiding constraint was **minimum maintenance per Minecraft update**.

- **No GeckoLib.** OptiFine CPMs are static boxes — no animation — so GeckoLib's
  reason to exist goes unused, and it would add both a fast-moving dependency
  and a model conversion step. The models are parsed into plain geometry instead.
- **Three small mixins.** `AbstractClientPlayer#getSkin` is patched to swap in
  the cape, so everything vanilla does with capes (the skin-customisation
  toggle, elytra swapping, chest-equipment offsets) keeps working untouched.
  The other two only rename the window (`Minecraft#createTitle`) and add the
  tab-list badge (`PlayerTabOverlay#getNameForDisplay`).
- **Cosmetics need no mixin at all.** They register through Fabric's
  `LivingEntityRenderLayerRegistrationCallback`.
- **No OpenGL.** Geometry goes through `submitCustomGeometry` and vanilla's
  `VertexConsumer`/`RenderType`, so the Vulkan transition in 26.4+ should not
  break it.
- **Cosmetics reuse vanilla bone transforms** via `ModelPart.translateAndRotate`,
  so they follow every vanilla animation for free.
- **Batched, content-addressed fetching.** One lookup request covers up to 100
  players, and an asset is downloaded once ever (then read from the disk cache),
  so a crowded server costs a few requests rather than several per player.
- **Vanilla GUI only.** The options screen and the wardrobe use vanilla widgets
  and vanilla's inventory-style player preview; no GUI library to update.

## Layout

```
src/client/kotlin/.../client/
  MainClient.kt              entrypoint; registers hooks, starts sign-in
  LeiCraftClient.kt          mod id, logger, version, background scope
  LeiCraftUsers.kt           tab-list badge (uses_client)
  TextureStore.kt            uploads PNGs on the render thread, by asset id
  config/ClientConfig.kt     config/leicraftclient.json (+ V1 migration)
  api/
    LeiCraftApi.kt           HTTP: endpoints, Bearer token, 401 -> re-sign-in
    ApiResult.kt             envelope parsing -> Ok / Error(status, message)
    ApiModels.kt             response types, read by hand from Gson's tree
    BackendGate.kt           concurrency limit, per-route 429 back-off
  auth/
    ClientAuth.kt            Mojang handshake, session file, sign-in state
    AccountLinking.kt        link / unlink, polling the link status
  profiles/
    PlayerProfiles.kt        per-UUID profile cache, batched lookups, preview
    LookupBatch.kt           queue -> lookup requests (<= 100), answer matching
    VariantSelector.kt       which cosmetic variant to draw
    OwnPlayer.kt             the local player's UUIDs, loadout -> profile
  assets/
    AssetCache.kt            download once, verify, disk + memory caches
    AssetIntegrity.kt        SHA-256 / asset id checks
  cosmetics/
    CpmParser.kt             model JSON -> geometry   <- the delicate part
    CpmModel.kt              baked geometry types
    CosmeticsRenderLayer.kt  drawing
  gui/
    LeiCraftOptionsScreen.kt options + "LeiCraft account" section
    WardrobeScreen.kt        the wardrobe (preview, tabs, search, list)
    WardrobeSelection.kt     the unsaved loadout and its rules
    AccountStatus.kt         one-line account state
    Notices.kt               chat lines, toasts, next-tick screens
    PauseMenuEntry.kt, MenuLayout.kt, TabListIcon.kt
  command/LeiCraftCommands.kt  /leicraftclient
src/client/java/.../mixin/client/
  AbstractClientPlayerMixin.java   cape swap (mixins must be Java)
  PlayerTabOverlayMixin.java       tab-list badge
  MinecraftTitleMixin.java         window title
src/test/kotlin/...              CPM transform, API parsing, batching, variants,
                                 asset hashing, config migration, wardrobe rules
src/test/resources/api/          real API responses used as fixtures
```

## The CPM coordinate transform

This is the part worth understanding before changing anything.

`model.cfg` is written in Blockbench space (Y up). Each node carries an
`invertAxis` string naming the axes to mirror to reach Minecraft model space
(Y down, Z south). Blockbench writes `"xy"` on every node, which is a 180°
turn about Z — a proper rotation, so winding and UV handedness are unaffected.

Three rules, all covered by tests:

1. `invertAxis` applies to **that node's own** numbers and is **not inherited**.
   If it compounded, nested bones (which all carry `"xy"`) would cancel out.
2. Translations and box coordinates take the sign directly; a **rotation is an
   axial vector** and takes `det(S) / s_axis` instead. For `"xy"` that is
   `(-rx, -ry, +rz)`.
3. `sizeAdd` inflates the box on every side, like vanilla's `CubeDeformation`,
   while the UV rects keep using the uninflated size.

Both `textureOffset` (standard Minecraft box unwrap) and per-face
`uvNorth`/`uvEast`/… are supported.

Faces are wound so the counter-clockwise normal points **outward**, matching
vanilla, and the layer draws with `entityCutoutCull`. Culling matters: flat
mosaic models (the diamond sword is 31 coplanar boxes) have boxes that share
face area in the same plane, and without culling both coincident faces render at
identical depth and z-fight into speckled wrong-coloured pixels. Zero-depth
boxes — common in these models — still show from both sides, because their front
and back faces are coincident with opposite winding, so exactly one faces the
camera. An odd `invertAxis` flips handedness and the winding flips back with it.

The face/UV mapping was cross-checked against the V1 `lc-client-skinview`
Three.js renderer, which was validated against real OptiFine output.

### Format coverage

Every key used by the models the V1 backend served is supported (all 10
parse, verified by a key census), and the V2 API stores the same OptiFine
*PlayerItem* format. The API strips a model-level `texture` key on upload; the
mod always uses the variant's texture. Against the full
[OptiFine part spec](https://optifine.readthedocs.io/cem_parts.html):

| Field | Status |
| --- | --- |
| `type`, `textureSize`, `texture`, `usePlayerTexture`, `models` | supported |
| `attachTo` (head, body, left/rightArm, left/rightLeg) | supported |
| `invertAxis` (any permutation, incl. `z` and `xyz`) | supported |
| `translate`, `rotate`, `scale` (number or array), `mirrorTexture` | supported |
| `boxes`: `coordinates`, `textureOffset`, `uv<Face>`, `sizeAdd`, `sizesAdd` | supported |
| `uvFront`/`uvBack`/`uvLeft`/`uvRight` aliases | supported |
| `submodel` (singular) and `submodels` | supported |
| `sprites` | **not supported** — logs a warning and skips |
| `id`, `part`, `comment`, `credit` | ignored (metadata) |

`sprites` extrude a texture region rather than wrapping a box; no served model
uses them, and the exact geometry isn't pinned down by the docs, so the parser
warns instead of guessing at something that would render wrongly.

Numbers are read leniently because OptiFine's own models write some as strings
(`"sizeAdd": "0.1"`), as does this backend (`"active": "true"`).

## Building

Needs **JDK 25** (Minecraft 26.3 requires it).

```sh
./gradlew build          # jar in build/libs
./gradlew test           # unit tests
./gradlew runClient      # dev client
```

The dev client runs an offline account (name-based UUID), whose profile is
looked up by name — so to see a real player's capes and cosmetics on yourself:

```sh
./gradlew runClient -PdevUsername=SomeName
```

Offline accounts can't sign in, so linking and the wardrobe need a real
Microsoft account (a launcher-started instance). To use a local API, set
`apiBaseUrl` to e.g. `http://localhost:12550`.

The fixtures in `src/test/resources/api/` are real responses from the
API-Server; `src/test/resources/assets/` holds two stored assets named by their
SHA-256 (kept byte-exact by `.gitattributes`).

### Note on the toolchain

26.3 ships **unobfuscated**, so this uses the `net.fabricmc.fabric-loom`
plugin (not the short `fabric-loom` id, which is the legacy remapping variant),
has **no `mappings` dependency**, and uses plain `implementation` rather than
`modImplementation`. Loom 1.19 needs Gradle ≥ 9.8.

## Configuration

`config/leicraftclient.json`, written on first run:

| key | default | meaning |
| --- | --- | --- |
| `apiBaseUrl` | `https://api.client.leicraftmc.de` | API root, no trailing slash |
| `websiteUrl` | `https://client.leicraftmc.de` | opened by the options screen's website button |
| `capesEnabled` | `true` | apply custom capes |
| `cosmeticsEnabled` | `true` | render cosmetics |
| `accountServicesEnabled` | `true` | sign in with the Minecraft session (linking, wardrobe); `false` = only read public profiles |
| `cacheSeconds` | `600` | how long a fetched profile stays valid (minimum 30) |

A config still pointing at the V1 backend (`https://capesapi.leicraftmc.de`) is
moved to the new default on load, and missing keys are written back.

Other files the mod writes:

| path | contents |
| --- | --- |
| `config/leicraftclient-session.json` | the client token (`token`, `expires_at`, `uuid`) — keep it private |
| `<gameDir>/leicraftclient/cache/assets/` | downloaded assets, `<sha256>.png` / `.json`; safe to delete |

## CI

GitLab CI, following [docs/13](https://git.leicraftmc.de/LeiCraftMC/Style-Guides/-/blob/main/docs/13-git-and-ci.md)
of the style guide — same stage and job layout as the shared templates, with
`eclipse-temurin:25-jdk` in place of `oven/bun` and a Gradle-keyed cache.

Two stages. Branches and merge requests only run the tests; **the jar is built
solely on a tag**, so releasing is the only thing that produces an artifact.

| File | Stage | Jobs |
| --- | --- | --- |
| `.gitlab/ci/testing.yml` | `test` | `test:compile` (Kotlin has no separate typecheck), `test:unit` (+ JUnit report) |
| `.gitlab/ci/release.yml` | `release` | `release:upload` builds the jar and pushes it to the generic package registry; `release:publish` creates the GitLab Release linking it |

Both release jobs are gated on `$CI_COMMIT_TAG =~ /^v\d+\.\d+\.\d+/`.

To cut a release, push a `v*` tag:

```sh
git tag v1.2.0
git push origin v1.2.0
```

`build.gradle.kts` reads the version from `CI_COMMIT_TAG`, so that ships
`leicraftclient-1.2.0.jar`; without a tag, `mod_version` in `gradle.properties`
applies. `release:upload` hands the stripped version to `release:publish`
through a dotenv artifact so both jobs address the same registry URL.

## Conventions

See [`AGENTS.md`](AGENTS.md). This repo follows the
[LeiCraftMC Style Guides](https://git.leicraftmc.de/LeiCraftMC/Style-Guides) —
services are Kotlin `object`s (the guide's static-class services), types are
nested inside the service they belong to (namespace-for-types), Conventional
Commits, AGPL-3.0, `main` as the default branch.

## Updating to a new Minecraft version

In rough order of likelihood to break:

1. `CosmeticsRenderLayer` — the render pipeline is the thing Mojang keeps
   changing. Check `SubmitNodeCollector.submitCustomGeometry`, `RenderTypes`
   and the `VertexConsumer` setters.
2. `WardrobeScreen` — GUI rendering (`GuiGraphicsExtractor`, the
   `extractRenderState` / `extractBackground` hooks) and its copy of
   `InventoryScreen.extractEntityInInventoryFollowsMouse`.
3. `AbstractClientPlayerMixin` — check `PlayerSkin`/`PlayerSkin.Patch` still
   look the same.
4. `ClientAuth` — the handshake uses `Minecraft.services().sessionService()`
   (authlib `SessionService.joinServer`) and `Minecraft.getUser()`.
5. `MainClient` — Fabric renames its rendering callbacks occasionally.

`CpmParser` depends on no Minecraft types, so it should never need touching;
its tests run on a plain JVM.
