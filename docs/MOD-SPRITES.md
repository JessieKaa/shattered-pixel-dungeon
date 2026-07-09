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

## M16a: runtime `spriteFile` support

M16a implements Option A for **items, spells, and mobs**. Lua content can now
reference a standalone image file inside its own mod directory using the
`spriteFile` field.

### Supported Lua content

| Type   | Field name | Example value                          | Notes                                |
|--------|------------|----------------------------------------|--------------------------------------|
| Item   | `spriteFile` | `"sprites/items/item_HookedDagger.png"` | `LuaItem` / `LuaMaterial`            |
| Spell  | `spriteFile` | `"sprites/items/spell_Fireball.png"`    | `LuaSpell`                           |
| Mob    | `spriteFile` | `"sprites/mobs/mob_Spinner.png"`        | `LuaMob` uses `ModMobSprite`         |

`LuaNpc`, `LuaAlly`, and `LuaHeroClass` do **not** read `spriteFile` yet and
keep their existing sprite whitelist behaviour.

### Path rules

- The path is **relative to the declaring mod's own directory**.
  - Builtin mod: resolved under `assets/mods/<modId>/<spriteFile>`.
  - External mod: resolved under `mods_user/<modId>/<spriteFile>`.
- Must be a relative path; absolute paths, backslashes, and `..` segments are
  rejected.
- Only image extensions are accepted: `.png`, `.jpg`, `.jpeg`, `.webp`.
- Missing or invalid `spriteFile` falls back silently to the legacy `image` /
  `sprite` path and logs a diagnostic error.

### Declaring mod ownership

When `register_item` / `register_spell` / `register_mob` run during engine init,
`LuaEngine` writes a hidden string field `__mod_id` into the Lua table. Java
reads it back during `hydrate` as `ownerModId` and uses it to locate the owning
mod directory. This field is **not** persisted in the save Bundle; on
save/load the Lua id is restored and the definition (including `__mod_id` and
`spriteFile`) is re-hydrated from the Lua registry.

### Example item definition

```lua
register_item {
    id = "hooked_dagger",
    name = "Hooked Dagger",
    desc = "A curved blade that leaves bleeding wounds.",
    tier = 1,
    image = 0,                       -- fallback spritesheet frame
    spriteFile = "sprites/items/item_HookedDagger.png",

    attackProc = function(attacker, defender, baseDamage)
        RPD.affectBuff(defender, "Bleeding", 3)
        return baseDamage
    end,
}
```

### Example mob definition

```lua
register_mob {
    id = "test_mob",
    name = "Test Mob",
    hp = 20,
    ht = 20,
    attack = 8,
    defense = 4,
    spriteFile = "sprites/mobs/mob_TestMob.png",
}
```

### Java classes involved

- `com.shatteredpixel.shatteredpixeldungeon.modding.ModSpriteCache` — path
  validation, builtin/external resolution, and texture caching keyed by
  `modId:path`.
- `com.shatteredpixel.shatteredpixeldungeon.sprites.ModMobSprite` — minimal
  `MobSprite` subclass that renders a single PNG as one-frame idle/run/attack/die
  animations.
- `com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite.view(Item)` —
  checks `LuaItem`/`LuaSpell`/`LuaMaterial` for `spriteFile()` first, then falls
  back to the spritesheet frame.
- `com.shatteredpixel.shatteredpixeldungeon.modding.LuaEngine` — injects
  `__mod_id` during registration.
- `com.shatteredpixel.shatteredpixeldungeon.modding.LuaItem` /
  `LuaSpell` / `LuaMaterial` / `LuaMob` — parse and expose `spriteFile()` /
  `ownerModId()`.

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

1. **M16a (this milestone)** implements Option A for `LuaItem`, `LuaSpell`,
   `LuaMaterial`, and `LuaMob` using the `spriteFile` field. It is intentionally
   a static-image MVP: no animation frames, no TextureAtlas packing, no
   direction frames.
2. **M16b+ / content port** can extend the same pattern to `LuaNpc`, `LuaAlly`,
   and `LuaHeroClass`, or add animation-frame metadata if needed.
3. **Buffs/spells** may still need Option A or Option C depending on whether
   `BuffIndicator` is refactored to accept a path-based icon.

## Limitations of the M16a implementation

- `spriteFile` is **static only**: one image = one frame. Mobs get idle/run/
  attack/die mapped to the same frame.
- No support for animated frames, sprite atlases, or directional sprites.
- Only Lua-defined items/spells/materials/mobs consume `spriteFile`. Vanilla
  items and mobs are unchanged.
- Path traversal is rejected; absolute paths and backslashes are rejected.
- External mod files must be under the mod's own `mods_user/<id>/` directory.

## Files referenced

- `SPD-classes/src/main/java/com/watabou/gltextures/TextureCache.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Assets.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSpriteSheet.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/CharSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/MobSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/RatSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ModMobSprite.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModSpriteCache.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItem.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaSpell.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaMob.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaNpc.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaBuff.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java`
