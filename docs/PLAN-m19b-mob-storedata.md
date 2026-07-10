# PLAN: M19b — LuaMob storeData/restoreData persistence

## Goal
扩展 LuaMob 任意状态持久化,支持 Lua 脚本在 mob 上 `storeData`/`restoreData` 安全 JSON-like 数据,让状态型 mob 跨存档不丢;把 remixed_full_fetid_rat 从 M17b 的 id 派生 kind 回归为随机 kind + 持久化。

## Context
m17b 搬 `remixed_fetid_rat` 时 fork 缺 `storeData/restoreData`,只能用 `(selfId % 3)+1` 确定性派生 kind 替代随机+持久化。M19b 解决这一类状态型 mob 的根能力。注意只做 LuaMob,不碰 trap data(并行 M19a)。

## Files
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaMob.java` — 新增 per-instance `LuaTable data` 字段 + Bundle 存读 + 私有静态 codec(内联,镜像 `LuaItem` 的 key-typed 编码,加深度/大小限制)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/modding/RpdApi.java` — 暴露 `RPD.mobStoreData(selfId, data)` / `RPD.mobRestoreData(selfId)`,经现有 `resolveLuaMob` 解析
- `core/src/main/assets/mods/test_mod/scripts/mobs/remixed_fetid_rat.lua` — **实际含 `(selfId%3)+1` workaround 的 M17b 文件**,改回随机 kind + 持久化
- `core/src/main/assets/mods/remixed_full/scripts/mobs/remixed_full_fetid_rat.lua` — 当前是 minimal stub(仅 attackProc 中毒),补齐 3-kind gas + 持久化(faithful 到原 remixed FetidRat)
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/LuaMobStoreDataTest.java` — 数据持久化测试(新建,模板 = `LuaItemStateTest`)
- `docs/PLAN-m19b-mob-storedata.md`

## Decisions(worker 细化时确认)

1. **fetid_rat 文件歧义**:PLAN 原文把 `remixed_fetid_rat`(test_mod,M17b,含 workaround)与 `remixed_full_fetid_rat`(remixed_full,minimal stub)混为一谈。**两文件都改**:test_mod 那个是 workaround 真正所在(必须回归),remixed_full 那个是 supervisor message 点名的目标(目前无 kind,补齐为完整 FetidRat 行为以展示能力)。两处都用新 API。`RemixedFullPackTest` 仅断言注册不断言行为,改动安全。
2. **API 形态**:`RPD.mobStoreData(selfId, data)` / `RPD.mobRestoreData(selfId)`(对称、`mob` 前缀明示作用域,避免 flat namespace 碰撞)。`mobRestoreData` 返回 mob 的 live data table(按引用,Lua 可原地改),`mobStoreData` 替换该 table(table 校验,nil/非 table → no-op)。语义对齐 remixed `mob.storeData/restoreData`。
3. **Codec 内联在 LuaMob**(不抽公共类、不碰 LuaItem/LuaBuff):镜像 fork 既有约定(`LuaItem`/`LuaBuff` 各自内联 own codec),零跨文件 blast radius。用 `LuaItem` 的 key-typed 变体(`n:`/`s:` key,数字 key 不塌成字符串),比 `LuaBuff` 的 string-key 更正确。**加 `LuaItem` 没有的深度/大小限制**(Step 3 要求):maxDepth=8、maxRows=1000,防自引用 table 栈溢出 + 存档膨胀。跨类合并列为 follow-up,不在本 PR。
4. **不碰**:LuaTrap、DataDrivenLevel、LuaItem、LuaBuff、`.claude/`。不用 `git add -A`(显式 `git add` 具体文件)。

## Steps
1. ✅ 读 `LuaMob.java`(storeInBundle/restoreFromBundle 字段:`lua_mob_id`/`lua_spawned`/`lua_immunity_classes`/`stolen_loot`)、`LuaItem.java`(state codec 模板)、`LuaBuff.java`(string-key codec 对照)、`RpdApi.java`(已有 `placeBlob`/`addImmunity`/`Blobs`/`Buffs`/`resolveLuaMob`)、remixed 源 `RemixedFetidRat.lua` + `mob.lua`(`storeData/restoreData` 语义)。
2. **LuaMob.java**:
   - 加 `private LuaTable data;` 字段(不声明初始化,镜像 `LuaItem.state`;由 hydrate 设)。
   - `hydrate()` 末尾 `data = new LuaTable();`(fresh + restore 都走 hydrate;restore 在 hydrate 后再 load,不会被清)。
   - 加常量 `LUA_MOB_DATA = "lua_mob_data"`。
   - `storeInBundle`:**先序列化再判空**(reviewer nice-to-have):`String[] rows = (data==null)?null:serializeData(data); if (rows != null && rows.length > 0) bundle.put(LUA_MOB_DATA, rows);`。这样 data 仅含 function/userdata/thread(全被跳过)或超深截断后无行时,不写空 key —— 比 `hasEntries` 更贴合"空 data 不写 key + 旧档兼容"目标。不引入 `hasEntries`。
   - `restoreFromBundle`:在 hydrate 之后 `if (bundle.contains(LUA_MOB_DATA)) loadData(bundle.getStringArray(LUA_MOB_DATA));`(hydrate 已把 data 重置为空,load 填充)。
   - package-private accessors(供 RpdApi 同包调用):`LuaTable luaData()`(`data==null?new LuaTable():data`,Lua 永远拿到 table)、`void luaData(LuaTable t)`(`data = t==null? new LuaTable():t`)。
   - 私有静态 codec(抄 `LuaItem.serializeState/loadState/encodeKey/decodeKey/encodeScalar/decodeScalar/b64/unb64`,key-typed),**加** `MAX_DEPTH=8` / `MAX_ROWS=1000`:`writeStateRows(table, path, out, depth)` 带 depth 参数,`depth>=MAX_DEPTH` 不再递归(深子表整体跳过,不栈溢出);`out.size()>=MAX_ROWS` 停止追加。encodeScalar 对 function/userdata/thread 返回 null → 该叶子跳过。
3. **RpdApi.java**:在 M6a blob/immunity 区附近加两个内部类 + `build()` 注册:
   - `MobRestoreData extends OneArgFunction`:`LuaMob m = resolveLuaMob(id,"mobRestoreData"); return m==null? NIL : m.luaData();`(返回 live table)。
   - `MobStoreData extends TwoArgFunction`:`LuaMob m = resolveLuaMob(...); if(m==null||!dataVal.istable()) return NIL; m.luaData(dataVal.checktable()); return NIL;`。
   - `rpd.set("mobRestoreData", new MobRestoreData()); rpd.set("mobStoreData", new MobStoreData());`。
4. **test_mod/remixed_fetid_rat.lua**:删 `kindOf`/`(selfId%3)+1`;`spawn` 改 `local d=RPD.mobRestoreData(selfId); if not d.kind then d.kind=math.random(1,3); RPD.mobStoreData(selfId,d) end; RPD.addImmunity(selfId,KINDS[d.kind].immunity)`;`act` 改 `local d=RPD.mobRestoreData(selfId); RPD.placeBlob(KINDS[d.kind or 1].blob,RPD.charPos(selfId),50); return false`;更新 header 注释(M19b:真实持久化取代 id 派生)。
5. **remixed_full/remixed_full_fetid_rat.lua**:从 minimal stub 升级为完整 FetidRat(3-kind gas + persist),结构与 test_mod 版一致;`KINDS`+`spawn`+`act`。source 无 attackProc 中毒,故移除以 faithful(保留 name/spriteFile/hp/ht/attack/defense/sprite/maxLvl)。
6. **LuaMobStoreDataTest.java**(模板 `LuaItemStateTest`):
   - `mobDataRoundTripsThroughBundle`:inline register 一个 mob,`Actor.add(mob)` 拿 id,`RPD.mobStoreData(id, {kind=2,name='x',nested={a=true,b='s'}})`→storeInBundle→restore→`RPD.mobRestoreData` 断言 kind/name/nested 一致。
   - `fetidRatKindPersistsAcrossRestore`:inline mob 其 `spawn` 随机+存 kind;create+Actor.add+手动 dispatch spawn(经 registry table 的 spawn fn)→读 kind→storeInBundle→restore→再读 kind 相同;顺带断言 immunity FQCN 持久化。
   - `malformedDataBundleDoesNotCrash`:corrupt `lua_mob_data` rows → restore 不抛(镜像 `LuaItemStateTest`)。
   - `noDataMobRoundTripsCleanly`:从不调 storeData 的 mob round-trip 无 `lua_mob_data` key、data 为空 table。
   - `safetyBoundariesBoundRecursionAndSkipUnsupported`(reviewer must-fix):从 Lua 构造 `d.self=d`(自引用)、超深嵌套 table(>MAX_DEPTH)、一个 function 值 + 一个正常标量 `d.keep=7`;`RPD.mobStoreData` → `storeInBundle`/`restoreFromBundle` **不抛、不栈溢出**;restore 后 `d.keep==7` 可读、self/function 未恢复(data 里不存在)。验证 MAX_DEPTH/MAX_ROWS 防栈溢出 + unsupported 类型跳过。
7. `./gradlew :core:test` 绿(已知 flaky `GeneratorLuaItemTest.luaItemProbabilityPersistsAcrossFullReset` 单测失败可重跑,不计回归)。
8. codex_reviewer 评审:`assign("codex_reviewer", "评审方案: docs/PLAN-m19b-mob-storedata.md")`;assign 失败/静默按协议跳过并在回报告知 supervisor。

## Acceptance
- [ ] LuaMob 能持久化安全 data(string/number/bool/浅+嵌套 table),restore 后 Lua 经 `RPD.mobRestoreData` 可读取;function/userdata/thread 被跳过;超深/超大/自引用被截断不崩(有专项测试覆盖)。
- [ ] test_mod `remixed_fetid_rat` 与 remixed_full `remixed_full_fetid_rat` 不再依赖 id 派生 kind,kind 随机生成 + 跨存档稳定。
- [ ] 不破坏旧 LuaMob 存档(新 key 缺失时 data=空 table)、无 data mob(不写空 key)。
- [ ] `:core:test` 绿。
- [ ] 不碰 LuaTrap/DataDrivenLevel/LuaItem/LuaBuff;不提交 `.claude/`;不用 `git add -A`。
