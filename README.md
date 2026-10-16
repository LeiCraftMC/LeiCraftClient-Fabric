# LeiCraftClient (Fabric)

Custom capes and cosmetics for LeiCraft, on Minecraft 26.3 / Fabric.

It talks to the existing cosmetics backend (`capesapi.leicraftmc.de`) and reads
the same OptiFine-style formats the V1 client used, so no server-side change and
no hosts-file redirect are needed any more — the mod calls the domain directly.

## What it does

- **Capes** — fetches `/capes/<name>.png` and patches it into the player's skin.
- **Cosmetics** — fetches `/users/<name>.cfg`, then each listed OptiFine
  "Custom Player Model" (`model.cfg`) and its texture, and renders them attached
  to the vanilla player bones.

## Design, and why

The guiding constraint was **minimum maintenance per Minecraft update**.

- **No GeckoLib.** OptiFine CPMs are static boxes — no animation — so GeckoLib's
  reason to exist goes unused, and it would add both a fast-moving dependency
  and a model conversion step. The models are parsed into plain geometry instead.
- **One mixin.** Only `AbstractClientPlayer#getSkin` is patched, to swap in the
  cape. Everything vanilla does with capes (the skin-customisation toggle,
  elytra swapping, chest-equipment offsets) keeps working untouched.
- **Cosmetics need no mixin at all.** They register through Fabric's
  `LivingEntityRenderLayerRegistrationCallback`.
- **No OpenGL.** Geometry goes through `submitCustomGeometry` and vanilla's
  `VertexConsumer`/`RenderType`, so the Vulkan transition in 26.4+ should not
  break it.
- **Cosmetics reuse vanilla bone transforms** via `ModelPart.translateAndRotate`,
  so they follow every vanilla animation for free.

## Layout

```
src/client/kotlin/.../client/
  MainClient.kt              entrypoint; registers the render layer
  TextureStore.kt            uploads downloaded PNGs on the render thread
  config/ClientConfig.kt     config/leicraftclient.json
  api/CapesApi.kt            backend HTTP
  cape/CapeManager.kt        per-player cape cache
  cosmetics/
    CpmParser.kt             model.cfg -> geometry   <- the delicate part
    CpmModel.kt              baked geometry types
    CosmeticsManager.kt      per-player cosmetics cache
    CosmeticsRenderLayer.kt  drawing
src/client/java/.../mixin/client/
  AbstractClientPlayerMixin.java   cape swap (mixins must be Java)
src/test/kotlin/...              CPM transform regression tests
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

Every key used by the models the backend currently serves is supported (all 10
parse, verified by a key census). Against the full
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
./gradlew test           # CPM transform tests
./gradlew runClient      # dev client
```

Cosmetics are keyed by player name, so to see a real profile in dev:

```sh
./gradlew runClient -PdevUsername=SomeName
```

### Note on the toolchain

26.3 ships **unobfuscated**, so this uses the `net.fabricmc.fabric-loom`
plugin (not the short `fabric-loom` id, which is the legacy remapping variant),
has **no `mappings` dependency**, and uses plain `implementation` rather than
`modImplementation`. Loom 1.19 needs Gradle ≥ 9.8.

## Configuration

`config/leicraftclient.json`, written on first run:

| key | default | meaning |
| --- | --- | --- |
| `apiBaseUrl` | `https://capesapi.leicraftmc.de` | backend root, no trailing slash |
| `capesEnabled` | `true` | apply custom capes |
| `cosmeticsEnabled` | `true` | render cosmetics |
| `cacheSeconds` | `600` | how long a fetched profile stays valid |

## CI

GitLab CI, following [docs/13](https://git.leicraftmc.de/LeiCraftMC/Style-Guides/-/blob/main/docs/13-git-and-ci.md)
of the style guide — same stage and job layout as the shared templates, with
`eclipse-temurin:25-jdk` in place of `oven/bun` and a Gradle-keyed cache.

| File | Jobs |
| --- | --- |
| `.gitlab/ci/testing.yml` | `test:compile` (Kotlin has no separate typecheck), `test:unit` (+ JUnit report) |
| `.gitlab/ci/build.yml` | `build:jar` — default branch only, jar as artifact |
| `.gitlab/ci/release.yml` | on a `v*` tag: uploads the jar to the generic package registry, then creates a GitLab Release linking it |

The jar takes its version from `CI_COMMIT_TAG` when one is present, so
`git tag v1.2.0 && git push --tags` ships `leicraftclient-1.2.0.jar`. Otherwise
`mod_version` in `gradle.properties` applies.

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
2. `AbstractClientPlayerMixin` — check `PlayerSkin`/`PlayerSkin.Patch` still
   look the same.
3. `MainClient` — Fabric renames its rendering callbacks occasionally.

`CpmParser` depends on no Minecraft types, so it should never need touching;
its tests run on a plain JVM.
