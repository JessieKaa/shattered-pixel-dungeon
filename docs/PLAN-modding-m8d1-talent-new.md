# PLAN: M8d1 — 新天赋 enum + Bundle 兼容(D6(b) MVP)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M8(M8d1 条目)+ §11 D6(b) 评估
> 前置:M7e 已合(`LuaTalentOverride` desc+maxPoints 下调);M7 全合 master(`5d1f0fd37`,331 tests)
> 并行:M8a/b/c(降级收尾)同时跑。本 feature 改 Talent.java + LuaEngine + 新 LuaTalentRegistry,**文件域与 M8a/b/c 不重叠**(LuaEngine 独占)
> D6(b) MVP:**新 enum 项 + Bundle 兼容 + initClassTalents Lua 注入 hook**;不含 on_upgrade 回调(M8d2)

## Goal

D6(b) MVP:加新天赋 enum 项(`MOD_<ID>_<NAME>` 前缀)+ `initClassTalents` Lua 注入 hook + `restoreTalentsFromBundle` mod 未知 name 静默 skip。Lua 可注册新天赋进现有职业 tier,旧档加载不崩。验证"加新天赋"save-schema 可行,为 M8d2(on_upgrade 回调)探路。

## Context

### D6(b) 评估结论(§11,worker 自包含)

- **Bundle 存 `talent.name()` 字符串**(`storeTalentsInBundle` L1160),不是 enum 序数 → 新 enum 项**不需 schema bump**
- **旧档无新 enum**:`Talent.valueOf(tName)` 抛 `IllegalArgumentException`,被 L1221 `catch(Exception)` 吞 + `reportException`(不崩,但点数丢失)→ M8d1 加 mod 未知 name 静默 skip
- **onTalentUpgraded L590 末尾**可加 Lua dispatch(M8d2,本 feature 不做)
- **initClassTalents L1074 末尾**加 Lua 注入 hook(**保留 switch,不重构 table**)
- **581 调用点**:新天赋不被现有引用(它们硬编码 vanilla enum 名);回归基线 3 测试(旧档不崩 + 新天赋升级 + tier 配额)
- **与 M7e LuaTalentOverride 正交**:新 enum 走 `Talent.maxPoints()` fallback 同样被覆写

### 关键 file:line(评估实测)

- `core/.../actors/hero/Talent.java:97-204`(enum 定义,新 MOD_ 项加此)
- `core/.../actors/hero/Talent.java:978-1074`(initClassTalents,L1074 末尾加 Lua 注入)
- `core/.../actors/hero/Talent.java:1186-1228`(restoreTalentsFromBundle,L1215 加 mod 未知 name skip)
- `core/.../actors/hero/Talent.java:1176-1184`(removedTalents/renamedTalents 迁移口,参考)
- `core/.../modding/LuaEngine.java:705-723`(RegisterTalentOverrideFunction,M8d1 旁加 RegisterTalentFunction)
- `core/.../modding/LuaTalentOverride.java`(M7e,新 enum 复用其 maxPoints fallback)

## Files

- `core/src/main/java/.../actors/hero/Talent.java`:
  - 加 1-2 个 `MOD_EXAMPLE_TALENT` enum 项(`MOD_` 前缀隔离;icon 复用占位)
  - `initClassTalents` L1074 末尾加 `LuaTalentRegistry.injectClassTalents(cls, talents);`(单点 hook,不动 switch 体)
  - `restoreTalentsFromBundle` L1215:`Talent.valueOf` 失败时查 `LuaTalentRegistry.isKnownModTalent(name)`,已知 mod 静默 skip,真正未知才 reportException
- `core/src/main/java/.../modding/LuaTalentRegistry.java`(**新增**):`Map<id, LuaTable>` 注册表;`register(id, tbl)`/`injectClassTalents(cls, talents)`/`isKnownModTalent(name)`/`clear`;校验 id(MOD_ 前缀)/name/tier/class
- `core/src/main/java/.../modding/LuaEngine.java`:`RegisterTalentFunction`(id/name/tier/class 必填,maxPoints/desc/on_upgrade 可选;on_upgrade M8d1 接收但不激活)
- `core/src/main/assets/mods/test_mod/scripts/talents/mod_example.lua`:`register_talent{id="MOD_EXAMPLE", name="Lua Talent", tier=2, class="WARRIOR", maxPoints=2, desc="..."}`
- `core/src/test/java/.../modding/LuaTalentRegistryTest.java`(新增):注册 + 注入 tier + 旧档 skip + Bundle round-trip + 与 LuaTalentOverride 共存

## Steps

> 实现细节(worker 细化,2026-07-07)。dispatcher 的 Goal/Context 不变。

### 关键设计决策(细化)

- **mod talent = Java enum 项**(不是动态非-enum name)。tier 是 `LinkedHashMap<Talent,Integer>`,key 必须 enum;故 Lua `register_talent.id` 必须解析到一个 `MOD_` 前缀的预声明 enum 项(`Talent.valueOf(id)` 成功 + `id.startsWith("MOD_")`)。vanilla enum 名(无 MOD_ 前缀)被拒绝 —— 不能用 register_talent 把 vanilla talent 重新注入别的 tier。
- **maxPoints/desc 复用 M7e 路径**:`RegisterTalentFunction` 把整张 table 转发给 `LuaTalentOverride.register(talent, tbl)`,这样 `Talent.maxPoints()`/`desc()` 的 M7e fallback 自动覆盖 mod talent(已验证 `Talent.java:473-498` 的 fallback 不区分 vanilla/mod)。**LuaTalentRegistry 只管"哪个 enum 注入哪个 class 的哪个 tier"+ "已知 mod name 集合"**,职责单一,避免两套覆写源。
- **enum icon 占位**(codex 轮1 修正):`talent_icons.png` 是 512×128,`TalentIcon` 按 16×16 切片 → 合法 frame `0..255`;越界值(如 300)会让 `film.get(icon)` 返回 null → `frame(null)` NPE。vanilla 已用到 215-218,故新项用合法未用 frame `219`/`220`(透明占位,真机显示透明图标但不崩)。`icon()` 默认返回 `icon` 字段(L469),无需特判。
- **catch 分支语义**:`restoreTalentsFromBundle` 的 `Talent.valueOf(tName)` 对 enum name 总成功(mod talent 是 enum),故 mod 卸载场景由 L1218 `tier.containsKey(talent)` 守护(不 put,静默)——**已有行为,无需改**。L1221 catch 分支只在 bundle name 非 enum 时触发(旧版移除的 vanilla 名等):`isKnownModTalent(name)` true → 静默;false → reportException(原行为)。MVP 下 isKnownModTalent 是防御兜底,逻辑正确即符合 PLAN 意图。

### 1. LuaTalentRegistry 注册表(`core/.../modding/LuaTalentRegistry.java`,新增)

```java
public final class LuaTalentRegistry {
    static final class ModTalentDef {
        final Talent talent; final int tier; final HeroClass heroClass;
    }
    private static final Map<Talent, ModTalentDef> byTalent = new HashMap<>();
    private static final Set<String> knownNames = new HashSet<>();   // enum.name() 集合

    // 校验:tier∈[1,4];cls≠null。upsert(last wins)。knownNames.add(talent.name())。
    static void register(Talent talent, int tier, HeroClass cls);
    public static boolean isKnownModTalent(String name);  // knownNames.contains(name)
    // 遍历 byTalent:def.heroClass==cls → talents.get(def.tier-1).put(def.talent, 0)(若 absent)
    static void injectClassTalents(HeroClass cls, ArrayList<LinkedHashMap<Talent,Integer>> talents);
    public static int size();
    public static void clear();   // byTalent + knownNames
}
```

### 2. RegisterTalentFunction(`LuaEngine` 内部类,新增 + 注册 global)

```java
globals.set("register_talent", new RegisterTalentFunction());   // 紧邻 register_talent_override
private static class RegisterTalentFunction extends OneArgFunction {
    call(arg):
      tbl=checktable; id=checkjstring;
      if (!id.startsWith("MOD_")) { log+return NIL; }       // vanilla 名拒(mod 不能重注入 vanilla)
      talent = Talent.valueOf(id);  // IAE → log+return NIL
      tier = tbl.get("tier").toint();  if tier∉[1,4] → log+return NIL
      cls = HeroClass.valueOf(tbl.get("class").checkjstring());  // IAE → log+return NIL
      LuaTalentRegistry.register(talent, tier, cls);
      LuaTalentOverride.register(talent, tbl);   // 转发 desc/maxPoints(M7e 路径)
}
```

### 3. Talent.java 新 MOD_ enum 项(L204 `RATFORCEMENTS(217,4);` → 改 `;` 前加两项)

```java
RATFORCEMENTS(217, 4),
//M8d1: mod-injected talent slots (D6(b)). Lua activates via register_talent{id="MOD_EXAMPLE_TALENT",...}.
//icon 300/301 are reserved placeholder ids (no vanilla resource); MVP tests do not render icons.
MOD_EXAMPLE_TALENT(300, 2), MOD_SECOND_TALENT(301, 2);
```

### 4. initClassTalents L1074 末尾单点 hook(不动 switch 体)

```java
        //tier4
        //TBD

        //M8d1: Lua-injected mod talents fill their registered class+tier slots.
        LuaTalentRegistry.injectClassTalents(cls, talents);
    }
```
+ Talent.java import `com.shatteredpixel.shatteredpixeldungeon.modding.LuaTalentRegistry;`

### 5. restoreTalentsFromBundle L1221 catch 分支(防御兜底)

```java
} catch (Exception e) {
    //M8d1: name 非 Talent enum(旧版移除的 vanilla 名 / 损坏 save)。已知 mod id 静默 skip,
    //否则 reportException(原行为)。注:mod talent 是 enum,valueOf 成功 → 此分支仅防御。
    if (LuaTalentRegistry.isKnownModTalent(tName)) {
        // silently skip — mod removed or name not a real enum constant
    } else {
        ShatteredPixelDungeon.reportException(e);
    }
}
```

### 6. ModTestSupport.resetLuaState 加 `LuaTalentRegistry.clear();`(test_mod 的 mod_example.lua 在 LuaEngine.init 时注册,需跨测试清)

### 7. 示例脚本 `core/src/main/assets/mods/test_mod/scripts/talents/mod_example.lua`

```lua
register_talent {
    id = "MOD_EXAMPLE_TALENT",
    tier = 2,
    class = "WARRIOR",
    maxPoints = 2,
    desc = "Lua 新天赋:示例(D6(b) MVP)。",
}
```

### 8. LuaTalentRegistryTest 用例

- `luaRegister_injectsIntoClassTier`(register MOD_EXAMPLE_TALENT tier2 WARRIOR via `g.load("register_talent{...}")`) → `initClassTalents(WARRIOR, list)` 后 `list.get(1)` 含 MOD_EXAMPLE_TALENT(0 点)
- `doesNotInjectIntoOtherClass`(WARRIOR 注册,MAGE 不含)
- `nonModPrefixId_rejected`(id='HEARTY_MEAL' → size 不增)
- `unknownModId_skippedWithoutThrowing`(id='MOD_NONEXISTENT' → 不抛,size 不变)
- `badTier_rejected`(tier=5 / tier=0)
- `badClass_rejected`(class='BOGUS')
- `coexistsWithTalentOverride`(register 带 maxPoints=1 → `Talent.MOD_EXAMPLE_TALENT.maxPoints()==1`,M7e 路径)
- `bundleRoundTrip_preservesModTalent`(`new Hero()` + heroClass=WARRIOR + initClassTalents + register + inject + put points=2 → storeTalentsInBundle → `new Hero()` + heroClass=WARRIOR + restoreTalentsFromBundle → assert points==2)
- `isKnownModTalent_unit`(register 后 true,clear 后 false)

### 9. C3 回归

test_mod disabled:`LuaTalentRegistry` 空(test 不 enable test_mod / @Before clear),`injectClassTalents` noop,vanilla initClassTalents 不受影响。既有 331 tests 不回归。

## codex 评审轮1 修复(2026-07-07,worker 自审)

codex `exec` 返回 3 个 must-fix(非方向分歧),纳入本节作为实施约束:

1. **icon 越界 → 用合法 frame 219/220**(已并入"enum icon 占位"决策):原方案 300/301 会让 `TalentIcon.frame(film.get(300))` NPE。改用 219/220(透明占位)。
2. **title 无消费路径 → 扩展 LuaTalentOverride 加 title 字段**:`Talent.title()` 走 `Messages.get(...".title")`,新 MOD_ enum 无 properties key → 显示 `!!!MOD_*.title!!!`。修:
   - `LuaTalentOverride.Override` 加 `String title`(nullable);`register` 加 `parseTitle`(同 `parseDesc` 模式,LuaString 校验);新增 `getTitle(talent)`。
   - `Talent.title()` 末尾:override-first fallback(`LuaTalentOverride.getTitle(this) != null ? override : Messages.get(...)`)。`title()` 已有 HEROIC_ENERGY 特判,新 fallback 在 HEROIC_ENERGY 分支后、Messages.get 前。
   - `RegisterTalentFunction` 转发 `name` → `LuaTalentOverride`(走同一 register 调用,table 里 `name` 字段被 parseTitle 读)。注:M7e `register_talent_override` 不传 name → title=null → vanilla Messages fallback(M7e 测试不回归)。
3. **tier∈[1,4] 与 base cap 2 冲突 → MVP 限制 tier∈[1,2]**:`MOD_*(_,2)` 的 `baseMaxPoints=2`,M7e lower-only 以 baseMaxPoints 为 cap;tier3/4 注册 maxPoints=3/4 会被拒。MVP `RegisterTalentFunction` 校验 `tier∈[1,2]`(与 T1/T2 vanilla cap 2 一致)。tier3/4 需按 tier 预声明不同 base cap slot 或改 cap 逻辑,留 M8d2。

### 测试增补(对应修复 2/3)

- `titleOverride_fromRegisterTalent`(register_talent 带 name → `Talent.MOD_EXAMPLE_TALENT.title()` 返回该 name)
- `m7eOverrideWithoutName_keepsVanillaTitle`(register_talent_override 无 name → title 走 Messages,M7e 不回归)
- `tier3_rejected_byMvp`(register_talent tier=3 → 拒绝,size 不增)

## codex 评审轮2 修复(实施 review,2026-07-07,worker 自审)

codex `exec` review 实施(`git diff master...HEAD`)返回 1 个 must-fix,已落地:

4. **register_talent 必须强制校验 name/title**(`LuaEngine.RegisterTalentFunction`):MOD_ enum 无 `.title` properties key,漏传/错传 name 会注册出"可注入、可升级、但 UI 显示 `!!!MOD_*.title!!!`"的新天赋(契约漏洞)。修复:`LuaTalentRegistry.register` 调用前校验 `name` 或 `title` 至少一个是 `LuaString`(严格,排除 number-coerce;与 `LuaTalentOverride.parseTitle` 一致),否则 log + skip。测试增补:`missingName_rejected` / `badNameType_rejected` / `titleAlone_acceptable`;`isKnownModTalent_unit` / `clear_removesAllInjections` 的成功注册用例补 `name='x'`。



## Acceptance

- [ ] LuaTalentRegistry + register_talent 完成
- [ ] Talent.java 新 MOD_ enum 项 + initClassTalents Lua 注入 hook(单点,不动 switch 体)
- [ ] restoreTalentsFromBundle mod 未知 name 静默 skip(旧档不崩)
- [ ] 新天赋进 tier(可用天赋点升级)
- [ ] 不开 luajava(只传 id/int/string)
- [ ] `:core:test` 通过,既有 331 tests 不回归;C3 守住
- [ ] 与 M7e LuaTalentOverride 共存(新 enum 也能被覆写)

## M8d2 预留(本 feature 不做)

- onTalentUpgraded L590 末尾 `LuaTalentRegistry.dispatchTalentUpgraded(hero, talent)`
- RpdApi `hero.give_item`/`hero.add_buff` 原子 API(送物品/注 Buff)
- 复杂互锁(IRON_WILL/SPIRIT_FORM)保留 Java 硬编码

## Risks

- 新 enum 项 icon 资产(占位或复用现有)
- restoreTalentsFromBundle skip 逻辑需区分"mod 卸载"(isKnownModTalent=true 静默)vs"真正未知"(reportException)
- 守 fork 约束:Talent.java 单点 hook(L1074/L1215),不动 enum 既有项/init switch 体/onTalentUpgraded;新代码进 modding/ 子包
- M8d1 不含 on_upgrade(M8d2),新天赋仅数值 + tier 注入
- C5 proguard:enum 反射 `Talent.valueOf` 既有 keep 覆盖;LuaTalentRegistry 无反射
