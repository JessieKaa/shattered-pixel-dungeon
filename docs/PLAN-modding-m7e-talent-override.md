# PLAN: M7e — 天赋数值覆写(LuaTalentOverride,D6=(a))

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M7(M7e 条目)+ §10.1 评估依据
> 前置:M6 全部已合 master(`9a802f91e`,273 tests 绿)
> 并行:M7a(combat)/ M7c(targeting)同时开发。**改 Talent/Hero/modding 新 wrapper,文件域不重叠**
> D6 = (a) 天赋数值覆写(不动 enum/Bundle/initClassTalents/onTalentUpgraded);(b)/(c) 留 M8+
> D5' = (a) 禁 luajava(只传 talent id string + int)

## Goal

开放天赋数值给 Lua 覆写(D6=(a)):新增 `modding/LuaTalentOverride` 注册表,Lua 可覆写任意天赋的 `maxPoints`/`desc`,**零 enum 改动、零 Bundle 改动、零 initClassTalents/onTalentUpgraded 改动、零 Hero.java 改动**。`pointsInTalent` 乘数因破坏升级门控/等值语义 defer M8。验证"天赋数值(maxPoints/desc)数据驱动化"最低风险档可行,为 M8+ 的 (b)/(c)/乘数探路。

## Context

### 评估结论(§10.1,worker 自包含)

D2 当初选 (b)/(c)「复用现有 4 职业天赋,不开放天赋树编辑」,刻意回避 Talent.java 数据驱动化。评估发现:

- **Talent.java 1219 行,~120 enum 项**(6 HeroClass × 4 tier + HEROIC_ENERGY + 彩蛋);"互锁"实为 `onTalentUpgraded` 升级回调(~15 if 分支)非前置/排斥图
- **581 处 `hasTalent`/`pointsInTalent` 调用**散落全仓(Char/Hero/Mob/buffs/abilities)→ 改 enum 行为的回归基线巨大
- **Remished 无 Talent.java 可抄**(NYRDS 走 @LuaInterface 反射,本 fork 禁用)
- **三档 MVP**:(a) 数值覆写 5-7 天 中风险(不动 enum);(b) 加新天赋 12-18 天 高(581 漏改);(c) Lua 职业天赋树 25-40 天 极高(save 全链路 + UI 重写)
- **决策:D6=(a)** — `LuaTalentOverride` 注册表,`Talent.maxPoints()/desc()` fallback,`Hero.pointsInTalent` 乘数;零 enum/Bundle 改动

### 关键 file:line(worker 探索后修正,2026-07-07)

- `Talent.java`:enum L96-203;`maxPoints` 字段 **L433**;`maxPoints()` **L472-474**(`return maxPoints;` 单行);`desc()`(final 无参)**L483-485** → 委托 `desc(false)`;`desc(boolean metamorphed)` **L487-495**(实际 hook 点);`onTalentUpgraded` L497-581(**不改**);`initClassTalents` L959-1140(**不改**);Bundle 序列化 **L1142-1217**(`storeTalentsInBundle`/`restoreTalentsFromBundle`,**不改**,见下 clamping 说明)
- `Hero.java`:`hasTalent` **L476-478**;`pointsInTalent` **L480-487**(遍历 `talents` LinkedHashMap 返回 int)
- `LuaHeroClass.java`:hydrate 模式参照(L66-98,checkjstring/optint/optjstring + try/IllegalArgumentException)
- `LuaEngine.java`:`initInternal` 各 `globals.set("register_*", ...)` + `loadXxxScripts()` 模式;`installGlobalsForTests` L386-397 需补 `register_talent_override`;`resetForTests` L64-66
- `ModTestSupport.resetLuaState` L49-59:需补 `LuaTalentOverride.clear()`
- `ModToggleRegressionTest`:disabled=0/enabled=N 断言(取并集)

## Files

- `core/src/main/java/.../modding/LuaTalentOverride.java`(新增):单例注册表 `Map<Talent, Override>`,Override { Integer maxPoints; String desc }(均 nullable);`get(Talent)`/`register(Talent, LuaTable)`/`clear()`/`size()`;hydrate 严格校验(id 必填+`Talent.valueOf`、`maxPoints ∈ [1,99]`、desc 为 string;坏字段独立 skip+log,合法字段仍 apply)
- `core/src/main/java/.../actors/hero/Talent.java`:`maxPoints()`(L472-474)单点 fallback `Override o=LuaTalentOverride.get(this); return (o!=null&&o.maxPoints!=null)?o.maxPoints:maxPoints;`;`desc(boolean metamorphed)`(L487-495)override-first 返回;**不改 enum/init/Bundle/onTalentUpgraded**
- ~~`Hero.pointsInTalent` 乘数~~ → **删除(defer M8,见 Scope 决议 1)**;`Hero.java` 零改动
- `core/src/main/java/.../modding/LuaEngine.java`:加 `register_talent_override` global(initInternal + installGlobalsForTests)+ `loadTalentScripts()`(扫 `mods/<id>/scripts/talents/*.lua`,per enabled mod,插在 loadBuffScripts 后);`resetForTests` drop singleton(既有)
- `core/src/main/java/.../modding/RpdApi.java`:**M7e 分块** `// M7e talent API`:只读 `talentPoints(heroId, talentId)` → raw int(无乘数;write 走 register_talent_override,不开放 setTalent 直改)
- `core/src/main/assets/mods/test_mod/scripts/talents/`(新增示例):1-2 个 override(选 HEARTY_MEAL/IRON_WILL 这类数值型,maxPoints 上调,验证 maxPoints/desc 生效)
- `core/src/test/java/.../modding/LuaTalentOverrideTest.java`(新增):override 注册/读取/maxPoints fallback/desc fallback/坏 id skip/坏 maxPoints skip 字段/clear/size;**确认 Override 不进 Bundle**(无 pointsInTalent 乘数,故不验乘数)
- `core/src/test/java/.../modding/ModToggleRegressionTest.java`:disabled → `LuaTalentOverride.size()==0`,enabled → `size()==N`(取并集)
- `core/src/test/java/.../modding/ModTestSupport.java`:`resetLuaState` 补 `LuaTalentOverride.clear()`(硬要求)

## Steps

1. **LuaTalentOverride 注册表**(见 Scope 决议:无 pointMultiplier):
   - `Map<Talent, Override>` 单例;`Override` 是 inner class(maxPoints Integer nullable、desc String nullable)
   - `register(Talent t, LuaTable tbl)`:读 `maxPoints`(int,`1<=v<=99`)/`desc`(string);坏值 log + skip 该字段,合法字段仍 apply;`get(Talent)` 返回 Override 或 null;`clear()` / `size()`
2. **register_talent_override Lua 函数**:
   - `LuaEngine` 加 `register_talent_override(table)`:必填 `id`(string,匹配 Talent enum 名),映射到 `Talent.valueOf(id)`(enum 不存在 → log + skip,不崩);其余字段可选,委托 `LuaTalentOverride.register`
   - load 时机:新增 `loadTalentScripts()` 扫 `mods/<id>/scripts/talents/*.lua`(per enabled mod,插 loadBuffScripts 后);`register` 是 idempotent upsert,脚本运行时也可随时调
3. **Talent.maxPoints()/desc() fallback**(单点 hook,见细化 §设计微调):
   - `maxPoints()`(L472):`Override o = LuaTalentOverride.get(this); return (o != null && o.maxPoints != null) ? o.maxPoints : maxPoints;`(直接读字段,无需 wrapper)
   - `desc(boolean metamorphed)`(L487):override-first `if (o != null && o.desc != null) return o.desc;` 后接原逻辑
   - **不改 enum 名/字段/Bundle 序列化**(save 安全:`Talent.valueOf` 反序列化不受影响)
4. **~~Hero.pointsInTalent 乘数~~ → 不实现(defer M8)**:见 Scope 决议 1。`Hero.java` 零改动;`onTalentUpgraded`(L497-581)零改动。maxPoints 上调已让 modder 通过更多 raw points 放大效果,是安全数值档。M8 再做 `effectivePointsInTalent()` 逐点迁移。
5. **示例脚本**:test_mod 加 1-2 个 override(选 HEARTY_MEAL/IRON_WILL 这类数值型,非送物品型),验证 maxPoints/desc 生效(无 pointMultiplier 字段)
6. **测试**:
   - LuaTalentOverrideTest:register + get + 坏 id skip + maxPoints 越界 skip 字段 + desc 类型错 skip 字段 + maxPoints fallback + desc fallback + clear + size
   - **vanilla 等价**:override 默认 null 时 maxPoints()/desc() 行为 100% 等价(581 调用点不动)
   - **save-safe 论证**:零 schema 改动;vanilla 存档(无 override)加载字节等价;Bundle clamp 用当前 maxPoints()(mod 在则一致,mod 卸则按 vanilla 上限收敛——文档化 inherent,非 schema 损坏)
7. **C3 回归**:test_mod disabled 时 LuaTalentOverride 空,所有天赋走原值;`resetLuaState` 含 `clear()`
8. **回填 PLAN 末尾 M8+ 预测**:`effectivePointsInTalent()` 迁移条目 +(b) 加新天赋/(c) Lua 职业天赋树 的 save-schema 设计建议

## Acceptance

- [ ] LuaTalentOverride 注册表(maxPoints/desc 两字段) + register_talent_override + loadTalentScripts 完成
- [ ] Talent.maxPoints()/desc() 单点 fallback(不改 enum/init/Bundle/onTalentUpgraded)
- [ ] **不实现 pointsInTalent 乘数**(defer M8);Hero.java 零改动
- [ ] 示例脚本覆写 maxPoints/desc 生效
- [ ] save-safe:零 schema 改动 + vanilla 存档字节等价加载 + Bundle clamp 用当前 maxPoints()(mod 在一致 / mod 卸按 vanilla 收敛,文档化 inherent)
- [ ] 严格 hydrate 校验:`maxPoints ∈ [1,99]`、desc string、坏 id/坏值 skip+log
- [ ] 不开 luajava(只传 talent id string + int)
- [ ] `:core:test` 通过,既有 273 tests 不回归(581 调用点等价);C3 守住(disabled → size==0)
- [ ] `LuaTalentOverride.clear()` 在 `resetLuaState` 中(硬要求)
- [ ] 回填 M8+ 预测(`effectivePointsInTalent()` 迁移 + (b)/(c) save-schema)

## M8+ 预测(回填,2026-07-07,M7e 完成后)

### 「放大」天赋效果:M7e 砍掉的乘数该怎么回来(effectivePointsInTalent 迁移)

M7e 证明 `pointsInTalent` 全局乘数 / maxPoints 上调都不安全(破坏 `[0, vanilla]` 域:`Talent.java:629` 除零、`:875/882/907` `IntRange(points,2)` min>max、41 处 `==N` capstone 门)。M8 安全放大的路径:

1. **新增 `Hero.effectivePointsInTalent(Talent)`** = `Math.round(raw * multiplier)`,**不动 `pointsInTalent()`**。raw 留作升级/等值/Bundle 用的真值,effective 只给"效果数值"出口。
2. **逐调用点迁移**:把 581 处里"纯效果数值"的调用(`1+2*pointsInTalent`、`pointsInTalent*X` 这类算术)迁到 effective;**保留**等值门(`==N`)、升级门(`pointsInTalent < maxPoints`)、`talentPointsSpent` 走 raw。581 处需逐点分类(算术 vs 等值 vs 结构),工作量 ≈ (b) 档。
3. **per-talent allowlist**:只对"纯线性放大安全"的天赋(无 IntRange/除法/==N)开放 multiplier;其余天赋 register 时拒 multiplier 字段(类比 M7e 拒 maxPoints 上调)。审计表按 codex round-2 给的 breakage 清单(`FOCUSED_MEAL` 除法、`LINGERING_MAGIC/SUCKER_PUNCH/PATIENT_STRIKE` IntRange、`RecallInscription/Sunray/IRON_STOMACH` 等值)逐个标 unsafe。
4. **maxPoints 上调**(让玩家多投点)同属 M8:必须配合 allowlist,且只对 effective 迁移完成的天赋开放(raw 点数超 vanilla 同样破坏未迁移的调用点)。

### (b) 加新天赋的 save-schema 建议

- **enum 不加项**(C4):新天赋走 `LuaTalent` 旁路数据类(类比 `LuaHeroClass`/`LuaBuff`),id 字符串解析,不进 `Talent.valueOf`。
- **Bundle**:`talents_tier_N` 现按 `Talent.name()` 存 enum 名。Lua 天赋不是 enum,需 sidecar:存 `lua_talents_tier_N` = `{id=string, points=int}`,restore 时若 id 非 enum 则走 LuaTalent 路径。**这是 schema 扩展**(新 key),不破坏 vanilla 存档(老存档无该 key → 空 Lua 天赋)。
- **互锁**:`onTalentUpgraded` 的 ~15 if 分支是升级副作用(送物品/注 Buff),新天赋的副作用走 Lua callback(`onUpgrade(heroId, talentId, newLevel)`),不动原 enum 分支。
- **tier 解锁**:沿用 `tierLevelThresholds`(L436),新天赋挂到哪 tier 由 Lua 定义,UI (`TalentsPane`) 需扩展渲染 Lua 天赋行。

### (c) Lua 职业天赋树的 tier 解锁 / per-class 设计

- **per-class 树**:复用 `LuaHeroClass.talentSource`(host HeroClass)模式,Lua 职业天赋树 = `{tier1=[lua_talent_id...], tier2=[...], ...}`,挂在 `LuaHeroClass` 上。
- **save**:`lua_class_id` sidecar(已存在)+ `lua_talent_tree` 存玩家在该树上投的点(同 (b) 的 sidecar 模式)。
- **metamorphosis**:`metamorphedTalents` 现按 enum 映射;Lua 天赋变形需额外 sidecar(Lua id ↔ Lua id),不进 `replacements` bundle。
- **UI 重写**:`TalentsPane`/`TalentButton` 硬绑 `Talent` enum,需抽象成 `TalentRef`(enum 或 lua id 二态)——这是 (c) 工期 25-40 天的大头。
- **风险**:`HeroClass` enum 不加项(C4),Lua 职业的 `hero.heroClass` 仍是 host,`lua_class_id` 是 marker——与 M3c 一致。

### onTalentUpgraded 升级副作用 Lua 化的 hook 点

- 单点 hook:`onTalentUpgraded(Hero, Talent)`(L497)末尾加 `LuaTalentCallbacks.onUpgrade(hero, talent)`,Lua 天赋(及 enum 天赋的 Lua 增强侧)在此挂 callback。
- **不搬原 if 分支**(避免 M7e 之前的 drift 教训):super-then-Lua,enum 副作用先走,Lua callback 后走(仅追加效果,不改 enum 行为)。
- 注意 `IRON_WILL`/`VETERANS_INTUITION` 等分支依赖 `pointsInTalent==N`,M8 effective 迁移时这些分支**留 raw** 判断(见上)。

### LuaTalentOverride 复用率(给 (b)/(c) 打底)

- **直接复用**:`register_talent_override` global、`loadTalentScripts` 目录扫描、`installGlobalsForTests` test 钩子、`ModTestSupport.resetLuaState` clear、`ModToggleRegressionTest` toggle 断言模式 —— (b)/(c) 的 Lua 天赋注册走同一套 plumbing。
- **数据类模式复用**:`LuaTalentOverride.Override`(nullable 字段 + 字段级校验 + 坏值 skip)是 `LuaHeroClass`/`LuaBuff` hydrate 的轻量变体,(b) 的 `LuaTalent` 数据类可照搬。
- **baseMaxPoints() 保留**:M8 allowlist 审计仍需 vanilla cap 做上界校验,M7e 加的 `baseMaxPoints()` 直接复用。
- **未复用**:maxPoints 上调 / multiplier 本身(M8 重做 effective 后才有意义)。
- **净评估**:M7e 验证了"Lua→registry→Talent 单点 fallback"全链路可行且 save-safe,(b)/(c) 的风险收敛到 enum/UI/save-schema 扩展,不再有"数值档安不安全"的未知。

## ⚠ Scope 决议(codex 评审 round 1,2026-07-07 — 收窄到真正"最低风险档")

codex `NEEDS FIX` 4 条,逐条决议:

1. **`Hero.pointsInTalent` 全局乘数 → 删除(defer M8)**。codex 指出 `pointsInTalent()` 非纯数值出口:它还门控 UI 升级(`TalentButton:119`/`TalentsPane:206` 用 `pointsInTalent < maxPoints()` 判可升级)、`upgradeTalent()` 不做 max 校验、`onTalentUpgraded` 依赖 `==N`;且 `pointMultiplier<=0` 会让 `hasTalent()` 变 false,与"hasTalent 不受影响"自相矛盾。**全局乘数 = 把 raw points 和 effective power 混进同一 API,本质不安全**。安全做法是新增 `effectivePointsInTalent()` 逐点迁移 581 调用点——这正是 (b)/(c) 已 defer 的同规模风险,**属 M8**,不属 (a)。决议:**M7e 只做 `maxPoints` + `desc`**,砍掉 `pointMultiplier` 字段与乘数 hook。maxPoints 上调本身已让 modder 放大天赋效果(更多 raw points → `pointsInTalent` 自然读高),是安全的数值档。
2. **Bundle clamp 交互 → 不改 Bundle(honor "零 Bundle 改动"),严格校验兜底**。L1152/L1208 用 `maxPoints()` 钳位是 inherent:mod 降上限截断 raw points、mod 卸载后 vanilla 上限截断超投点数——**这是"mod 赐予的进度在 mod 移除时消失"的同类行为(类比 mod 物品),非 schema 损坏**。严格校验 `maxPoints >= 1` 杜绝负点数腐蚀 `talentPointsSpent`。Acceptance 的 "save-safe" 重述为:**零 schema 改动 + vanilla 存档字节等价加载 + mod 在则一致、mod 卸则按 vanilla 上限收敛**(文档化 inherent)。
3. **hydrate 严格校验 → 接受**。`id` 缺失/`Talent.valueOf` 异常 → 整条 skip+log;`maxPoints` 必须是 int 且 `1 <= v <= 99`(否则 skip 字段+log);`desc` 必须 string;**无 pointMultiplier 字段**(见决议 1)。合法字段仍 apply,坏字段独立 skip。
4. **C3 clear → 硬要求**。`LuaTalentOverride.clear()` 必须进 `ModTestSupport.resetLuaState()`;生产 mod 开关(`WndModManager:127`)仅持久化、**重启生效**(文档化,不热卸载)。

**净改动**:Files 删 `Hero.pointsInTalent` hook;Override struct 删 `pointMultiplier`;Steps 4 改为"不实现乘数";Acceptance 删乘数条、重述 save-safe;Risks 补 clamp 行为。M8+ 回填补 `effectivePointsInTalent()` 迁移条目。

## 阶段 1 细化(探索后,2026-07-07)

### 设计微调(基于实际代码结构)

1. **`maxPoints()` 不需 `maxPointsInternal()` 包装**:现有 body 是单行 `return maxPoints;`(字段直读)。fallback 直接读字段即可,减少无谓方法:
   ```java
   public int maxPoints(){
       LuaTalentOverride o = LuaTalentOverride.get(this);
       return (o != null && o.maxPoints != null) ? o.maxPoints : maxPoints;
   }
   ```
2. **`desc()` hook 点是无参 final `desc()` 不能改,改 `desc(boolean metamorphed)`**(L487):无参 `desc()`(L483,final)委托 `desc(false)`,所有调用最终走 2-arg 形式。override-first 返回(整串替换语义):
   ```java
   public String desc(boolean metamorphed){
       LuaTalentOverride o = LuaTalentOverride.get(this);
       if (o != null && o.desc != null) return o.desc;
       // 原逻辑(metamorphed 追加 meta_desc)不变
   }
   ```
   副作用:有 override 的 metamorphosed 天赋会丢 meta_desc(v1 整串替换,tier-aware 留 M8,可接受)。
3. ~~`pointsInTalent` 乘数~~ → **删除(见 Scope 决议 1)**。M7e 不动 `Hero.pointsInTalent`,581 调用点(含 41 处 `==N` 等值判断)零回归。

### Bundle clamping 交互(重要,save 行为)

`maxPoints()` 在 Bundle 序列化两处被调(L1152 store / L1208 restore)做 `Math.min(points, maxPoints())` 钳位:
- override **数据本身不进 Bundle**(Override record 不序列化,由 mod 重注册)✓ 满足 Acceptance
- 但 override 的**钳位副作用**会落到存档:mod 把 maxPoints 调**低**时(罕见),已存的高点数会被永久截断;调**高**无影响(原值 ≤ 新 max)
- 这是 maxPoints 语义的必然结果,(a) 档可接受;记入 Risks + M8+ 回填

### 加载机制:C3 一致性

新增 `loadTalentScripts()`,镜像其他 loader 扫 `mods/<id>/scripts/talents/*.lua`(per enabled mod),插在 `loadBuffScripts()`(L128)之后。这样 **disabled mod → 0 override**(C3 守住),`ModToggleRegressionTest` 可断言 disabled=0/enabled=N。`register_talent_override` 同时作为 global 注册(`initInternal` + `installGlobalsForTests`),脚本运行时也可随时调(idempotent upsert)。

### 等值检查风险(已量化)

**41 处 `pointsInTalent(...) == N`**(Hero/RecallInscription/Sunray/Momentum/DeathMark/Shockwave/...),典型:
- `RecallInscription`:`== 2 ? 300 : 10`(时长档)
- `Sunray`:`== 2 ? 6 : 4`(伤害档)
- `IRI内皮 IRON_STOMACH`(Hero L1747):`== 1 ? damage/4f : == 2 ? 0`

默认 multiplier=null→1.0 论证成立但只能证"未启用 override 时不回归"。**M7e 不动 `pointsInTalent`,41 处 + 581 调用点全部零回归**(Scope 决议 1)。乘数会破坏等值/升级门控,defer M8 `effectivePointsInTalent()` 逐点迁移。M7e 的 maxPoints 上调是"通过更多 raw points 放大效果"的安全路径,不碰等值语义。

## Risks

- Talent.maxPoints/desc 是热点(581 调用间接依赖),fallback 必须默认 null 透传(性能:Override 查询是 HashMap O(1),可接受)
- desc override 第一版整串替换(override-first,覆盖 meta_desc);tier-aware 留 M8
- **Bundle clamp 行为**(L1152/L1208 用当前 `maxPoints()`):mod 降上限或卸载时 raw points 按当前/vanilla 上限收敛——**inherent,非 schema 损坏**(类比 mod 赐予进度随 mod 移除消失)。严格校验 `maxPoints ∈ [1,99]` 杜绝负点数腐蚀
- 守 fork 约束:Talent.java 改动是单点 fallback(maxPoints L472 / desc L487),**不动 enum/init/Bundle/onTalentUpgraded**(C4);Hero.java 零改动;新代码主体在 modding/LuaTalentOverride.java(C2)
- C5 proguard:`Talent.valueOf(id)` 反射 enum,既有 keep 已覆盖(enum 反射是 SPD 既有路径);LuaTalentOverride 无反射
- 生产 mod 开关仅持久化,**重启生效**(不热卸载);测试隔离靠 `resetLuaState` 的 `clear()`
