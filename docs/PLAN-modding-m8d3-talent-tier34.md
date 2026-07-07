# PLAN: M8d3 — tier3/4 mod 天赋注入(D6(b) 闭合)

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M8(M8d3 条目)+ §11 D6(b) 评估
> 前置:M8d2 已合(on_upgrade Lua 回调);master `f09c14a94`,397 tests
> 本 feature 是 **D6(b) 最后一块**:mod 可注册 tier 3(subclass)/tier 4(armor ability)新天赋,闭合"Lua 注册新天赋"全 tier 覆盖
> 串行:本 feature 在 M8d2 合后做(都改 Talent/LuaTalentRegistry/LuaEngine)

## Goal

`register_talent{ id=..., tier=3|4, subclass=...|armor_ability=..., ... }` 可把预声明 `MOD_` enum 注入 subclass(tier 3)或 armor ability(tier 4)的 tier 槽。tier 1/2 路径不变(class-scoped),tier 3/4 新增 subclass/armorAbility 维度。**cap 问题**:vanilla tier3 cap 3、tier4 cap 4,现有 `MOD_EXAMPLE_TALENT/MOD_SECOND_TALENT` 用两参构造(baseMaxPoints=2),M7e maxPoints lowers-only 无法上调,故 tier3/4 必须用**新预声明的 cap 3/4 enum 槽位**。

## Context

### 为什么 tier3/4 需要 cap 3/4 的新 enum

- `Talent` enum 构造:两参 `(name, icon)` 默认 `baseMaxPoints=2`;四参 `(name, icon, baseMaxPoints, ...)` 指定 cap
- vanilla tier1/2 cap 2、tier3 cap 3、tier4 cap 4(见 `Talent.maxPoints()` / `TIER_3`/`TIER_4` 常量)
- M7e `LuaTalentOverride` maxPoints 是 **lowers-only**(≤ `baseMaxPoints`,见 M7e codex R2 决策:上调会触发除零 / IntRange min>max / ==N 永不匹配等 domain 违规)
- 所以 `MOD_EXAMPLE_TALENT`(cap 2)不能当 tier3/4 天赋——maxPoints 上不去
- **方案**:新增 `MOD_TIER3_TALENT`(四参构造,baseMaxPoints=3)+ `MOD_TIER4_TALENT`(baseMaxPoints=4)两个占位槽,mod 用 id 激活

### 关键 file:line(M8d2 后实测)

- `core/.../actors/hero/Talent.java:97-204`(enum,加 `MOD_TIER3_TALENT`/`MOD_TIER4_TALENT`)
- `core/.../actors/hero/Talent.java:1104-1157`(`initSubclassTalents`,L1156 `tierTalents.clear()` 后加 `LuaTalentRegistry.injectSubclassTalents(cls, talents)`)
- `core/.../actors/hero/Talent.java:1163-`(`initArmorTalents`,末尾加 `LuaTalentRegistry.injectArmorTalents(abil, talents)`)
- `core/.../modding/LuaTalentRegistry.java`(M8d1/M8d2,加 `injectSubclassTalents`/`injectArmorTalents` + tier 校验放宽)
- `core/.../modding/LuaEngine.java:752-`(`RegisterTalentFunction`,tier 校验 [1,2]→[1,4] + subclass/armor_ability 字段)

### tier 维度映射

| tier | 注入方法 | 维度 key | enum 槽 | cap |
|---|---|---|---|---|
| 1-2 | `injectClassTalents`(M8d1 已有) | `HeroClass` | `MOD_EXAMPLE_TALENT`/`MOD_SECOND_TALENT` | 2 |
| 3 | `injectSubclassTalents`(本 feature 新增) | `HeroSubClass` | `MOD_TIER3_TALENT` | 3 |
| 4 | `injectArmorTalents`(本 feature 新增) | `ArmorAbility` | `MOD_TIER4_TALENT` | 4 |

## Phase 1 findings（worker 实测,2026-07-07)

- **`ArmorAbility` 不是 enum** —— 它是 `abstract class implements Bundlable`,具体子类 `HeroicLeap`/`Shockwave`/`Endure`/.../`PowerOfMany` 共 18 个,无 name 注册表。所以 tier 4 **不能用 `ArmorAbility.valueOf`**。改用 **simple class name** 作 key:mod 写 `armor_ability = "HeroicLeap"`,Java 侧 `abil.getClass().getSimpleName()` 比较。`HeroClass.armorAbilities()` 每个 class 返回 3 个具体子类实例,`Hero.armorAbility` 指向其中之一。simple name 全局唯一(子类分散在 warrior/mage/rogue/huntress/duelist/cleric 子包,无重名)。
- **`HeroSubClass` 是 enum** → tier 3 仍用 `HeroSubClass.valueOf`(PLAN 原案不变)。
- **enum 构造**:只有两参 `(int icon)`(默认 cap 2)和 `(int icon, int maxPoints)`。无四参构造。PLAN 原文"四参构造"是笔误,实际用两参 `(icon, maxPoints)`: `MOD_TIER3_TALENT(221, 3)`,`MOD_TIER4_TALENT(222, 4)`。
- **icon frame 221/222 未用**(219/220 已被 MOD_EXAMPLE_TALENT/MOD_SECOND_TALENT 占用;talent_icons.png 512x128 → 0..255,221/222 合法未用)。
- **hook 点实测**:`initSubclassTalents` L1155 `tierTalents.clear();` 之后、L1157 `}` 之前插 `injectSubclassTalents(cls, talents)`;`initArmorTalents` L1172 for 循环之后、L1173 `}` 之前插 `injectArmorTalents(abil, talents)`。
- **MAX_TALENT_TIERS = 4**:`talents.get(3)` 存在,tier4 槽可用。
- **Bundle 兼容**:MOD_TIER3/4 是真 enum 常量,`Talent.valueOf("MOD_TIER3_TALENT")` 成功;mod 禁用重载时 `tier.containsKey` 为 false → 静默丢弃(M8d1 既有路径,无需改 `isKnownModTalent`)。
- **dispatch class-agnostic**:`Hero.upgradeTalent` → `Talent.onTalentUpgraded` → `LuaTalentRegistry.dispatchTalentUpgraded` 按 Talent 查 byTalent,与 tier 无关 → tier3/4 on_upgrade 自动工作,无需改 dispatch。

## Files

- `core/src/main/java/.../actors/hero/Talent.java`:
  - enum 加 `MOD_TIER3_TALENT(221, 3)` + `MOD_TIER4_TALENT(222, 4)` —— 两参构造 `(icon, maxPoints)`,`maxPoints()` 返回 3/4
  - `initSubclassTalents` L1155 `tierTalents.clear()` 后加 `LuaTalentRegistry.injectSubclassTalents(cls, talents);`(单点 hook,switch body 不动)
  - `initArmorTalents` L1172 for 循环后、`}` 前加 `LuaTalentRegistry.injectArmorTalents(abil, talents);`
- `core/src/main/java/.../modding/LuaTalentRegistry.java`:
  - `ModTalentDef` 加 `HeroSubClass subClass` + `String armorAbilityName` 字段(tier 1/2 为 null,tier 3 用 subClass,tier 4 用 armorAbilityName=simple class name)
  - `register` 签名扩展:接收 subClass/armorAbilityName;tier 校验 [1,2]→[1,4];tier 3 要求 subClass≠null、tier 4 要求 armorAbilityName≠null、tier 1/2 要求 heroClass≠null(互斥校验,错则 log+skip)
  - `injectSubclassTalents(HeroSubClass cls, talents)`:遍历 byTalent,tier==3 && def.subClass==cls → `talents.get(2).put(talent, 0)`(idempotent)
  - `injectArmorTalents(ArmorAbility abil, talents)`:tier==4 && def.armorAbilityName.equals(abil.getClass().getSimpleName()) → `talents.get(3).put(talent, 0)`
  - `clear` 不变(已清 byTalent)
- `core/src/main/java/.../modding/LuaEngine.java`(`RegisterTalentFunction`):
  - tier 校验 `tier < 1 || tier > 2` → `tier < 1 || tier > 4`
  - tier 3:读 `tbl.get("subclass")` → `HeroSubClass.valueOf`(catch IllegalArgumentException → log+skip)
  - tier 4:读 `tbl.get("armor_ability")` → 字符串存为 armorAbilityName(不 valueOf,在 inject 时与 `abil.getClass().getSimpleName()` 比较;无校验,因 ArmorAbility 无注册表 —— 注入时不匹配则自然 noop)
  - tier 1/2:仍读 `class` → `HeroClass.valueOf`(现状不变)
  - 转发 `LuaTalentRegistry.register(talent, tier, heroClass, subClass, armorAbilityName, onUpgrade)`
- `core/src/main/assets/mods/test_mod/scripts/talents/mod_tier34.lua`(新):register tier3(MOD_TIER3_TALENT + subclass=BERSERKER)+ tier4(MOD_TIER4_TALENT + armor_ability="HeroicLeap")示例
- `core/src/test/java/.../modding/LuaTalentRegistryTest.java`:扩 tier3/4 注入 + register 校验 + on_upgrade 仍工作

## Steps

1. **Talent enum 加 MOD_TIER3_TALENT / MOD_TIER4_TALENT**:两参构造 `(icon, maxPoints)`,`MOD_TIER3_TALENT(221, 3)` / `MOD_TIER4_TALENT(222, 4)`,icon 221/222 未用(见 Phase 1 findings);`maxPoints()` 走 vanilla 逻辑返回 3/4
2. **LuaTalentRegistry ModTalentDef 扩展 + register tier [1,4]**:加 subClass/armorAbility 字段;register 互斥校验(tier↔key);`injectSubclassTalents`/`injectArmorTalents`(idx=2/3,idempotent)
3. **Talent.initSubclassTalents / initArmorTalents 双 hook**:L1156 + initArmorTalents 末尾各加单行 `LuaTalentRegistry.inject*(...);`
4. **RegisterTalentFunction tier [1,4] + subclass/armor_ability 字段**:tier 3/4 读对应字段,valueOf 校验,转发 6 参 register
5. **示例 mod_tier34.lua**:tier3 注册到指定 subclass + tier4 注册到指定 armor_ability
6. **测试**:
   - tier3 register → injectSubclassTalents 把 talent 放进 talents.get(2)
   - tier4 register → injectArmorTalents 把 talent 放进 talents.get(3)
   - tier3/4 register 带 on_upgrade,升级仍触发 dispatch(M8d2 路径不回归)
   - tier 3 缺 subclass → reject;tier 4 缺 armor_ability → reject
   - tier 1/2 路径不回归(仍用 heroClass)
   - C3:test_mod disabled,inject* noop,vanilla initSubclassTalents/initArmorTalents 字节不变
   - 既有 397 tests 不回归
7. **Bundle 兼容**:MOD_TIER3/4 enum 进 save → restoreTalentsFromBundle 的 `isKnownModTalent` 静默 skip 路径(M8d1 已有)覆盖新 enum 名

## Acceptance

- [ ] `MOD_TIER3_TALENT`(cap 3)/ `MOD_TIER4_TALENT`(cap 4)enum,`maxPoints()` 返回 3/4
- [ ] `LuaTalentRegistry.injectSubclassTalents` / `injectArmorTalents`(idempotent,idx 2/3)
- [ ] `Talent.initSubclassTalents` L1156 + `initArmorTalents` 末尾双 hook(单点,switch body 不动)
- [ ] `register_talent` tier [1,4],tier 3/4 读 subclass/armor_ability 字段
- [ ] tier↔key 互斥校验(tier3 必 subClass、tier4 必 armorAbility、tier1/2 必 heroClass)
- [ ] tier3/4 on_upgrade 仍触发(M8d2 dispatch 不回归)
- [ ] 不开 luajava(只传 id/int/enum name)
- [ ] `:core:test` 通过,既有 397 tests 不回归;C3 守住

## Risks

- **cap 上调 domain 违规**:本 feature 不放宽 M7e maxPoints 上调,而是用新 enum 的 baseMaxPoints=3/4 从源头满足 cap —— 避免 M7e R2 的除零/IntRange 问题
- **enum 槽位有限**:MOD_TIER3/4 各 1 个槽,mod 间互斥激活(同 M8d1 设计);若需多 tier3 mod 共存,后续 milestone 加更多槽
- **ArmorAbility 非 enum**:已确认 `ArmorAbility` 是 abstract class,tier 4 改用 simple class name 比较(见 Phase 1 findings),不用 valueOf。mod 写错名(如 "Bogus")→ inject 时无 abil 匹配 → 自然 noop,不会崩。
- **initArmorTalents 结构**:L1163- 末尾 hook 点,确认 tier4 槽 `talents.get(3)` 存在(MAX_TALENT_TIERS≥4)
- **守 fork 约束**:Talent 双 hook 单点;新代码进 modding/ 子包 + Talent enum 内
- C5 proguard:enum 无反射,不需 keep
