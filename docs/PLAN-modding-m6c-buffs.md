# PLAN: M6c — Remished buff wrapper + 16 buff 脚本

> 上层路线图:`docs/MODDING-ROADMAP.md` §4 M6
> 前置:M6b 已合 master(`72c13386d` feature,merge 后 :core:test 通过,240 tests)
> 并行:M6d(item/spell)同时开发。**约定 RpdApi 分块 + 独立测试类,降低冲突**
> D5 = (a) B 全量;D5' = (a) 禁 luajava

## Goal

实现 Lua buff 基础设施(`register_buff`/`LuaBuff`/`LuaBuffRegistry`)并移植 Remished `scripts/buffs/` 的 16 个 buff 脚本,至少完整跑通代表性 buff 生命周期(attach/act/detach/叠加),为 M6e 平衡收尾提供 buff 内容包。

## Context

M6b 预测:Buff 侧估 1.5–2.5 天。主要成本不是 Lua 改写,而是把 Remished buff 生命周期/叠加语义映射到 SPD `Buff` 子类。建议先扩 `affectBuff` + `removeBuff` + `permanentBuff` 三类窄 API,并对 3–4 个代表 buff 做单测后批量迁移。

Remished buff 脚本规模(已探查):16 个,24–165 行,总 744 行。候选代表:
- 简单短脚本:`Anesthesia`/`GasesImmunity`/`ManaShield`
- 中等生命周期:`BodyArmor`/`Cloak`/`DieHard`/`Counter`
- 元素 champion:`ChampionOfAir/Water/Fire/Earth`
- 大脚本:`ShieldLeft`/`ChaosShieldLeft`

## Files

- `core/src/main/java/.../modding/LuaBuff.java`(新增):extends `Buff`,保存 `luaBuffId`/`level`; no-arg restore ctor + Bundle `lua_buff_id`/`lua_buff_level`/`restoring`/`lua_state`;每个 Java buff 实例持有隔离 `LuaTable state`,作为回调参数传给脚本,避免 registry table 共享状态；`lua_state` 必须显式序列化/反序列化,仅保存 string/number/boolean 与嵌套 table,跳过 function/userdata/thread 等不可序列化值；回调 `attachTo(targetId,state)` / `act(selfId,targetId,state)` / `detach(targetId,state)` / `icon(state)` / `name(state)` / `info(state)`；`act` 默认 `detach()`(与 `Buff.act`)；Lua 返回 number 时 `spend(number)`,返回 boolean true 时 `spend(TICK)`,返回 nil/false 时 `detach()`；提供 public `refresh(level,duration)` / `makePermanent()` / `sameLuaId(id)` 包装 protected `postpone`/`diactivate` 与同类多 id 查询；override `immunities()` 从 Lua `immunities` 字段/函数读取白名单 id 并映射到 Blob/Buff Class。
- `core/src/main/java/.../modding/LuaBuffRegistry.java`(新增):id→LuaTable registry/create/contains/size/clear,模式同 `LuaSpellRegistry`。
- `core/src/main/java/.../modding/LuaEngine.java`:加 `register_buff` + `loadBuffScripts()`；扫描 `mods/<id>/scripts/buffs/*.lua`; `resetForTests` 仍只 drop singleton,实际 registry clear 由 `ModTestSupport.resetLuaState()` 补齐。
- `core/src/main/java/.../modding/RpdApi.java`:**M6c 分块**新增 `affectLuaBuff` / `removeBuff` / `permanentBuff` / `setBuffLevel` / `buffLevel` / `detachBuff`；`affectBuff` 仍处理 Java whitelist,额外识别 LuaBuff id 并委托 `LuaBuffRegistry`。
- `core/src/main/java/.../modding/RpdApi.java`:扩 `BuffWhitelist` 导入并注册 SPD 等价 buff:`Invisibility`/`Levitation`/`Frost`/`Light`/`Bless`/`Fury`/`Speed`/`Burning`/`Amok`/`Sleep`/`Drowsy`/`Blindness`/`Weakness`/`Vulnerable`/`MagicImmune` 等 Remished 脚本引用项；暂不开放 source-aware 的 `Charm`/`Terror`(需要 attacker/source id),也不开放 `Invulnerability`/任意 Class。
- `core/src/main/assets/mods/test_mod/scripts/buffs/`(新增 16 个脚本):用 `register_buff{...}` 改写 Remished 脚本；无法在 M6c 生命周期中表达的 Remished hook 在脚本 `info`/字段里明确 `degraded=true` + `degradation='...'`。
- `core/src/test/java/.../modding/ModTestSupport.java`:clear `LuaBuffRegistry`。
- `core/src/test/java/.../modding/RpdApiBuffTest.java`(新增):独立测试类,避免与 M6d 冲突。
- `core/src/test/java/.../modding/ModToggleRegressionTest.java`:加入 buff registry disabled/enabled/id/size exact checks。

## Steps

1. **实现 `LuaBuffRegistry` 与 `register_buff`**:沿 `LuaSpellRegistry`/`RegisterSpellFunction` 形状验证 `id/name`; `icon`/`info`/`act`/`attachTo`/`detach`/`immunities`/`degraded` 可选；坏 table 只 log+skip。
2. **实现 buff loader**:在 `LuaEngine.initInternal()` 的 type loaders 中加入 `loadBuffScripts()`(shop 之后、entry 之前即可);目录为 `mods/<id>/scripts/buffs`; disabled mod 时不扫描。
3. **实现 `LuaBuff` 最小生命周期**:构造时 hydrate table 并创建每实例独立 `state` table；`attachTo` 在新 attach 时调用 Lua `attachTo(targetId,state)`,false 则拒绝；Bundle restore 设置 `restoring=true`,SPD `Char.restoreFromBundle` 重新 `attachTo` 时只执行 `super.attachTo(target)` 并跳过 Lua `attachTo` 副作用。`act` 调 Lua `act(selfId,targetId,state)`,number=spend 秒,true=spend TICK,nil/false=detach；`detach` 调 Lua `detach(targetId,state)` 后 super；`name/desc/icon/iconTextDisplay` 从 Lua 字段/函数读取,无值 fallback id/name/info。
4. **LuaBuff 状态与叠加语义**:`level` 是 Java 字段,RPD `affectLuaBuff(charId,buffId,level)` 在 `target.buffs(LuaBuff.class)` 中按 `sameLuaId(id)` 查找同 id buff；已有则调用 `refresh(level,duration)` 更新 level/时间,不重复 attach；没有则 `LuaBuffRegistry.create(id)` 后 `attachTo(target)`。禁止用 `Buff.append`(只能 no-arg class instantiate)或 `target.buff(LuaBuff.class)`(class-exact,会混淆所有 LuaBuff id)。`permanentBuff` 对 LuaBuff 调 `makePermanent()`。Bundle restore 后从 registry rehydrate；脚本移除时 degrade 为 `??? (<id>)` 且 act detach。
5. **RpdApi M6c buff API**:在 `RpdApi.build()` 加 `// M6c buff API` 分块; `affectBuff` 先查 Java `BuffWhitelist`,再查 `LuaBuffRegistry`;新增 `removeBuff(charId,buffId)` 同时支持 Java whitelist class 与 LuaBuff id；`permanentBuff(charId,buffId[,level])` 仅对 LuaBuff 常驻(避免 Java `FlavourBuff`/source-aware buff 语义错误)；`RPD.Buffs` 只放 Java whitelist 常量,`RpdApi.build()` 在 buff loader 之前执行,无法包含 Lua ids——文档里写明 Lua buff 必须用字符串字面量 id,不走常量表(后续若需要可加 lazy/动态 `RPD.LuaBuffs` 表)。
6. **扩 `BuffWhitelist` 与 immunity 解析**:按 Remished 16 脚本引用补齐 SPD 等价项。`Burning`/`Frost`/`Sleep`/`Drowsy` 等若不是 `FlavourBuff` 就用对应公开 setter/`Buff.affect` 最小安全策略;没有安全公开 apply 语义的保持不开放并在脚本降级说明中记录。新增共享 id→Class resolver 供 `RPD.addImmunity` 与 `LuaBuff.immunities()` 复用,覆盖 `GasesImmunity` 的 `ToxicGas`/`Paralysis`/`Vertigo`。
7. **移植 16 scripts/buffs**:文件名对应 Remished 16 个:`Anesthesia`,`BodyArmor`,`ChampionOfAir/Earth/Fire/Water`,`ChaosShieldLeft`,`Cloak`,`Counter`,`DieHard`,`Encumbrance`,`GasesImmunity`,`ManaShield`,`ShieldLeft`,`TestBuff`,`UnsuitableItem`。M6c 完整支持:metadata/name/info/icon, attach/act/detach, level, target damage via RPD.damageChar, apply Java/Lua buffs via RPD.affectBuff/removeBuff/permanentBuff。明确降级:Remished `defenceProc/attackProc/drBonus/stealthBonus/speedMultiplier/charAct/regenerationBonus/hasteLevel/target:setGlowing` 暂不改 Char/Mob 全局 hook,脚本保留 metadata + lifecycle 可测行为并标注。
8. **测试**:`RpdApiBuffTest` 覆盖 register validation、loader loads 16、disabled mod zero buff、affect/remove/permanent LuaBuff、act numeric/boolean/nil semantics、detach callback、level update/stack,每实例 state 不串联且 Bundle round-trip,state restore 后不重放 attach 副作用,`GasesImmunity` 的 Lua `immunities` 映射真实生效,Java whitelist扩展常量,sandbox luajava 仍不可达。`ModToggleRegressionTest` exact registry 数更新。
9. **回填本 PLAN 末尾 M6e 预测**:16 buff 完成度、降级 hook 清单、M6e 若要高保真需新增 Char/Mob hook 的风险与建议。

## Acceptance

- [x] `register_buff` / `LuaBuffRegistry` / buff loader 完成,mod disabled 时 0 buff(C3)
- [x] 至少 16 个 Remished buff 脚本被移植或明确标注不可等价降级;代表 3–4 个有行为测试
- [x] `affect/remove/permanent` buff API 可用于 Lua mob/spell/item
- [x] 不开 luajava,不引入 Remished `scripts/lib/`
- [x] 新增 `RpdApiBuffTest`;既有 core tests 不回归
- [x] 回填 M6e 预测:平衡风险/不可等价清单/后续调参建议

## M6e 预测(完成后回填)

- 已完整移植 buff 数:4 / 16 高保真或行为等价基础可用 (`GasesImmunity` real immunities, `Counter` act/state, `Cloak`→SPD Invisibility, `ChampionOfEarth` one-shot heal intent)
- 降级/不可等价 buff:12 / 16 涉及 Remished-only hooks: `damage`/`defenceProc`/`attackProc`/`drBonus`/`speedMultiplier`/`stealthBonus`/`charAct`/`regenerationBonus`/`hasteLevel`/sprite glow/source item shield logic。`Charm`/`Terror` 未进 Java whitelist,因为需要 source id 语义。
- 新增 Lua-facing surface 数:3 (`RPD.affectBuff` 支持 Lua buff id, `RPD.removeBuff`, `RPD.permanentBuff`) + `register_buff`。
- 单 buff 平均工时:约 0.25–0.35 天(基础设施摊销后脚本多为 metadata/lifecycle 降级移植)。
- M6e 平衡风险:中高。当前 16 buff 已注册可加载,但多数战斗强度 hook 未接入 SPD Char/Mob 核心。若 M6e 追求 Remished 高保真,优先设计 source-aware combat hooks: generic `defenseProc`/`attackProc`, DR/speed/stealth/regeneration bonus aggregation, source-aware `Charm`/`Terror`, shield source item level。若只做内容包可用,先用现有 LuaBuff lifecycle + Java whitelist 调参即可。

## Parallel notes(M6d)

M6c 与 M6d 并行。共享冲突点:`RpdApi.build()` 注册区、`ModToggleRegressionTest` registry exact counts。约定:
- M6c 用 `// M6c buff API` 分块 + `RpdApiBuffTest`
- M6d 用 `// M6d item/spell API` 分块 + `RpdApiItemSpellTest`
- 合并时若 `ModToggleRegressionTest` exact sizes 冲突,取并集(类似 M6a/M6-fast)

## Pending Issues(codex 第三轮未决,实施时落实)

1. **`lua_state` 序列化** ✅ 落地:`LuaBuff` 用 Base64 path + 递归扁平化的显式 serializer,只存 string/number/boolean/嵌套 table;function/userdata/thread 跳过并由脚本 `onRestore(state)` 重建。测试覆盖含 `.`/`=`/深层的 round-trip。
2. **`RPD.Buffs` 不含 Lua ids** ✅ 落地:`RPD.Buffs` 仅暴露 Java whitelist 常量;Lua buff 用字符串字面量 id 调用 `affectBuff`/`removeBuff`/`detachBuff`/`permanentBuff`/`setBuffLevel`,`buffLevel(charId,id)` 读取。
3. **Level API**(codex 实现 review round-1 must-fix) ✅ 落地:新增 `RPD.setBuffLevel` / `RPD.buffLevel` / `RPD.detachBuff`;`counter.lua` 用 level 递减并在 0 时 `detachBuff`。

