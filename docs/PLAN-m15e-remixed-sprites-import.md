# PLAN: M15e — remixed-dungeon sprites 资产导入

## Goal
把 `../remixed-dungeon/sprites/` 下的 ~498 个 PNG 搬进 SPD fork 的 `assets/mods/remixed_full/sprites/`,建立从 remixed sprite 文件名到 SPD mod 资产路径的映射,并调研/文档化 SPD 的 sprite 加载机制,为后续脚本改写提供资产基础。**纯资产/文档 feature**,不改 Java(除非发现需要极小加载适配)。**与 M15a/b/c/d 零文件冲突**。

## Context(Explore 2026-07-09 核实)
- remixed-dungeon sprites 目录:`../remixed-dungeon/sprites/`,498 PNG,命名如 `mob_Spinner.png`、`item_HookedDagger.png`、`spell_LightningBolt.png`、`buff_Necrotism.png`。
- SPD test_mod:没有 sprites 子目录;LuaItem 用 `image = 0` 引用 vanilla items.png spritesheet 的帧索引。
- SPD 中未搜到 `TextureAtlas`/`sprites.pack`/`TexturePacker` 字样;很可能直接用 `TextureCache`/ `FilmStrip` 按 `items.png`、`mobs.png` 等 spritesheet 加载。
- remixed 是**独立 PNG 文件**(非 spritesheet),与 SPD 的 spritesheet 帧索引机制不同。不能直接让 `image = 0` 指向 remixed PNG。

**设计决策**:
- **本 feature 只做资产搬运 + 命名映射 + 加载路径调研**,不解决"独立 PNG 如何被 LuaItem.image 引用"的 runtime 问题(那是脚本改写/平台扩展的后续工作)。
- 目标目录:`core/src/main/assets/mods/remixed_full/sprites/`。子目录按类型组织:`sprites/items/`、`sprites/mobs/`、`sprites/spells/`、`sprites/buffs/`、`sprites/objects/`、`sprites/hero/` 等(从文件名前缀自动分类)。
- 生成一个**映射文件**:`assets/mods/remixed_full/sprites/SPRITE-MAP.md`(或 JSON),列出原始 remixed 文件名 → 新路径,供后续脚本改写参考。
- **加载机制调研**:读 `TextureCache.java`、`FilmStrip.java`、`ItemSprite.java`、`MobSprite.java`、`LuaItem.image` 如何解析。给出结论:要让 remixed 独立 PNG 显示,需要哪种 Java 加载适配(例如新增 `ModSpriteCache` 按文件名加载,或改用 libgdx TextureAtlas pack)。
- **不提交实际 498 个 PNG?** 取决于仓库大小策略。本 fork 已有 assets,498 PNG 约 ~2-5MB(小图标),可 commit。若用户想保持仓库小,可只提交一个 subset(代表性 item/mob/spell/buff 各 5-10 个)。本 PLAN 假设**全量导入**,worker 如觉太大可 [BLOCKED] 请示。

## Files (worker-verified)
- **`core/src/main/assets/mods/remixed_full/sprites/{items,mobs,spells,buffs,objects,hero,levelObjects,...}/`**:搬运的 PNG 文件(按文件名前缀分类)。
- **`core/src/main/assets/mods/remixed_full/sprites/SPRITE-MAP.md`**:原始文件名 → 新路径的 markdown 表。
- **`docs/MOD-SPRITES.md`**(新):SPD sprite 加载机制调研结论 + 如何让 remixed 独立 PNG 在 runtime 显示的选项(A/B/C)。
- (可选)`tools/import-remixed-sprites.sh`:一个 shell 脚本,按前缀自动分类搬运(本 feature 做一次性的;脚本可 commit 也可不 commit)。
- (可选,若需要)`core/.../modding/ModSpriteCache.java`:最小 Java 适配让 LuaItem 的 `image` 字段能按文件名引用 mod sprite。但本 feature 建议**先调研后决定是否实施**;若实施,范围要窄。

### 显式延后
- **runtime 显示适配**:独立 PNG vs SPD spritesheet 机制不匹配,让 LuaItem.image 引用 remixed PNG 需要 Java 改动(新增 sprite cache / TextureAtlas pack)。本 feature 调研 + 文档,不强制实现。
- **脚本改写**:把 remixed 脚本里的 `image = 0` / `imageFile = ...` 改为引用新 sprite 路径。后续 milestone(内容移植)。
- **TextureAtlas pack**:若调研结论是用 atlas,pack 脚本/gradle task 后续做。

## Steps
1. 调研 SPD sprite 加载机制:读 `TextureCache.java`、`FilmStrip.java`、`ItemSprite.java`、`MobSprite.java`、`LuaItem`/`LuaMob` 的 image 字段处理(`LuaEngine` 的 register_item)。
2. 在 worktree 里把 `../remixed-dungeon/sprites/*.png` 按前缀分类复制到 `assets/mods/remixed_full/sprites/`。
3. 生成 `SPRITE-MAP.md`。
4. 写 `docs/MOD-SPRITES.md`:调研结论 + 推荐显示方案(例如:选项 A 给每个 LuaItem/LuaMob 加 `spriteFile` 字段 + ModSpriteCache;选项 B 用 libgdx TexturePacker 把独立 PNG 打成 per-mod atlas;选项 C 复用 SPD spritesheet 帧索引,只抽部分 remixed 图标合并进去)。
5. (可选)若调研显示只需极小的 Java 适配即可让 `LuaItem` 支持 `imageFile` 字符串字段,worker 可顺手实现(需新增测试);否则只文档化,留后续。
6. `:core:test` 全绿(assets 改动不影响测试,除非加了 Java 适配)。
7. codex 评审(assign 优先,失败上报)。

## Acceptance
- [ ] `assets/mods/remixed_full/sprites/` 下有按类型分类的 remixed PNG
- [ ] `SPRITE-MAP.md` 存在,映射完整
- [ ] `docs/MOD-SPRITES.md` 记录 SPD sprite 加载机制 + remixed PNG 显示方案
- [ ] 若不加 Java 适配,本 feature 纯资产 + 文档
- [ ] `:core:test` 全绿
- [ ] 与 M15a/b/c/d 零文件冲突

## 注意
- 绝不 `git add -A`;`.claude/` 不进 commit
- **新 codex 政策**:assign codex_reviewer,失败上报 dispatcher
- 本 feature 主要是**资产搬运 + 调研文档**,不强求 runtime 显示(机制不匹配)
- 若 498 PNG 全量提交导致仓库过大,worker 可 [BLOCKED] 请示 subset 策略
- 与 M15a/b/c/d(代码 hook)零重叠
