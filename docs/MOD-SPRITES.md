# SPD Sprite Loading & Remixed PNG Display Options

This document records how Shattered Pixel Dungeon loads sprites, where the
`remixed_full` mod's single-file PNG assets fit, and what runtime work is needed
for Lua mods to actually display them.

## Current asset location

- Imported remixed sprites: `core/src/main/assets/mods/remixed_full/sprites/`
- Mapping file: `core/src/main/assets/mods/remixed_full/sprites/SPRITE-MAP.md`

## How SPD loads sprites today

### 1. Texture cache

`SPD-classes/.../gltextures/TextureCache.java` caches `SmartTexture` instances
by key and supports three source types:

- `Integer` — unsupported (legacy Android resource handles)
- `String` — loaded via `Gdx.files.internal((String)src)` as a `Pixmap`
- `Pixmap` — used directly

So a string path such as `"mods/remixed_full/sprites/items/item_HookedDagger.png"`
can already be turned into a texture by `TextureCache.get(path)`.

### 2. Items — spritesheet + frame index

- `core/.../Assets.Sprites.ITEMS = "sprites/items.png"`
- `ItemSpriteSheet.java` builds a `TextureFilm` over the 256x512 spritesheet.
- `ItemSprite` extends `MovieClip`; its constructors call
  `super(Assets.Sprites.ITEMS)` and `view(int image)` looks up
  `ItemSpriteSheet.film.get(image)`.
- `Item.image` is an `int` frame index into `ItemSpriteSheet`.
- `LuaItem` reads `image = tbl.get("image").optint(0)` and stores it in the
  inherited `Item.image` field. The value is therefore a spritesheet frame, not
  a file path.

### 3. Mobs, NPCs, allies, heroes — class-based sprite classes

- `MobSprite` / `CharSprite` subclasses set `texture(Assets.Sprites.XXX)` and
  define animations with `TextureFilm` over that spritesheet.
- `LuaMob` uses a string `sprite` field that maps to a hard-coded whitelist of
  existing `CharSprite` classes (`RatSprite`, `CrabSprite`, etc.).
- `LuaNpc`, `LuaAlly`, and `LuaHeroClass` use the same whitelist pattern.
- None of them currently accept a custom texture path.

### 4. Buffs, spells, and other icons

- Buff icons come from `Assets.Interfaces.BUFFS_SMALL` / `BUFFS_LARGE`
  spritesheets and are referenced by integer icon index in `BuffIndicator`.
- `LuaBuff` currently has no image override at all.
- `LuaSpell` extends `Item` directly (not `LuaItem`) and independently parses
  `image = tbl.get("image").optint(0)` into the inherited `Item.image` field.
  It therefore has the same frame-index semantics as `LuaItem`.

## Why the imported remixed PNGs are not directly usable yet

The remixed-dungeon source ships **one PNG per sprite** (e.g. `mob_Spinner.png`,
`item_HookedDagger.png`). SPD's rendering code is built around
**spritesheets + frame indices** for items/buffs/spells, and around
**hard-coded CharSprite classes** for mobs/NPCs/heroes. A bare PNG file in the
assets folder is reachable via `Gdx.files.internal`, but nothing in the Lua
pipeline knows how to turn a file path into an on-screen sprite.

## Options to make remixed PNGs display at runtime

### Option A — Add per-sprite file path fields (recommended for mobs/items)

Introduce a new string field that Lua can set, and a lightweight sprite class
that loads a single PNG instead of a spritesheet.

Examples:

- `LuaItem` / `LuaSpell` add `imageFile = "items/item_HookedDagger.png"`.
  When present, a new `ModItemSprite` uses `TextureCache.get(modRelativePath)`
  as its texture. The same field must be added to both classes because
  `LuaSpell` extends `Item`, not `LuaItem`.
- `LuaMob` / `LuaNpc` / `LuaAlly` add `spriteFile = "mobs/mob_Spinner.png"`.
  A new `ModMobSprite`/`ModCharSprite` extends `CharSprite`, sets the texture
  from the file, and builds a one-frame `TextureFilm`.

Pros:

- Matches remixed's existing one-PNG-per-asset structure.
- No asset build step required; artists drop PNGs into the mod folder.

Cons:

- Many small textures instead of one packed spritesheet; more texture switches
  at runtime.
- Animated mobs need a consistent frame layout or separate metadata.

Key files to touch:

- `core/.../modding/LuaItem.java` — parse `imageFile`, expose it.
- `core/.../modding/LuaEngine.java` — no validation change required, but
  `register_item` may want to allow the new field.
- `core/.../sprites/ItemSprite.java` or a new `ModItemSprite.java` — render from
  file path when `imageFile` is present.
- `core/.../sprites/CharSprite.java` / new `ModCharSprite.java` — render from
  file path for mobs/NPCs/allies/heroes.

### Option B — Pack remixed PNGs into per-mod TextureAtlases

Use libGDX `TexturePacker` at build time to generate `sprites/sprites.pack` +
`sprites/sprites.png` for each mod. Then add a `ModSpriteCache` that loads the
atlas and resolves sprites by name. Lua could still reference names like
`"item_HookedDagger"`.

Pros:

- Fewer draw calls; matches upstream's performance expectations.
- Atlas regions give named access without hard-coding frame indices.

Cons:

- Requires a build-time/tooling step (gradle task or shell script).
- Need to keep the pack process deterministic and compatible with Android asset
  packaging.

Key files/tools to add:

- `tools/pack-remixed-sprites.sh` or a Gradle task using `gdx-tools`
  `TexturePacker`.
- `core/.../modding/ModSpriteCache.java` — atlas loading + name-to-region lookup.
- Lua wrappers to consume names instead of integers.

### Option C — Merge selected remixed icons into SPD spritesheets

Pick a subset of remixed item/buff/spell icons and splice them into
`sprites/items.png`, `interfaces/buffs.png`, etc., then assign stable frame
indices and update scripts to use those indices.

Pros:

- Reuses the existing `ItemSprite`/`BuffIndicator` pipeline exactly.
- No new Java classes needed.

Cons:

- Tedious manual image editing; every upstream spritesheet update risks breaking
  the merged indices.
- Not feasible for 497 unique sprites; best for small curated subsets.

## Recommended path forward

1. **This milestone (M15e)** only imports the assets and documents the gap.
2. **Next milestone (content port / script rewrite)** pick Option A for mobs and
   items because it maps 1:1 to remixed's PNG-per-sprite format and does not add
   a build step. Implement:
   - `LuaItem.imageFile` + `LuaSpell.imageFile` + `ModItemSprite`.
   - `LuaMob.spriteFile` + `ModCharSprite`.
3. **Heroes/allies/NPCs** follow the same `ModCharSprite` pattern.
4. **Buffs/spells** may still need Option A or Option C depending on whether
   `BuffIndicator` is refactored to accept a path-based icon.

## Files referenced

- `SPD-classes/src/main/java/com/watabou/gltextures/TextureCache.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Assets.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSpriteSheet.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/CharSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/MobSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/RatSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItem.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaMob.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaNpc.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaBuff.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java`
