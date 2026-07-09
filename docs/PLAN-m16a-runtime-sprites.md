# PLAN: M16a — runtime mod spriteFile support

## Goal
让 Lua item / spell / mob 可以直接引用 mod 包内的独立 PNG 文件(例如 `spriteFile = "sprites/items/item_HookedDagger.png"`),打通 M15e 已导入 remixed sprites 的 runtime 显示链路。目标是最小可用的静态图片支持,不做复杂动画/atlas 打包。

## Context
当前 M15 已完成 Lua 内容进入主游戏的获取路径:item/spell 可掉落,mob 可刷怪,shop 可售卖 Lua item/spell。但视觉仍主要依赖 SPD 原生 spritesheet frame index 或 mob sprite class 白名单。

已核实的现状:
- `LuaItem.hydrate()` / `LuaSpell.hydrate()` 当前只读取 `image` 整数帧索引。
- `ItemSprite.view(Item)` 最终走 `ItemSpriteSheet.film.get(image)` 渲染 spritesheet 帧。
- `LuaMob.hydrate()` 当前通过 `sprite` 字符串解析到固定 `CharSprite` 类,不是文件路径。
- `ModManifest` 已有 `origin` / `baseDir`; `LuaEngine` 有当前 mod 加载上下文(`currentMod` 静态字段),可用于记录 sprite 文件归属。
- M15e 已把 remixed PNG 放到 `core/src/main/assets/mods/remixed_full/sprites/...`,但没有 runtime 消费路径。
- `TextureCache.get(String)` 已可直接把 assets 相对路径转成 `SmartTexture`。

设计原则:
- `spriteFile` 是 opt-in 字段;没有该字段时保持现有 `image` / `sprite` 行为不变。
- 内置 mod 与外部 mod 都必须可解析相对路径,且禁止路径穿越。
- 缺失/非法 sprite 不崩溃,回退现有 vanilla 图标/精灵并记录诊断日志。
- 只实现静态 PNG MVP;动画帧、TextureAtlas pack、方向帧留后续。

## Files
预计会改:
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModSpriteCache.java` (新):按 mod id + 相对路径解析/缓存 Texture 或 TextureRegion,处理 builtin/external 路径与路径校验。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItem.java`:读取并保存 `spriteFile` 及 owner mod id/baseDir 信息。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaSpell.java`:同上。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaMob.java`:读取 `spriteFile`,保留现有 `sprite` class fallback。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaEngine.java`:在 `RegisterItemFunction` / `RegisterSpellFunction` / `RegisterMobFunction` 注册时,把当前 mod id 注入到 Lua 表中(`__mod_id` 元字段,不暴露给 Lua 读取)。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ItemSprite.java`:如果 Item 是带 `spriteFile` 的 Lua item/spell,优先渲染独立 PNG;否则走原逻辑。
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/sprites/ModMobSprite.java` (新):为 LuaMob 静态 PNG 提供最小 CharSprite 实现(单帧 idle/run/attack/die)。
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/ModSpriteCacheTest.java`:路径校验、缓存、缺失 fallback。
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaItemSpriteFileTest.java` / `LuaSpellSpriteFileTest.java` / `LuaMobSpriteFileTest.java`:字段解析与保存/Bundle 恢复。
- `docs/MOD-SPRITES.md`:补 runtime 已实现字段和限制。

避免改动:
- 不改 M15e 资产映射结构。
- 不引入 TexturePacker/atlas 构建链。
- 不让 Lua 传任意绝对路径或 `../`。

## Steps
1. 实现 `ModSpriteCache`:
   - `validateSpritePath(String)` 拒绝 null、空、绝对路径、反斜杠、`..` 段、非 `.png`/`.jpg`/`.jpeg`/`.webp` 扩展名。
   - `get(ModManifest mod, String path)` → 按 `mod.id + ":" + path` 缓存 `TextureRegion`;builtin 用 `Gdx.files.internal("mods/" + mod.id + "/" + path)`,external 用 `mod.baseDir.child(path)`。
   - 失败返回 null 并 `Gdx.app.error` 记录;提供 `clear()` 测试钩子。
   - 提供 `getOrNull(String modId, String path)` 方便 ItemSprite 在丢失 manifest 时按 mod id 查询(回退 `ModRegistry.get`)。
2. `LuaEngine` 注册函数:在 `register_item`/`register_spell`/`register_mob` 的 Lua 表中写入隐藏字段 `__mod_id = currentMod.id`(string key,用下划线前缀避免与正常 Lua 字段冲突)。如果 `currentMod` 为 null(全局 init.lua),不写入,后续按无 spriteFile 处理。
3. `LuaItem` / `LuaSpell` / `LuaMob` hydrate:
   - 读取 `spriteFile` 字符串;若存在且非空,记录到实例字段 `spriteFile`。
   - 从 Lua 表读取 `__mod_id` 作为 `ownerModId`。
   - 暴露 `spriteFile()` / `ownerModId()` getter。
   - LuaItem 还需把 `spriteFile` 与 ownerModId 持久化? Bundle 恢复时从 registry 的 table 重新 hydrate,所以不必单独持久化(与现有 name/desc 逻辑一致)。
4. `ItemSprite.view(Item)` 改造:
   - 若 item 是 `LuaItem` / `LuaSpell` / `LuaMaterial`(它们都继承 Item 并可能声明 spriteFile),且 `spriteFile()` 非空,调用新的私有 `viewModSprite(item)`。
   - `viewModSprite` 调用 `ModSpriteCache.getOrNull(ownerModId, spriteFile)`;命中则 `texture(region)` + 调整尺寸并返回;未命中则回退 `view(item.image(), item.glowing())`。
   - 保持 emitter / glowing 路径不变;glowing 仍可作用于独立 PNG。
5. `LuaMob` spriteFile 路径:
   - hydrate 中若 `spriteFile` 存在,优先设置 `spriteClass = ModMobSprite.class`,并把 `spriteFile`/`ownerModId` 保存到实例字段。
   - 由于 `spriteClass` 由 Mob 在首次需要 sprite 时通过 `Reflection.newInstance` 创建,`ModMobSprite` 的无参构造函数需要能反查所属 mob 的 spriteFile/ownerModId。方案:Mob 创建 sprite 时调用 `sprite(spriteClass.newInstance())` 并把 `this` 传给它;`ModMobSprite` 强转 `ch` 为 `LuaMob` 并读取其字段。
   - 若 `spriteFile` 缺失或加载失败,回退现有 `sprite` 字符串解析的 whitelist class。
6. 给 `remixed_full` 或 `test_mod` 增加最小 spriteFile 示例:优先在 `test_mod` 的 item/spell/mob 脚本中加一个测试用声明,不影响生产资源。
7. 写测试:
   - `ModSpriteCacheTest`:路径非法、builtin 路径解析(用真实 `remixed_full/sprites/items/...` 文件)、external 路径解析(用临时目录)、missing fallback、缓存命中。
   - `LuaItemSpriteFileTest`:注册带 `spriteFile` 的 item,`LuaItem.spriteFile()` / `ownerModId()` 正确,Bundle 恢复后仍正确。
   - `LuaSpellSpriteFileTest` / `LuaMobSpriteFileTest` 类似。
8. 更新 `docs/MOD-SPRITES.md` 的实际用法与限制。
9. 运行 `./gradlew :core:test`。
10. codex 评审:必须 `assign("codex_reviewer", ...)`;如果 assign 失败或 reviewer 不可用,跳过该评审阶段并在最终回报给 dispatcher 裁决,不要直接调用 codex-cli/codex exec。

## Acceptance
- [ ] Lua item 可声明 `spriteFile` 并在 item sprite 渲染中优先显示独立 PNG。
- [ ] Lua spell 可声明 `spriteFile` 并在背包/物品图标中显示独立 PNG。
- [ ] Lua mob 至少支持静态 PNG 显示;若受 CharSprite 架构阻塞,必须明确上报并保留 item/spell 完成态。
- [ ] builtin mod 与 external mod 的 sprite 相对路径都能解析。
- [ ] 非法路径/缺失文件不崩溃,回退旧视觉路径。
- [ ] 没有 `spriteFile` 的旧 mod 行为完全不变。
- [ ] `./gradlew :core:test` 通过。
- [ ] 不提交 `.claude/`,不使用 `git add -A`。

## Notes
- 本 feature 是 M16 的关键技术风险。实现要小步保守,优先打通真实显示路径,不要顺手做 atlas/动画系统。
- M16b 可以先写内容并引用 `spriteFile`;最终合并顺序上 M16a 应先于 M16b 或由 merge 冲突统一处理。
- 关于 `__mod_id` 注入:不持久化到 Bundle,Bundle 恢复后 LuaItem/LuaSpell/LuaMob 会从 registry 的 Lua table 重新 hydrate,从而重新拿到 `__mod_id` 和 `spriteFile`。
