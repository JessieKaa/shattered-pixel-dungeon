# PLAN: M16e — roadmap 文档收尾(M15 + M16)

## Goal
更新 `docs/MODDING-ROADMAP.md`:补回落后的 M15、M16 章节,更新状态总表与变更记录,让路线图与 master 实际进度一致。

## Context
当前 master 已合并 M15(5 feature)和 M16 第一批(3 feature),但 `docs/MODDING-ROADMAP.md` 只记录到 M14(状态总表合计 "M0-M14 全完成,56 feature")。需要补:

- **M15(全 main-game 集成,5 feature)**:
  - m15a Lua item 进主游戏掉落池(`Generator.setLuaItemProbability` + `BalanceConfig.lua_item_drop_prob` max 聚合,commit `db1d8b2ee`)
  - m15b Lua mob 进主游戏刷怪池(`LuaMobFactory` 占位 + `Level.createMob` factory 分支 + `MobSpawner` prob 替换 + `BalanceConfig.lua_mob_spawn_prob`,commit `172e628dd`)
  - m15c Lua spell 获取路径(`Generator.Category.LUA_SPELL` + `LuaSpellPool` + `lua_spell_drop_prob`,commit `cff423f64`)
  - m15d Lua shop 卖任意 Lua item/spell(`LuaShopItems.create` 查 LuaItemRegistry→LuaSpellRegistry→vanilla whitelist,commit `ce7cc5b72`)
  - m15e remixed sprites 资产导入(498 PNG + SPRITE-MAP.md + MOD-SPRITES.md,commit `4c0607d5e`)
- **M16(可玩内容包 alpha,3 + 1 feature)**:
  - m16a runtime mod spriteFile(Lua item/spell/mob 独立 PNG,commit `78174c2ed`)
  - m16b remixed_full minimal playable alpha(10 item/5 spell/6 mob/1 shop/1 hub,commit `3d36f4cfe`)
  - m16c mod diagnostics UI(ModDiagnostics + WndModDetails + WndModManager status,commit `16baac444`)
  - m16d android smoke(本批并行,docs/SMOKE-M16.md,状态视回报填)

文件域:仅 `docs/MODDING-ROADMAP.md`,与 M16d 的 `docs/SMOKE-M16.md` 零冲突。

## Files
预计会改:
- `docs/MODDING-ROADMAP.md`:
  - 在 §4 里程碑 M14 之后新增 **M15** 章节(动机/范围(feature 列表 + commit)/依赖 M14/风险/预估 feature 数 5/状态 `[x]` 完成)。
  - 新增 **M16** 章节(动机:从 modding beta 推进到可玩内容包 alpha;范围 m16a/b/c + m16d smoke;依赖 M15;预估 feature 数 4;状态:写 m16a/b/c `[x]` 完成,m16d `[~]` 或 `[x]` 视回报)。
  - §6 状态总表:加 M15、M16 两行;更新合计行(M0-M16 累计 feature 数 = 56 + 5 + 4 = 65,其中 M16d 视完成情况)。
  - §8 变更记录:加两行(2026-07-09 M15 完成、M16 第一批完成)。
  - 顶部/章节状态图例无需改。

避免改动:
- 不动 PLAN-*.md(那些是各 feature 的审计记录,已随代码进 master)。
- 不改其他 docs(MOD-AUTHORING / MOD-SPRITES / SMOKE-M16)。
- 不改代码。

## Steps
1. 读 `docs/MODDING-ROADMAP.md` 全文,确认 M14 结尾位置(§4 内)与 §6/§8 结构。
2. 在 M14 章节后写 M15 章节:沿用 M12/M13/M14 的章节格式(目标/动机/范围(feature+commit)/依赖/风险/难度/预估 feature 数/状态)。
3. 写 M16 章节:m16a/b/c 标 `[x]`(commit 已给),m16d 标 `[~]` 进行中(若回报完成再改 `[x]`,由 dispatcher 收口时确认)。
4. 更新 §6 状态总表:M15 行 `[x]` feature 5;M16 行 feature 4(m16a/b/c 完成 + m16d smoke);合计行更新为 M0-M16 累计。
5. 更新 §8 变更记录:2026-07-09 两行(M15 全完成 5 feature;M16 第一批 m16a/b/c 完成 + m16d smoke 并行)。
6. 检查文档内部一致性:状态图例、feature 计数、里程碑依赖图(§5 若引用 M15/M16 则补,否则不动)。
7. (无代码改动,无需 `:core:test`;若想确认文档引用的 commit 存在,`git log --oneline | grep <short>` 抽查)
8. codex 评审:必须 `assign("codex_reviewer", ...)`;如果 assign 失败或 reviewer 不可用,跳过该评审阶段并在最终回报给 dispatcher 裁决,不要直接调用 codex-cli/codex exec。

## Acceptance
- [ ] `docs/MODDING-ROADMAP.md` 含 M15 章节(feature 列表 + commit,状态完成)。
- [ ] 含 M16 章节(m16a/b/c 完成 + m16d smoke)。
- [ ] §6 状态总表含 M15/M16 行,合计 feature 数更新一致。
- [ ] §8 变更记录含 M15/M16 两条。
- [ ] 文档内部计数/状态自洽。
- [ ] 不改代码,不改其他 docs 文件。
- [ ] 不提交 `.claude/`,不使用 `git add -A`。

## Notes
- 这是纯文档 feature,零代码风险。
- commit hash 以 PLAN Context 中给的为准;若与实际 `git log` 不符,以 `git log` 为准并在回报中说明。
- M16d 状态初始写 `[~]`,最终由 dispatcher 在 M16d 回报后收口时改定(或指示 worker 改)。
