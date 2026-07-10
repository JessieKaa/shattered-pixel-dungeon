# PLAN: M18a — NPC chooseOption 选项对话框 API

## Goal
给 fork 加 `RPD.chooseOption` 选项对话框 API,让 Lua NPC 能弹出多选项窗口并回调选择,解锁选项型 NPC 交互(remished 的 Innkeeper/PlagueDoctor/Mercenary + 现有 m17a 的 barman/bishop/inquirer 升级为选项交互)。

## Context
M17a 搬 NPC 时,`chooseOption` 是 fork 缺失 API 之首(6 个降级 NPC 里 3 个原用 chooseOption → 降级为单线 dialog)。这是 M18 扩 API 批次的首个,跑通"扩 fork Lua API"的完整模式(API 设计 + Wnd 接入 + render-thread 安全 + 测试 + 示例升级),为 m18b/c/d 立模板。

**fork NPC API 现状**(调研确认):
- `RpdApi.java` NPC 原语注册在 L163-201:`rpd.set("npcYell", new NpcYell())` / `rpd.set("showDialog", new ShowDialog())` / `rpd.set("giveItem", new GiveItem())` / `rpd.set("leaveTown", new LeaveTown())`。每个是一个内部类,继承两参 LuaValue 函数。
- `ShowDialog`(L942)的实现关键:在 **render thread** 开 `WndMessage`(通过 `Game.runInRenderThread` 或类似),因为 Wnd 必须在主线程创建。`chooseOption` 要照这个模式。
- `LuaNpc.java`:onInteract 是唯一回调,chooseOption 不需要改 LuaNpc(它是 RPD 全局函数,Lua 脚本在 onInteract 里调 `RPD.chooseOption(...)`)。

**remished chooseOption 用法**(参考 `../remixed-dungeon/scripts/npc/Barman.lua` / `Bishop.lua`):
```lua
local idx = chooseOption(dialog, "标题", "返回", "选项1", "选项2", "选项3")
-- idx 是玩家选的序号(1-based),回调式或同步返回
```
fork 版签名 worker 设计(建议回调式,避免 Lua 同步阻塞等玩家点选):
```lua
RPD.chooseOption(charId, title, options_array, function(choiceIdx) ... end)
-- 或更贴近 remished 的同步返回式(若 fork Wnd 能阻塞 Lua — 需调研可行性)
```
**签名决策由 worker 调研后定**:关键是 fork 的 Wnd 是否能阻塞 Lua 协程拿到同步返回,还是必须回调式。回调式更简单稳妥(不阻塞 Lua),推荐。

**上游 Wnd 资源**(worker 调研复用 vs 新建):
- 上游 `windows/` 下可能有 `WndOptions`(标准选项窗)— 若有则 chooseOption 直接包它;没有则参考 `WndMessage` 结构新建一个最小选项 Wnd。**worker 必须先 grep 上游 `windows/Wnd*.java` 确认 WndOptions 是否存在**。

## Files
预计改/新增(路径相对 worktree 根):
- `core/src/main/java/.../modding/RpdApi.java`(加 `ChooseOption` 内部类 + `rpd.set("chooseOption", new ChooseOption())` 注册)
- 可能新增 `core/src/main/java/.../windows/WndLuaOptions.java`(若上游无 WndOptions 可复用;有则不新建)
- 测试:`core/src/test/.../modding/`(断言 chooseOption 注册存在 + 签名正确 + 非空选项校验逻辑,headless 下可能无法开真 Wnd,测注册 + 参数校验即可)
- 示例验证:升级 `core/src/main/assets/mods/remixed_full/scripts/npcs/barman.lua`(m17a 降级为单线 dialog,M18a 升级为 chooseOption 2-3 选项,验证 API 真正可用)
- 参考(不改):
  - `RpdApi.java` L942 `ShowDialog`(render-thread 开 Wnd 模式)
  - `RpdApi.java` L163-201(NPC API 注册区)
  - remished `../remixed-dungeon/scripts/npc/Barman.lua` / `Bishop.lua`(原 chooseOption 语义)
  - 上游 `core/src/main/java/.../windows/Wnd*.java`(grep WndOptions)

避免改动:
- 不改 `LuaNpc.java`(chooseOption 是 RPD 全局,不在 LuaNpc)
- 不碰 `DataDrivenLevel.java`(那是 m18d 的范围,并行 worktree)
- 不改其他 m17a NPC 脚本(只升级 barman 作示例,其余留给后续)

## Steps
1. **调研上游 Wnd**:grep `core/src/main/java/.../windows/Wnd*.java`,确认 `WndOptions` 是否存在 + 其构造签名(标题/options 数组/回调)。读 `RpdApi.ShowDialog`(L942)掌握 render-thread 开 Wnd 的完整模式。
2. **定签名**:基于调研定 `chooseOption(charId, title, options, onChoice)`(回调式,推荐)或同步式(若 Wnd 能阻塞 Lua)。在 PLAN 本文件"细化"节补最终签名 + 理由。
3. **实现 ChooseOption 内部类** in RpdApi.java:解析 Lua 参数(charId/title/options array/callback)→ 在 render thread 开 Wnd(WndOptions 或新建 WndLuaOptions)→ 玩家选 → 回调 `onChoice(idx)`。
4. **注册**:`rpd.set("chooseOption", new ChooseOption())`(放 L201 giveItem 附近,NPC API 区)。
5. **测试**:headless 下测注册 + 参数校验(空 options/非法 charId 的错误路径),真 Wnd 渲染无法 headless 测(像 ShowDialog 那样只测非渲染逻辑)。
6. **示例**:升级 `barman.lua` 用 `RPD.chooseOption`(2-3 选项,如"买酒/聊天/离开"),验证 API 端到端。更新文件头注释(m17a 降级说明 → M18a 升级)。
7. `./gradlew :core:test` 绿。
8. **codex 评审**:必须 `assign("codex_reviewer", ...)`;若 assign 失败/不可用,**跳过评审并在回报告知 dispatcher 裁决,不要直接调用 codex-cli / codex exec**。

## Acceptance
- [ ] `RPD.chooseOption` 注册成功,Lua 脚本能调,开选项 Wnd,玩家选后回调触发。
- [ ] render-thread 安全(照 ShowDialog 模式,不在 Lua 线程直接开 Wnd)。
- [ ] 不破坏现有 NPC API(npcYell/showDialog/giveItem/leaveTown 不受影响)。
- [ ] barman.lua 升级为 chooseOption 示例,端到端验证 API 可用。
- [ ] `:core:test` 绿。
- [ ] 不改 LuaNpc.java / DataDrivenLevel.java;不提交 `.claude/`;不用 `git add -A`。

## 细化(worker 调研后落定,2026-07-10)

### 调研结论(已坐实)
- **WndOptions 存在**:`core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndOptions.java`。构造 `(String title, String message, String... options)`,按钮回调 `protected void onSelect(int index)`(index **0-based**);`enabled(int)` 可逐项禁用。无需新建 WndLuaOptions。
- **render-thread 模式**:`ShowDialog` 用 `Game.runOnRenderThread(() -> GameScene.show(new WndMessage(body)))`(RpdApi.java:956)。
- **决定性证据**:fork `LuaShopNpc.showShopWindow()`(modding/LuaShopNpc.java:148-191)已实现**完全相同**的模式 —— `Game.runOnRenderThread(new Callback(){ showShopWindow() })` 内 `GameScene.show(new WndOptions(sprite(), shopName, msg, opts){ onSelect→attemptBuy })`。M18a 照此模式即可。
- **Lua callback 回调模式**:`LuaBuff` / `LuaItemCallbacks` 已用 `fn.isfunction()` gate + `fn.call(LuaValue.valueOf(arg))` / `fn.invoke(varargsOf(args))` 派发,失败 swallow + `Gdx.app.error`。
- **luaj 无 FourArgFunction**:classpath 只有 `OneArgFunction`/`TwoArgFunction`/`ThreeArgFunction`/`VarArgFunction`(RpdApi 现有代码最高用到 ThreeArgFunction)。4 参必须用 **VarArgFunction**。

### 最终签名(回调式)
```lua
RPD.chooseOption(charId, title, options, callback)
```
- `charId`: int —— 照 `showDialog` 做"anchor only"校验(resolveChar,任意 live Char 即可,不强制 NPC,与 showDialog 一致、最灵活)。
- `title`: string —— WndOptions 的 title(窗口标题);WndOptions message 传 `""`(空 prompt 行)。
- `options`: Lua **数组 table** of strings,如 `{"买酒","聊天","离开"}`。1-indexed 遍历(同 LuaShopNpc.hydrate)。**空 table / 非 table / 含非 string 元素 → 拒绝(NIL + log)**。
- `callback`: Lua function,**1-based** choice index 入参(贴 remished `chooseOption` 返回 1-based 的习惯;LuaShopNpc 内部用 0-based 是 Java 内部,跨 Lua 边界统一 1-based)。

返回值:总是 `NIL`(异步回调式,无同步返回值)。所有非法入参 + 异常路径 swallow + `Gdx.app.error` + 返回 NIL,不抛(同 RpdApi 全体约定)。

### 实现细节(RpdApi.java)
1. **注册**:`build()` 内 `rpd.set("chooseOption", new ChooseOption())`,放 L167 `showDialog` 注册之后(NPC API 区,紧邻 dialog 原语)。
2. **`ChooseOption extends VarArgFunction`**(需新增 import `org.luaj.vm2.Varargs` + `org.luaj.vm2.lib.VarArgFunction`;codex_reviewer 阶段1 must-fix:必须重写 **`invoke(Varargs args)`** 返回 `Varargs`,**不是** `call(Varargs)` —— javap 确认 `VarArgFunction` 的派发方法是 `invoke`,`call(...)` 系列不经此路由。`LuaValue extends Varargs`,故 `return NIL;` 合法):
   ```java
   private static final class ChooseOption extends VarArgFunction {
       @Override public Varargs invoke(Varargs args) {
           try {
               LuaValue charId = args.arg(1);
               LuaValue title  = args.arg(2);
               LuaValue opts   = args.arg(3);
               LuaValue cb     = args.arg(4);
               Char c = resolveChar(charId);          // anchor only, like showDialog
               if (c == null) return NIL;
               if (!title.isstring()) { error("title"); return NIL; }
               String[] options = parseOptionsTable(opts);  // seam
               if (options == null || options.length == 0) { error("options"); return NIL; }
               if (!cb.isfunction()) { error("callback"); return NIL; }
               final String t = title.optjstring("");
               final LuaImage icon = null;             // 无 per-NPC 图标(MVP)
               final LuaValue callback = cb;
               Game.runOnRenderThread(() -> GameScene.show(new WndOptions(t, "", options) {
                   @Override protected void onSelect(int index) {
                       ChooseOption.dispatchChoice(callback, index);
                   }
               }));
           } catch (Exception e) { Gdx.app.error(TAG, "chooseOption threw", e); }
           return NIL;
       }
   }
   ```
3. **两个 package-private 测试 seam**(放 ChooseOption 类外、RpdApi 内,`static`):
   - `static String[] parseOptionsTable(LuaValue optsVal)`:1-indexed 遍历 table → `String[]`;**任一元素非 string / 非 table → 返回 null**;length 0 → 返回 null(让 call() 统一报"empty")。纯逻辑,headless 可测。
   - `static boolean dispatchChoice(LuaValue callback, int selectedIndex)`:`selectedIndex < 0` → false(玩家取消/点空白时 WndOptions 会传 -1,即 onCancel 路径,不回调);否则 `callback.call(LuaValue.valueOf(selectedIndex + 1))`(1-based),异常 swallow + log。纯逻辑,headless 可测。
   - 为什么抽 seam:`GameScene.show` 在 headless 无场景图,真 Wnd 不可开(同 ShowDialog/LuaShopNpc 无法 headless 测渲染)。把"Lua table→String[]"和"index→Lua 回调"两段纯逻辑抽出,直接单测,绕开渲染依赖(同 `LuaShopNpc.attemptBuy` 的 pure-logic seam 思路)。

### 测试计划(新增 `core/src/test/.../modding/ChooseOptionTest.java`)
- `chooseOptionExposedOnRpdGlobal`:`LuaEngine.init()` 后 `RPD.chooseOption` 非nil(注册存在)。
- `chooseOptionRejectsBadCharIdWithoutThrowing`:非 int charId、未知 charId(99999)→ nil,不抛(同 showDialog 测试形态)。
- `parseOptionsTableValidAndInvalid`(直接调 seam):
  - `{"a","b","c"}` → `["a","b","c"]`(1-indexed 顺序保持)。
  - 含非 string 元素(`{1,"b"}`)→ null。
  - 非 table(string)→ null。
  - 空 table `{}` → null。
- `dispatchChoiceInvokesCallbackOneBased`(直接调 seam):
  - 传一个记录入参的 Lua function(reflection 或 `globals().load` 定义),selectedIndex=0 → 回调收到 **1**;selectedIndex=2 → 收到 **3**。
  - selectedIndex=-1(取消)→ 回调不触发,返回 false。
  - callback=nil/function 缺失 → false。

### 示例(barman.lua 升级)
原 m17a 降级(单线 showDialog)→ M18a 升级为 3 选项 chooseOption:
```lua
onInteract = function(selfId, heroId)
    RPD.chooseOption(selfId, "酒保",
        {"买一杯特酿(暂无货)","聊聊最近的传闻","转身离开"},
        function(choice)
            if choice == 1 then
                RPD.showDialog(selfId, "『今日酒桶见底,改日再来吧。』")
            elseif choice == 2 then
                RPD.showDialog(selfId, "『听说地牢深处又不太平了……保重,旅人。』")
            elseif choice == 3 then
                RPD.npcYell(selfId, "慢走。")
            end
        end)
end
```
更新文件头注释:m17a 降级说明 → M18a 升级说明(chooseOption 已可用;handle 传送仍缺失,保留信息有损说明)。保留台词硬编码 ZH。

## Notes
- 签名决策(回调 vs 同步)是本 feature 最大设计点 —— 调研 fork Wnd 是否能阻塞 Lua 后定。回调式是安全默认。
- M17a 的 barman 原本就是 chooseOption 降级来的,M18a 让它"回归本貌",是最自然的示例验证。
- 这是 M18 批次首个扩 API feature,API 命名/模式会成为 m18b/c/d 的模板,设计要干净(命名贴 remished 习惯 + fork 风格)。
- 并行 m18d 只碰 DataDrivenLevel,零冲突。
