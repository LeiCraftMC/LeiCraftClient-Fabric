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
`uvNorth`/`uvEast`/… are supported. Boxes with zero depth are common, which is
why the layer renders with `entityCutout` (no backface culling).

The face/UV mapping was cross-checked against the V1 `lc-client-skinview`
Three.js renderer, which was validated against real OptiFine output.

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
