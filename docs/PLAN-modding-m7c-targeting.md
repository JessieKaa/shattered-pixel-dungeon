# PLAN: M7c — spell targeting UI(selectCell + onUseAt)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M7(M7c 条目)+ §10.3 评估依据 #4
> 前置:M6d spell 已合 master(`9a802f91e`,8 代表 spell 全 self 占位)
> 并行:M7a(combat)/ M7e(talent)同时开发。**本 feature 改 LuaSpell.execute() 的 targeting 前置,M7d mana 改消耗逻辑(M7c 合后做),约定字段不撞**
> D5' = (a) 禁 luajava(只传 heroId/cellId)

## Goal

补完 M6d spell targeting 降级:LuaSpell 支持 `selectCell` 真实目标选择,新增 `onUseAt(heroId, cellId)` 回调。spell 可声明 `targeting="self"|"cell"|"enemy"`,覆盖传送/闪电/召唤类 spell 的真实目标需求。**只改 execute() 的"决定 target"前置,不碰消耗逻辑**(留 M7d)。

## Context

### 评估结论(§10.3 #4,worker 自包含)

M6d 的 8 spell cast 全 self 占位(`LuaSpell.execute` L99-115 直接 `callOpt("onUse", hero.id())`),无真实目标选择。评估发现:

- **Wand 有成熟 selectCell 模板可复用**:`Wand.java:101,124` `usesTargeting=true` + `GameScene.selectCell(targeter)` + `targetingPos` 是 SPD 标准模式
- LuaSpell 已有 `targeting` 字段(L85,M6d 仅元数据保存,只接 self)
- **风险点**:`onUse(heroId)` 签名要扩到 `onUseAt(heroId, cellId)`,向后兼容(cell 缺省传 nil,旧 spell 不动)
- 工时 2-3 天含 4 种 targeting 模式测试

### 关键 file:line

- `core/.../modding/LuaSpell.java:43,56,80-86,99-115`(execute 改造点 + targeting 字段 L85)
- `core/.../items/wands/Wand.java:101,124`(selectCell + usesTargeting 模板)
- `core/.../scenes/GameScene.java`(selectCell public 入口)

## Files(worker 阶段 1 核对,均准确)

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaSpell.java`:
  - L57 `targeting` 字段(已存在);L85 hydrate(加 3 值白名单校验,坏值→self)
  - L99-115 `execute()`(改造成 targeting 分支)
  - **新增** `applySelf(Hero)` / `applyAtCell(Hero, int)` 两个 private 方法(消耗段,留 M7d)
  - **新增** `targetListener(Hero)` 返回 `CellSelector.Listener`(targeting 段)
  - 新增 imports:`Actor`、`Char`、`CellSelector`、`GameScene`、`GLog`(`com.shatteredpixel.shatteredpixeldungeon.utils.GLog`)
- `core/src/main/assets/mods/test_mod/scripts/spells/`:
  - `lightning_bolt.lua`(已是 cell,`onUse`→`onUseAt`,射线终点用真实 cell 而非 from+3)
  - `town_portal.lua`(self→cell,语义改为「传送术」:teleportChar(hero,cell),见 Steps 决策)
  - `summon_beast.lua` / `raise_dead.lua`(self→cell,spawnMobNear 改用选中 cell)
  - `charm.lua`(char→enemy,onUseAt 对 charAtCell(cell) 施 Vertigo;真 Charm buff 待 M7a)
  - `heal.lua` / `haste.lua` / `sprout.lua`(**不动**,保持 self + onUse)
  - `test_spell.lua`(**不动**)
- `core/src/test/java/.../modding/LuaSpellTest.java`:**扩展现有类**(复用 headless harness + ModTestSupport),新增 targeting hydrate + onUseAt 回调 + 坏值回退测试;**更新** `m6dSpellMetadataHydrates`(town_portal self→cell)
- `RpdApi.java`:**不改**(charAtCell/cellRay/zapEffect/teleportChar/spawnMobNear/affectBuff/charPos 全存在)
- C3:`ModToggleRegressionTest.disabled_mod_loadsZeroLuaContent`(**不改,跑通即可**)

## Steps(worker 阶段 1 细化到可执行)

### 1. targeting 字段校验 + usesTargeting(LuaSpell.hydrate L85)

```java
String t = tbl.get("targeting").optjstring("self");
targeting = (t.equals("self") || t.equals("cell") || t.equals("enemy")) ? t : "self";
usesTargeting = "cell".equals(targeting) || "enemy".equals(targeting);
```

坏值(含 charm.lua 旧值 `"char"`)→ self。`usesTargeting`(Item 公有字段 L74)对齐 wand 模板:quickslot 第二次点击走 `autoAim`→`item.targetingPos`→`GameScene.handleCell`→我的 listener(QuickSlotButton L89-105),否则 quickslot 每次都重新弹 selector、无自动瞄准记忆。

### 2. execute() 分支 + 消耗段提取(只决定 target,消耗逻辑独立成方法给 M7d)

**targeting 段(M7c 本体)** —— `execute()`:

```java
@Override
public void execute(Hero hero, String action) {
    super.execute(hero, action);
    if (action.equals(AC_USE)) {
        if ("cell".equals(targeting) || "enemy".equals(targeting)) {
            // 不要 hero.busy()!Hero.busy() 置 ready=false,而 GameScene.selectCell
            // 设 cellSelector.enabled = Dungeon.hero.ready → selector 一装上就禁用,
            // 点击落进 GameScene.cancel() 分支,applyAtCell 永不触发(codex must-fix,
            // 对照 Wand.execute L116-127:selectCell 前不 busy)。busy/spend 留在
            // applyAtCell() 里(onSelect 拿到有效目标后再消耗)。
            GameScene.selectCell(targetListener(hero));
        } else {
            applySelf(hero);
        }
    }
}
```

新增 `targetingPos` 覆盖(pass-through,让 quickslot autoAim 瞄准真实点选格而非 Ballistica 落点):

```java
@Override
public int targetingPos(Hero user, int dst) {
    return ("cell".equals(targeting) || "enemy".equals(targeting)) ? dst : super.targetingPos(user, dst);
}
```

`targetListener(hero)` 返回匿名 `CellSelector.Listener`:
- `onSelect(Integer cell)`:
  - `cell == null` → cancel(CellSelector.cancel() 发 `onSelect(null)`),**不消耗**,直接 return
  - `Char ch = Actor.findChar(cell);`
  - `targeting=="enemy"` → 若 `ch==null || ch.alignment != Char.Alignment.ENEMY` → `GLog.w("Select an enemy")` + return(**不消耗**);否则 `QuickSlotButton.target(ch)`(刷新 quickslot lastTarget,防下次 auto-aim 打旧目标 —— 对照 Wand L712-714)
  - `targeting=="cell"` 且 `ch != null` → `QuickSlotButton.target(ch)`(cell 上有 char 才记,空格不记)
  - `applyAtCell(hero, cell)`
- `prompt()`:返回字面量 `"Select a target"` / `"Select an enemy"`(**不用 Messages.get** —— modding 子包跨包 i18n 不可靠,与 RpdApi 硬编码日志串一致)
- 新增 import:`com.shatteredpixel.shatteredpixeldungeon.ui.QuickSlotButton`

**消耗段(M7c 实现,M7d 将改 useMode)** —— `applySelf` / `applyAtCell`:

```java
private void applySelf(Hero hero) {           // self:立即消耗
    detach(hero.belongings.backpack);
    LuaTable tbl = luaTable();
    if (tbl != null) LuaItemCallbacks.callOpt(tbl, "onUse", LuaValue.valueOf(hero.id()));
    hero.spend(castTime);
    hero.busy();
}
private void applyAtCell(Hero hero, int cell) { // cell/enemy:选中后才消耗
    detach(hero.belongings.backpack);
    LuaTable tbl = luaTable();
    if (tbl != null) LuaItemCallbacks.callOpt(tbl, "onUseAt",
            LuaValue.valueOf(hero.id()), LuaValue.valueOf(cell));
    hero.spend(castTime);
    hero.busy();
}
```

两个方法对称、各 ~6 行,不强行抽象(plan 约束:三行相似优于过早抽象)。**分离的好处**:M7d 把 detach 换成 mana 消耗时,边界清晰(见末尾 M7d 协作约定)。

### 3. onUseAt 回调契约

- `callOpt(tbl, "onUseAt", heroId, cellId)` —— callOpt 是 varargs(`LuaValue...`),2 参天然支持
- Lua 端 `onUseAt(hero, cell)` 用 M6d 既有 RPD helper;`onUse(hero)` 仍工作(self spell 向后兼容)
- **不开 luajava**:只传 int heroId/cellId(D5'(a))

### 4. 代表 spell 迁移(具体 Lua)

- **lightning_bolt**(cell):`onUse`→`onUseAt(heroId, cell)`,射线 `RPD.cellRay(from, cell)`(真实终点),zapEffect + 遍历 damageChar。删旧 `onUse`
- **town_portal**(self→cell,**语义变更**,决策见下):`onUseAt(heroId, cell)` → `RPD.teleportChar(heroId, cell)`;name "回城术"→"传送术",desc 同步。**决策**:PLAN 给了 `teleportChar(hero,cell)` 或 `enterTown` 二选一。「回城」本质 self-cast(enterTown 不吃 cell),无法演示 cell targeting;改成 Blink 式「传送到选定格子」才真正用上 cell,满足 Goal「传送类真实目标」。回城语义若需要,后续 spell 自行用 self+enterTown
- **summon_beast / raise_dead**(self→cell):`onUseAt(heroId, cell)` → `RPD.spawnMobNear("test_mob", cell)`(原来传 charPos(heroId))
- **charm**(char→enemy):`onUseAt(heroId, cell)` → `target=RPD.charAtCell(cell); if target then RPD.affectBuff(target, "Vertigo", 5) end`。注:真 Charm buff 依赖 M7a 白名单(未合),暂用 Vertigo 近似(M6d 既有),M7a 合后换
- **heal/haste/sprout**:不动(self + onUse)

### 5. 测试(扩 LuaSpellTest,headless harness 已就绪)

新增:
- `targetingHydratesSelfCellEnemy`:inline 注册 3 spell(targeting=self/cell/enemy),assert `spell.targeting()`
- `targetingBadValueFallsBackToSelf`:`targeting="char"`/`"garbage"` → "self"
- `onUseAtCallbackFiresViaCallOpt`:inline `onUseAt=function(h,c) _flag=true; _h=h; _c=c end`,callOpt 2 参,assert 全部捕获
- (可选)`migratedSpellsHaveCorrectTargeting`:assert lightning_bolt=="cell" / charm=="enemy" / town_portal=="cell" / heal=="self"

更新:`m6dSpellMetadataHydrates` 里 town_portal 期望 "self"→"cell"。

**不可 headless 测的部分**(沿用 LuaSpellTest L46-49 既有 precedent):`execute()→selectCell` 全路径 + cancel-不消耗 + enemy 拒绝非 mob —— `GameScene.cellSelector` 无真实 scene 时为 null,由 **code review + 桌面 run** 验证,测试注释标注。

### 6. C3 回归

跑 `ModToggleRegressionTest.disabled_mod_loadsZeroLuaContent`(test_mod disabled 时 8 spell 全不注册)。**不改该测试**,确认通过即可。

### 7. 回填 M7d 协作约定(实施后填具体行号)

消耗段 = `applySelf` / `applyAtCell`( detach/spend/callOpt );targeting 段 = `execute` 分支 + `targetListener`。

## Acceptance

- [ ] LuaSpell 支持 targeting=self/cell/enemy,hydrate 正确
- [ ] cell/enemy spell 走 selectCell,onUseAt(heroId,cellId) 触发;cancel 不消耗
- [ ] self spell 向后兼容(onUse(heroId) 立即消耗,旧脚本不动)
- [ ] 4+ 代表 spell 迁移到真实 targeting(lightning_bolt/town_portal/summon_beast/charm)
- [ ] 不开 luajava,只传 heroId/cellId
- [ ] `:core:test` 通过,既有 273 tests 不回归;C3 守住
- [ ] 回填 M7d 协作约定(execute 消耗段留给 mana useMode)

## M7d 协作约定(实施后回填,已填)

- **targeting 段(M7c 本体,勿改)**:
  - `execute()` 分支 L112-128(cell/enemy → selectCell,self → applySelf)
  - `targetingPos()` L131-136(pass-through for cell/enemy)
  - `targetListener(Hero)` L138-161(cancel/enemy 过滤/QuickSlotButton.target → applyAtCell)
- **消耗段(M7c 占位,M7d 改 useMode)**:
  - `applySelf(Hero)` L164-172:self 路径 detach + callOpt onUse + spend + busy
  - `applyAtCell(Hero, int cell)` L175-184:cell/enemy 路径 detach + callOpt onUseAt(heroId, cellId) + spend + busy
  - **M7d 改造点**:把这两个方法里的 `detach(hero.belongings.backpack)` 换成 mana useMode 消耗(按 spellCost / useMode 字段)。targeting 分支 + callOpt 回调名(onUse/onUseAt)**不变**
- **共享字段**:
  - `targeting`(L63,hydrate L95-96,M7c):M7d 不碰
  - `usesTargeting`(Item 公有字段,hydrate L97,M7c):M7d 不碰
  - M7c **未新增任何字段**;M7d 预计新增 `useMode`/`spellCost` 消耗逻辑(spellCost 字段 M6d 已有,L97 附近),与 targeting 字段不撞
- **回调用名约定**:self → `onUse(heroId)`;cell/enemy → `onUseAt(heroId, cellId)`。M7d 若加 useMode 失败回调(如 mana 不足),用新名(如 `onCastFail`),勿改 onUse/onUseAt 语义
- **测试协作**:M7d 改 applySelf/applyAtCell 后,既有 `onUseCallbackFiresViaCallOpt` / `onUseAtCallbackFiresViaCallOpt`(callOpt 契约测试)仍须通过 —— 它们直接测 callOpt,绕过 execute,不受 useMode 影响

## Risks

- selectCell 是异步回调(UI),测试需 headless 模拟(CellSelector 注入或 mock);若 headless 无法测,标注手动验证
- enemy targeting 的敌对判定(`Actor.findChar` + faction)需对齐 SPD 既有逻辑
- 守 fork 约束:LuaSpell 改动在 modding 子包(C2);GameScene.selectCell 是 public 上游入口,直接复用不侵入
- M7c/M7d 都改 execute():本 feature 只碰 target 决定分支,M7d 碰消耗/useMode 分支,合并取并集
- C5 proguard:无反射
