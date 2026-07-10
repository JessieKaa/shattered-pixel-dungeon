# PLAN — M20b: remixed_full Lua allies 内容补全

## Goal
为 `remixed_full` mod 补全 **allies** 内容类型(当前 `scripts/allies/` 缺失)。交付 ≥2 个 remixed 风格 Lua 盟友:1 个近战护卫型,1 个功能型(治疗/辅助)。

## Context
- LuaEngine 的 M3b loader 自动扫描 `mods/remixed_full/scripts/allies/*.lua` → **只往该目录加文件即可,不碰 entry.lua**。
- `register_ally` 造 `LuaAlly`(继承 `DirectableAlly`,默认友好/智能),**不进原生 spawn 轮换**(`Level.createMob`/`MobSpawner` 不变),游戏内通过 `RPD.spawnAlly(id, pos)` 召出;`RPD.commandAlly` 可指挥。
- `RemixedFullPackTest` 计数不含 ally → **不要改它**。
- test_mod PoC:`scripts/allies/test_ally.lua`(最小:`register_ally{id=,name=,hp=,ht=,...}`)。
- RpdApi:`RPD.spawnAlly`、`RPD.commandAlly`、`RPD.healChar(charId, amt)`、`RPD.damageChar`、`RPD.charShield` 等。

## Files
- 新增 `core/src/main/assets/mods/remixed_full/scripts/allies/rf_guard_pup.lua` — 近战护卫:中等 hp/ht,`attackProc(selfId, enemyId, baseDamage)` 里对敌对 mob 加伤/特效(enemyId 由 Java 传入,无需 heroId)。
- 新增 `core/src/main/assets/mods/remixed_full/scripts/allies/rf_healing_wisp.lua` — 治疗型:`act(selfId)` 返回 `false`(保留 DirectableAlly 跟随 AI),side-effect 每 N tick `RPD.healChar(RPD.heroId(), amt)` 治疗英雄。
- 新增 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/RemixedFullAllyContentTest.java` — enable remixed_full → `LuaAllyRegistry.contains` 两个 id `assertTrue`;另断言 `RPD.heroId()` 注册存在。
- **改 `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApi.java`** — 纯新增零参 `RPD.heroId()`(决策 A):返回 `Dungeon.hero != null ? Dungeon.hero.id() : NIL`。仅新增 1 个 inner class + 1 行 `rpd.set`,不动任何现有方法 → 与其它 M20 worker 零冲突。

## Steps
1. **读 PoC + Java**:`test_mod/scripts/allies/test_ally.lua` 确认 `register_ally` 字段为 `id/name/hp/ht/attack/defense/sprite`(非 attackSkill/defenseSkill);`LuaAlly.java` 确认 `act(selfId)` 返回 true=接管 tick/返回 false=跑上游 AI;`RpdApi.LevelWidth` 抄零参 inner-class 模式。
2. **加 `RPD.heroId()`**(决策 A):在 `RpdApi.build()` 的 char-getter 区(`charPos`/`charName` 附近)`rpd.set("heroId", new HeroId())`,并在 char 类簇旁新增 `private static final class HeroId extends ZeroArgFunction`(参照 `LevelWidth`,无 hero 返 NIL,不抛 NPE)。**纯新增**。
3. 写 `rf_guard_pup.lua`:ht≈25,attack≈9,defense≈4,sprite `brute`;`attackProc` 里 `RPD.damageChar(enemyId, 2)` 做顺劈(参照 `remixed_full_bandit.lua`)。
4. 写 `rf_healing_wisp.lua`:ht≈12,attack≈3,defense≈2,sprite `bat`(脆皮后台);`act(selfId)` 每 3 tick(`tickCount` 闭包计数)`RPD.healChar(RPD.heroId(), 3)`,始终 `return false`(不接管 AI,继续跟随英雄)。
5. 写 `RemixedFullAllyContentTest.java`:enable remixed_full → `assertTrue(LuaAllyRegistry.contains("rf_guard_pup"))` / `("rf_healing_wisp")`;`assertTrue(LuaEngine` 暴露的 RPD 表含 `heroId` key(或直接 `RpdApi.build().get("heroId").isfunction()`)。
6. `./gradlew :core:test` 绿(已知 flaky: GeneratorLuaItemTest/GeneratorLuaSpellTest 概率断言,单独重跑)。重点确认 RpdApiBuffTest/RpdApiItemSpellTest/RpdApiBlobTest 不破。

## Acceptance
- [ ] `scripts/allies/` 下 ≥2 个 `.lua`,注册成功。
- [ ] `RemixedFullAllyContentTest` 通过。
- [ ] `:core:test` 全绿(flaky 重跑)。
- [ ] 未改 `entry.lua` / `RemixedFullPackTest.java`。

## Constraints(强制)
- 只在自己 worktree 内改动。
- 绝不改 `entry.lua`(M20f 独占)、`RemixedFullPackTest.java`(M20g 独占计数行)。
- 绝不 `git add -A` / commit `.claude/` / force push / reset --hard。

## 评审协议
完成 + 测试绿后,用 **`assign("codex_reviewer", ...)`** 评审(先 PLAN 再实现,两阶段)。严禁直接 codex/codex-cli。
- assign 失败或静默 hang → 跳过评审,回报时明确说明,由 dispatcher 决定是否亲审。
- 复用同一 reviewer terminal:首次 assign 创建,之后 send_message。

## 回报协议
完成后 `send_message`(无 receiver_id)回报 caller。含:`[DONE]`/`[BLOCKED]` + commit hash + reviewer terminal_id/轮数(或"assign 失败跳过") + 测试结果(标注 flaky) + 改动文件清单。

## Pending Issues(阶段 1 探索发现,2026-07-10)

### API 缺口:Lua ally 无法获取 hero 的 charId(阻塞治疗型盟友)

**事实(已核实 RpdApi.java 全部 `rpd.set` 条目):**
- RPD 没有任何 hero 访问器(无 `RPD.hero`/`RPD.heroId`/`RPD.player`)。所有按 charId 索引的函数(`healChar`/`charHP`/`charPos`/`heroMana`…)都**接收** charId 作参数,从不**产生**它。
- hero 的 charId 只能从回调签名里拿到:`spell.onUse(heroId)`、`spell.onUseAt(heroId,cell)`、`npc.onInteract(selfId,heroId)`、`mob.attackProc(selfId,enemyId,dmg)`。
- **ally 的回调签名里没有 heroId**:`act(selfId)`、`attackProc(selfId,enemyId,dmg)`(enemyId 是敌对 mob,不是 hero)、`onCommand(selfId,cmd,targetId)`(targetId 是 cell 或 enemy id,仅在脚本主动 commandAlly 时才有)。
- `RPD.enemyOf(selfId)` 用 `resolveLuaMob` 实现,**对 LuaAlly 返回 NIL**(ally 不是 LuaMob),且即使能返回也是"敌人"而非 hero。
- 不存在治疗型 blob:`BlobRegistry` 白名单全是敌对 gas(Fire/ToxicGas…)+ `Regrowth`/`SmokeScreen`。无 HealGas。
- cell 扫描路线(`charAtCell` 遍历邻格)会把敌对 mob 也治疗掉,语义错误,不可用。

**结论:** PLAN 第 4 步"healing wisp 在 `act` 里 `RPD.healChar` 附近英雄"在**不改 Java**的前提下不可实现。guard_pup(近战,只需 attackProc 的 enemyId,完全可行)不受影响。

### 需要决策(已 [BLOCKED] 上报 dispatcher) — **[RESOLVED] 2026-07-10:用户选 A**

dispatcher 回复:选 **A**,给 RpdApi.java 加零参 `RPD.heroId()`。约束:纯新增(不改现有方法,零合并冲突);无 hero 时返 NIL 不抛 NPE;沿用现有 inner-class 模式;commit message 注明决策来源。已在 Files/Steps 落地,继续阶段 2。
- **选项 A(推荐):** 给 `RpdApi.java` 加一个零参 `RPD.heroId()`(返回 `Dungeon.hero.id()`,无 hero 时 NIL),约 10 行,沿用现有 inner-class 模式。then healing wisp 的 `act` 用 `RPD.healChar(RPD.heroId(), amt)` 实现真正的治疗。代价:扩大文件清单(PLAN 原只列 2 lua + 1 test),且 RpdApi.java 是共享 Java 基建,可能与 M20 系列其它 feature 冲突。
- **选项 B(零 Java 改动,但偏离"治疗型"目标):** 把第二个盟友改成**辅助光环型**——跟随 hero(继承 DirectableAlly AI),每 tick 在自身位置放 `RPD.Blobs.Regrowth`(种草→露水/草药间接续航,nature_aura 同款)。不直接 healChar,但提供区域续航。完全用现有 API,无 hero id 需求。
- **选项 C:** 第二盟友改成**自回血坦克**(act 里 `RPD.healChar(selfId, amt)` 自愈,顶在前面),与 guard_pup 角色重叠,不推荐。

**我的建议:** 选 A。理由:M3b ally API 缺一个 hero 引用原语本身是 API 设计漏洞(任何"辅助英雄的盟友"都需要它),不是一次性 hack;改动极小且沿用既有模式;能让 PLAN 的"治疗型盟友"目标真正落地。B 的 Regrowth 不构成"治疗"。
