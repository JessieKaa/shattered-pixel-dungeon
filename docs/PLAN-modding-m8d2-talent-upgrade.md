# PLAN: M8d2 — onTalentUpgraded Lua 回调(D6(b) 核心价值)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M8(M8d2 条目)+ §11 D6(b) 评估
> 前置:M8d1 已合(D6(b) MVP:新 `MOD_` enum + `LuaTalentRegistry.injectClassTalents` + Bundle 兼容);master `608e6fc79`,392 tests
> 本 feature 是 **D6(b) 核心价值**:Lua 注册的新天赋升级时触发 `on_upgrade` 回调(送物品/注 Buff)
> 串行:**M8d3 tier3/4 注入等 M8d2 合后做**(都改 Talent/LuaTalentRegistry/LuaEngine,串行避免复杂 resolve)

## Goal

`Talent.onTalentUpgraded` L590 末尾加 Lua dispatch + RpdApi `giveItem`/`addBuff` 原子 API。Lua `register_talent{..., on_upgrade=function(hero, points) ... end}` 的新天赋升级时触发回调,Lua 可送物品/注 Buff。**复杂互锁**(IRON_WILL/SPIRIT_FORM updateHT)保留 Java 硬编码。

## Context

### D6(b) 评估结论(§11,worker 自包含)

- `onTalentUpgraded` L590 末尾加 `LuaTalentRegistry.dispatchTalentUpgraded(hero, talent, points)`
- **可 Lua 化**:送物品(`hero.belongings.collect`)+ 注 Buff(`Buff.affect`)
- **不能 Lua 化**:复杂互锁(IRON_WILL 非 Warrior 注 WarriorShield、SPIRIT_FORM 触发 updateHT)—— 涉及内部状态机,保留 Java 硬编码
- Lua `on_upgrade` 只暴露**送物品 + 注 Buff** 两类原子 API
- M8d1 预留:on_upgrade 字段接收但未激活

### 关键 file:line(评估实测 + M8d1,worker Phase 1 已核对)

- `core/.../actors/hero/Talent.java:519-601`(`onTalentUpgraded`,签名 `(Hero hero, Talent talent)` **无 points 参数**;末尾 SPIRIT_FORM 块在 L598-600,L601 是方法闭合 `}` → Lua dispatch 单点 hook 插在 L600 之后、L601 之前)
  - `points` 来源:`hero.pointsInTalent(talent)`(`Hero.upgradeTalent` L533-540 先 `tier.put(+1)` 再调 `onTalentUpgraded`,故取到的是升级后点数)
- `core/.../actors/hero/Talent.java:211`(`MOD_EXAMPLE_TALENT(219, 2), MOD_SECOND_TALENT(220, 2);` 两个预声明 enum 槽,M8d1 已加)
- `core/.../actors/hero/Hero.java:533-540`(`upgradeTalent`,触发 onTalentUpgraded,不动)
- `core/.../modding/LuaTalentRegistry.java`(M8d1,加 dispatchTalentUpgraded + onUpgrade 字段)
- `core/.../modding/RpdApi.java`:**giveItem / affectBuff 已存在,本 feature 不新增 API**
  - `RpdApi.java:1337` `GiveItem extends ThreeArgFunction` — `RPD.giveItem(charId, itemId, qty)`,M6d,用 `LuaItemRegistry.createItem` + `collect(hero.belongings.backpack)`,返 bool,带 per-depth quota
  - `RpdApi.java:252` `AffectBuff extends ThreeArgFunction` — `RPD.affectBuff(charId, buffName, amount)`,M6c,Java 白名单 + Lua buff(id-resolved,Lua buff 的 amount 当 level)。**即 PLAN 原计划的 `addBuff`,直接复用**
- `core/.../modding/LuaEngine.java:752-818`(`RegisterTalentFunction`,M8d1,接收 on_upgrade 转发 LuaTalentRegistry.register)

## Files

> Phase 1 核对结论:**RpdApi giveItem/addBuff 已存在(M6d/M6c),本 feature 不新增 RpdApi 代码**。Lua `on_upgrade` 直接调 `RPD.giveItem`/`RPD.affectBuff`。实际改动面 = 4 文件(Talent / LuaTalentRegistry / LuaEngine / mod_example.lua)+ 1 测试。

- `core/src/main/java/.../actors/hero/Talent.java`:`onTalentUpgraded`(**static**,签名 `(Hero hero, Talent talent)`)L600 之后、L601 `}` 之前加:
  ```java
  // M8d2: Lua on_upgrade callback (id-only across sandbox)
  LuaTalentRegistry.dispatchTalentUpgraded(hero, talent, hero.pointsInTalent(talent));
  ```
  (单点 hook,不动既有 ~15 个 if 分支;用参数 `talent` 而非 `this`——方法是 static;`points` 取升级后点数)
- `core/src/main/java/.../modding/LuaTalentRegistry.java`:
  - `ModTalentDef` 加 `final LuaValue onUpgrade`(**存 Java `null` 表示无回调**,不用 `LuaValue.NIL`——NIL 是单例对象,`== null` 不成立,会让无回调的 mod 天赋每次升级都 NIL.call 抛异常)
  - `register` 扩 4 参:`register(Talent, int tier, HeroClass, LuaValue onUpgrade)`;`onUpgrade` 用 `isfunction()` 判定,非 function → 存 `null`(不抛)
  - `dispatchTalentUpgraded(Hero hero, Talent talent, int points)`:`ModTalentDef def = byTalent.get(talent); if (def == null || def.onUpgrade == null) return;` → `def.onUpgrade.call(valueOf(hero.id()), valueOf(points))`;catch 异常 `Gdx.app.error` log(不影响升级)
  - `clear()` 已清 `byTalent` → onUpgrade 随之清,无需额外代码
  - 性能 guard:vanilla 天赋不在 `byTalent` → 一次 HashMap 查找即返回;mod 天赋无 on_upgrade → `def.onUpgrade == null` 即返回;只有 mod+on_upgrade 才进 Lua
- `core/src/main/java/.../modding/LuaEngine.java`:`RegisterTalentFunction` 在 `LuaTalentRegistry.register(...)` 调用前提取 `LuaValue onUpgrade = tbl.get("on_upgrade");`(`istable()`/`isfunction()` 检查,非 function 存 NIL),转发 4 参 register
- `core/src/main/assets/mods/test_mod/scripts/talents/mod_example.lua`:加 `on_upgrade = function(hero, points) RPD.giveItem(hero, "rotten_organ", points) end`(用既有 test_mod item `rotten_organ`,**不用不存在的 `test_mod_item`**;`hero` 实参是 int heroId)
- `core/src/test/java/.../modding/LuaTalentRegistryTest.java`:扩 on_upgrade dispatch + giveItem/affectBuff 集成(复用 RpdApiItemSpellTest 的 `newHero()` + `Actor.add` + `Dungeon.hero` 模式)

## Steps

1. **LuaTalentRegistry onUpgrade + dispatchTalentUpgraded**:`ModTalentDef` 加 `LuaValue onUpgrade` 字段(Java `null` 表示无回调);`register` 扩 4 参(`isfunction()` 判定,非 function → `null`);`dispatchTalentUpgraded` guard(`def == null || def.onUpgrade == null` → return),`fn.call(valueOf(hero.id()), valueOf(points))`,catch log
2. **RegisterTalentFunction 接收 on_upgrade**:`LuaValue v = tbl.get("on_upgrade");` `isfunction()` 检查,非 function 传 `null`,转发 4 参 `LuaTalentRegistry.register`
3. **Talent.onTalentUpgraded 单点 Lua dispatch**:L600 后加 `LuaTalentRegistry.dispatchTalentUpgraded(hero, talent, hero.pointsInTalent(talent));`(用参数 `talent`,既有 if 分支不动)
4. **~~RpdApi giveItem/addBuff~~**:已存在(M6d `GiveItem` / M6c `AffectBuff`),**不新增**。Lua `on_upgrade` 直接 `RPD.giveItem(hero, itemId, qty)` / `RPD.affectBuff(hero, buffId, level)`
5. **示例 mod_example.lua on_upgrade**:`on_upgrade = function(hero, points) RPD.giveItem(hero, "rotten_organ", points) end`
6. **测试**(复用 `newHero()` = `new Hero()` + `Belongings` + `Actor.add` + `Dungeon.hero=hero`;`hero.talents` 用 `Talent.initClassTalents` 初始化):
   - on_upgrade 升级触发:register 带 on_upgrade(回调设 Lua global flag + 记 points),`Talent.initClassTalents(WARRIOR, hero.talents)` 后 `hero.upgradeTalent(MOD_EXAMPLE_TALENT)`(自动 +1 点并触发 onTalentUpgraded)→ flag 被 set、回调收到 points=1
   - giveItem 集成:on_upgrade 调 `RPD.giveItem`,升级后 `hero.belongings.backpack` 含该 item
   - addBuff 集成:on_upgrade 调 `RPD.affectBuff` 挂 Lua buff,升级后按 `RpdApiBuffTest` 模式遍历 `hero.buffs(LuaBuff.class)` 用 `sameLuaId(id)` 断言**具体 id** 命中
   - 无 on_upgrade 的 mod 天赋:dispatch noop(不抛、不调 Lua)
   - vanilla 天赋(如 IRON_STOMACH):`hero.upgradeTalent(IRON_STOMACH)` dispatch noop(byTalent 无该 key)
   - 既有 392 tests 不回归
7. **C3 回归**:test_mod disabled → registry 空 → onTalentUpgraded dispatch 是 byTalent.get→null 的 noop

## Acceptance

- [ ] `onTalentUpgraded` L600 后 Lua dispatch 单点 hook(不动既有 ~15 if 分支;用参数 `talent` 而非 `this`,`points = hero.pointsInTalent(talent)`)
- [ ] `LuaTalentRegistry.dispatchTalentUpgraded` + `ModTalentDef.onUpgrade`(Java `null` 表示无回调,guard `== null`)
- [ ] **复用** RpdApi `giveItem`(M6d)/`affectBuff`(M6c)—— 不新增 RpdApi 代码(id-based,不暴露 Java 句柄)
- [ ] 示例天赋升级触发 on_upgrade 送物品(`rotten_organ`)
- [ ] 无 on_upgrade 的天赋(vanilla + mod)dispatch noop(guard,性能:vanilla 一次 HashMap miss 即返回)
- [ ] 不开 luajava(只传 heroId:int / points:int)
- [ ] `:core:test` 通过,既有 392 tests 不回归;C3 守住

## Risks

- onTalentUpgraded 是升级热路径,dispatch 需 guard(vanilla 天赋不在 byTalent → 一次 HashMap 查找即返回,无 Lua 开销)
- giveItem/addBuff 复用 M6d/M6c 路径,id-based 已验证安全(不暴露 Java 句柄);giveItem 带 per-depth quota,防 on_upgrade 循环刷物品
- 守 fork 约束:Talent.onTalentUpgraded 单点 hook;新代码进 modding/ 子包(Talent.java 仅 1 行 hook 调用)
- M8d2/M8d3 都改 Talent/LuaTalentRegistry/LuaEngine:**本 feature 先做,M8d3 后**(串行,避免三方 resolve)
- C5 proguard:on_upgrade LuaValue 无反射,不需 keep
- **PLAN 偏差(已 Phase 1 修正)**:原 Step 4 计划新增 RpdApi giveItem/addBuff,实测两者已由 M6d/M6c 提供(`GiveItem` L1337 / `AffectBuff` L252),本 feature 直接复用,改动面从 5 文件降到 4 文件 + 测试
