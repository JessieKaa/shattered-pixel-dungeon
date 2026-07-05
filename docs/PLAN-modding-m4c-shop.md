# PLAN: M4c — 多店经济(Lua 商店 NPC)

## Goal

基于 m4a SafeZone 加 **Lua 商店 NPC**:Lua 定义物品池 + 价格,interact 开购买窗口,玩家花金币买物品。MVP 只做**买**(sell 留后续)。上游目标 0 改动。

## Context

m4a(DataDrivenLevel + SafeZone)+ m4b(Lua NPC 对话)已完成。SafeZone 现在能进、能对话,但无经济。M4c 加商店:Lua NPC 携带物品池,interact 弹购买窗口,扣 `Dungeon.gold` 给物品。

参考 SPD 商店机制:
- `Shopkeeper.java`:static `sellPrice(Item)`(:201)/ static `sell()` 返回 `WndBag`(:205)/ `interact(Char)`(:238)/ `buybackItems` 回购列表(:71)
- `ImpShopkeeper.java:29` extends Shopkeeper(NPC 商店范例)
- SPD 商店模式依赖 **ShopRoom**(hero 进入 ShopRoom 后,点物品触发 sell/buy UI)。**SafeZone 不是 ShopRoom**,所以不能直接复用 ShopRoom 模式 → MVP 走自定义窗口。

**选型(worker 调研后定)**:
- **选项 A**:LuaShopNpc interact → 自定义 `WndOptions` 列物品 + 价格,选了扣金币给物品(最简,不依赖 ShopRoom,推荐 MVP)
- 选项 B:LuaShopNpc interact → 复用 `WndBag`(SPD 物品选择窗口)+ 自定义价格逻辑(中等)
- 选项 C:把 SafeZone 的某房间标 ShopRoom(改 DataDrivenLevel,复杂,可能破坏 m4a 简洁性,不推荐)

**建议选 A**(MVP 自定义 WndOptions),卖/回购/复杂 UI 留后续。

## 选型决策(worker 调研后定:**A**)

A(自定义 WndOptions)最贴合 m4a SafeZone(非 ShopRoom)+ m4b LuaNpc 范式。卖/回购/复杂 UI 留后续。

**关键设计决策**(worker 调研后落定):

1. **LuaShopNpc extends LuaNpc**(继承无敌 5 件套 + Bundle 范式),override `interact` 走自定义购买窗口(不调 `dispatchOnInteract`——那是 dialog 路径;商店直接 Java 处理)。
2. **购买逻辑内聚到 LuaShopNpc**,**不扩展 RpdApi**(PLAN 原 §Files 列了 `RpdApi.buyItem`,调研后发现:onSelect 在 Java 里、不需 Lua 反向调用,塞进 RpdApi 反而让 RpdApi 耦合 shopId 概念。未来若 Lua 需驱动购买再加)。偏离原 PLAN 的 RpdApi 改动一行,在此说明。
3. **物品 id → Item 实例**:MVP 白名单 map(PLAN §R2),`createItem(id)` 返回 `Item` 或 null(白名单外 skip+log)。沿用上游 `Reflection.newInstance` 不适用(类名≠Lua id),用显式 supplier map。
4. **数量管理(三态语义,codex must-fix 后统一)**:
   - `quantity` **缺省**或 `< 0` → **无限**(SPD 商店主流量是无限,匹配)
   - `quantity == 0` → **sold out**(不可购买,UI 灰显 + attemptBuy 拒绝)
   - `quantity > 0` → **有限库存**(每次买减 1,减到 0 转 sold out)
   - hydrate 时缺省 quantity 写入 `-1`(无限标志);Lua 显式写 0 = 故意上架即售罄(测试用)
5. **金币不足**:参考 Shopkeeper.interact 的 `enabled(index)` 模式——金币不够的物品 disable 灰显,onSelect 仍守卫(防外部触发)。购买消耗 `Dungeon.gold -= price`,**不动 `Statistics.goldCollected`**(它是"累计收集"统计,购买消耗不属于收集;Shopkeeper buyback 减它是另一回事)。

## Files

- `core/.../modding/LuaShopNpc.java`(新,extends LuaNpc)— 携带 `shopId` + 物品池(`List<ShopEntry>`)+ interact 弹购买 WndOptions + `attemptBuy` pure-logic seam
- `core/.../modding/LuaNpc.java`(改,仅 1 处)— `resolveSprite` 由 `private` 改 `protected static`,供 LuaShopNpc 复用;**行为零变化**(fallback 仍 RatKingSprite)。modding/ 内,非上游。
- `core/.../modding/LuaShopRegistry.java`(新)— 商店定义 Registry(id → LuaTable),1:1 对照 `LuaNpcRegistry`
- `core/.../modding/LuaEngine.java`(改)— `register_shop` 全局 + `loadShopScripts()` 加载 `scripts/shops/*.lua`(对照 `loadNpcScripts`)
- `core/.../modding/DataDrivenLevel.java`(改)— `createMobs` 加 `lua_shop:` prefix 分支(对照 `lua_npc:`)
- `core/src/test/.../modding/LuaShopTest.java`(新)— Registry + hydrate + 白名单 + buyable 决策 + createMobs 分支 + bundle round-trip
- `assets/scripts/shops/test_shop.lua`(新)— 测试商店(id + 几个物品 + 价格 + quantity,含无限/有限/sold-out 各一)
- `assets/mods/levels/test_safezone.json`(改)— mobs 加 `{"type":"lua_shop:test_shop","pos":<某格>}`
- **上游改动**:**0**(NPC 交互走 LuaShopNpc override,不动 Shopkeeper/ShopRoom/NPC.java)
- **RpdApi.java**:**不改**(见决策 2)

## Steps

### 1. 调研(已完成)

已核对(见选型决策 + 代码引用):
- `Shopkeeper.java:238` interact 模式:`c != Dungeon.hero` guard → `Game.runOnRenderThread(Callback)` → `WndOptions` 子类(`onSelect/enabled/hasIcon/getIcon`)+ `CurrencyIndicator.showGold`
- `Shopkeeper.java:267-274` buyback 扣金币:`Dungeon.gold -= price` + `Statistics.goldCollected -= price` + `item.doPickUp(Dungeon.hero)` 失败回退 `Dungeon.level.drop`
- `Item.java:121` `final boolean doPickUp(Hero)` → 内部 `collect(hero.belongings.backpack)`
- `Dungeon.gold` `public static int`(直接 `Dungeon.gold -= price`)
- `LuaNpc.java` / `LuaNpcRegistry.java`:继承范式(NPC + RatKing 无敌 5 件套 + `lua_npc_id` Bundle + `hydrate(LuaTable)` re-hydrate 模式)
- `LuaEngine`:`register_<type>` 全局 + `loadScriptsFrom(dir, label, registrySize)` 通用加载器
- `DataDrivenLevel.createMobs:207`:`lua_npc:` prefix 分支(对照加 `lua_shop:`)
- `LuaNpcTest`:headless harness 范式(`HeadlessApplication` + `LuaSandbox.exposedGlobals` + pure-logic seam 测试 + WndOptions 渲染靠 desktop run)

### 2. LuaShopNpc(extends LuaNpc)

字段 + Bundle key `lua_shop_id`:
```java
public class LuaShopNpc extends LuaNpc {
    private static final String LUA_SHOP_ID = "lua_shop_id";
    private String luaShopId;
    private String shopName = "???";
    private final List<ShopEntry> entries = new ArrayList<>();
    // ShopEntry: { String itemId; int price; int quantity; } — quantity<0 无限 / ==0 sold out / >0 有限
    // 无参 ctor(reflection,restore 用)+ LuaShopNpc(LuaTable) ctor → 调 super()(无参,**绕过 LuaNpc.hydrate**,不写 luaNpcId),然后 own hydrate
    // hydrate(tbl): shopId=tbl.id; shopName=tbl.name; spriteClass=LuaNpc.resolveSprite(tbl.get("sprite").optjstring("shopkeeper")); 解析 tbl.items 成 ShopEntry;
    //   每个 item: itemId=tbl.id(string), price=tbl.price(int>=0), quantity=tbl.quantity 缺省则 -1(无限)
    // codex round-2 must-fix:spriteClass 必须显式设(Mob.sprite 用 Reflection.newInstance(spriteClass),null 会崩)。
    //   LuaNpc.resolveSprite 由 private 改 protected static 供子类复用(其默认 fallback RatKingSprite 不变;
    //   LuaShopNpc 传缺省"shopkeeper" → ShopkeeperSprite.class,契合商店主题;未知值仍 fallback RatKing 不 crash)。
}
```
- **不调** `super.interact`(LuaNpc.interact 走 dialog dispatch)。完全 override:
  - `sprite.turnTo(pos, c.pos)` → hero guard(`c != Dungeon.hero` return)→ `Game.runOnRenderThread` → `GameScene.show(new WndOptions(...){...})`
  - WndOptions 选项:`" itemName — price金"`(无限)或 `" itemName — price金 (n)"`(剩余);金币不够时 `enabled(i)=false`
  - `onSelect(index)`:`attemptBuy(index)`(纯 Java,不走 Lua)
- **`attemptBuy(int index)` 购买核心**(headless 可测):
  ```java
  boolean attemptBuy(int index) {
      if (index<0 || index>=entries.size()) return false;
      ShopEntry e = entries.get(index);
      if (e.quantity == 0) { GLog.w(soldOut); return false; }            // sold out
      if (Dungeon.gold < e.price) { GLog.w(tooPoor); return false; }     // 不足
      Item item = LuaShopItems.create(e.itemId);                         // 白名单
      if (item == null) { GLog.w(unknownItem); return false; }
      Dungeon.gold -= e.price;                                           // 扣金币(不动 Statistics)
      if (e.quantity > 0) e.quantity -= 1;                               // 有限才减
      Hero hero = Dungeon.hero;                                          // headless 下 null
      if (hero != null) {
          if (!item.doPickUp(hero)) Dungeon.level.drop(item, hero.pos);  // 给物品
      }
      GLog.i(boughtMsg);
      return true;
  }
  ```
  - hero==null 时跳过 doPickUp(headless 测试只验 gold 扣减 + quantity 减 + item 实例化正确,物品获得靠 desktop run 验证——同 m4b split)
- **Bundle**:`storeInBundle` 写 `lua_shop_id`;`restoreFromBundle` 读后从 `LuaShopRegistry.getTable` re-hydrate(沿用 LuaNpc 模式;注意:既然 extends LuaNpc,super.storeInBundle 会写 `lua_npc_id`,我们写自己的 `lua_shop_id`,restore 时按 `lua_shop_id` 优先)。**简化**:不调 `super.storeInBundle` 的 LuaNpc 那套(LuaNpc.storeInBundle 写 lua_npc_id,我们覆盖整个 store/restore 用 lua_shop_id),但保留 `super.super`(NPC/Char)链路 —— 即调 `super.super.storeInBundle` 不可行,改为 `LuaShopNpc` 直接 override storeInBundle 调 `NPC.super`(即 `super` 是 LuaNpc,我们手动跳过 LuaNpc 的实现,直接走 Char/Mob)。**实际**:LuaNpc.storeInBundle 调 `super.storeInBundle(bundle)` + 写 lua_npc_id;我们 override 它,先调 `super.super.storeInBundle` 不可行(Java 无 super.super)。**解法**:`LuaShopNpc.storeInBundle` 调 `super.storeInBundle(bundle)`(会写 lua_npc_id=shopId 或 null)+ 额外写 lua_shop_id;restore 优先读 lua_shop_id。为避免 lua_npc_id 污染,LuaShopNpc 不复用 luaNpcId 字段(留 null),只用 luaShopId。LuaNpc.storeInBundle 的 `if (luaNpcId != null)` 守卫保证不写空 key。✓

### 3. LuaShopItems 白名单(MVP)

`core/.../modding/LuaShopItems.java`(新)或合并进 LuaShopNpc 静态内部。MVP 白名单(supplier map):
```java
static Item create(String id) {
    switch (id) {
        case "potion_of_healing": return new PotionOfHealing();
        case "potion_of_strength": return new PotionOfStrength();
        case "scroll_of_identify": return new ScrollOfIdentify();
        case "scroll_of_magic_mapping": return new ScrollOfMagicMapping();
        case "scroll_of_remove_curse": return new ScrollOfRemoveCurse();
        case "small_ration": return new SmallRation();
        case "ration": return new Food();
        case "berry": return new Berry();
        default: Gdx.app.error(TAG, "unknown shop item id: " + id); return null;
    }
}
```
- 选这些因 SafeZone 主题(消耗品/常见)+ 已确认类路径(`items/potions/PotionOfHealing.java` 等)。白名单外 skip+log(不 crash)。

### 4. LuaShopRegistry(1:1 对照 LuaNpcRegistry)

```java
public final class LuaShopRegistry {
    private static final Map<String, LuaTable> shops = new HashMap<>();
    static void register(String id, LuaTable t);
    static LuaTable getTable(String id);
    static LuaShopNpc create(String id);   // null if missing
    static boolean contains(String id);
    static Set<String> ids();
    static int size();
    static void clear();                   // test helper
}
```

### 5. LuaEngine:register_shop + loadShopScripts

- `SHOPS_DIR = "scripts/shops"`
- `globals.set("register_shop", new RegisterShopFunction())` — 验证 `id`+`name`+`items`(items 是 table,每个含 `id`+`price`,`quantity` 可选);validate 每个 item 的 id(string)+price(int>=0)
- `loadShopScripts()` 在 initInternal() 末尾调

### 6. DataDrivenLevel.createMobs:`lua_shop:` prefix

对照 `lua_npc:` 分支(`DataDrivenLevel.java:207-221`),在它**之后**加:
```java
if (spec.type != null && spec.type.startsWith(LUA_SHOP_PREFIX)) {
    String shopId = spec.type.substring(LUA_SHOP_PREFIX.length());
    LuaShopNpc shop = LuaShopRegistry.create(shopId);
    if (shop == null) { error+skip; }
    if (pos invalid || !passable[pos]) { error+skip; }
    shop.pos = spec.pos;
    mobs.add(shop);
    continue;
}
```
- `LUA_SHOP_PREFIX = "lua_shop:"`
- 注意 prefix 顺序:`lua_shop:` 必须在 `lua_npc:` 之后检查(不冲突,前缀不同)

### 7. test_shop.lua + test_safezone.json

- `scripts/shops/test_shop.lua`:`register_shop { id="test_shop", name="测试商店", sprite="shopkeeper", items={ {id="potion_of_healing", price=50, quantity=2}, {id="small_ration", price=15}, -- quantity 缺省=无限 {id="berry", price=10, quantity=0} -- sold out 测试 } }`
- `test_safezone.json` mobs 加 `{"type":"lua_shop:test_shop","pos":120}`(120 是空地格,需核对 passable)

### 8. 测试(LuaShopTest,headless)

沿用 `LuaNpcTest` harness。覆盖:
- Registry contract(register/getTable/create/contains/size/clear/unknown→null)
- hydrate:解析 shopName + items(itemId/price/quantity;无限=sold-out 检查)
- 白名单:已知 id→非 null Item;未知 id→null(不抛)
- **`attemptBuy` pure-logic 决策**(headless 核心,Dungeon.hero==null):
  - 金币够 → true,`Dungeon.gold` 减 price,有限 quantity 减 1,无限 quantity 不变
  - 金币不够 → false,`Dungeon.gold` 不变
  - sold out(quantity==0)→ false
  - invalid index → false
  - 未知 itemId → false,`Dungeon.gold` 不变
- `register_shop` 全局验证(id/name/items 必填,缺字段 reject)
- shipped `test_shop.lua` 经 `LuaEngine.init` 注册成功
- `DataDrivenLevel.createMobs` `lua_shop:<id>` 分支:已知 id 落一个 LuaShopNpc;未知 id skip
- Bundle round-trip:`lua_shop_id` 持久化 + 从 registry re-hydrate shopName
- M1 sandbox 回归(register_shop 注入后 luajava 仍不可达)

### 9. 回归验证

- `./gradlew :core:test` 全绿(含新 LuaShopTest + 既有 LuaNpcTest 等)
- `./gradlew :desktop:debug` 编译过
- debug 桌面进 SafeZone(沿用 m4a 入口):走近 LuaShopNpc interact,看物品列表 + 价格;买一个 → 扣金币 + 得物品;sold out 灰显;金币不够 disable
- 原版一周目可正常开局(C3:`modding/` 外 0 改动,`DataDrivenLevel` 只在 SafeZone 走)
- 离开 SafeZone 回主线无污染(m4a isEphemeral)

## Acceptance

- ✅ SafeZone 里能和 LuaShopNpc 交互,弹购买窗口(WndOptions 列物品+价格)
- ✅ 买物品:扣 `Dungeon.gold` + hero 获得物品 + 数量管理
- ✅ 金币不足时拒绝交易 + GLog 警告
- ✅ 商店物品池/价格由 Lua 定义
- ✅ 原版一周目可正常开局(C3)
- ✅ 离开 SafeZone 回主线无污染
- ✅ ≥1 单元测试:LuaShopRegistry round-trip + buyItem 路由(金币扣减 + 物品获得 + 不足拒绝)
- ✅ modding/ 子包,C2 隔离
- ✅ **上游改动 0**(不动 Shopkeeper/ShopRoom/NPC.java)
- ✅ codex_reviewer APPROVED

## 风险 + 注意

- **R1: 商店窗口 UI**。SPD WndOptions 选项数有限(屏幕容量)。物品池大时需分页或滚动。MVP 限制物品数 ≤ 6(WndOptions 一屏),多物品留后续。
- **R2: 物品实例化**。Lua 定义 `item.id` → Java Item。SPD 物品是 `Generator.Category` 或直接 `new PotionOfHealing()`。用 Reflection 或白名单映射(item id → Item class)。MVP 白名单(常用物品:治疗药水/面包/卷轴/铁钉等),白名单外 skip。
- **R3: 金币/物品持久化**。SafeZone isEphemeral 不存档,但购买后 hero 获得的物品进 hero.belongings(随 hero 存档)。这是正确行为(买的物品带回主线)。`Dungeon.gold` 扣减也随 hero 存档。✓
- **R4: 数量三态语义**。`quantity` 缺省或 `<0` = 无限(每次买都扣金币给物品,不减库存);`==0` = sold out(不可买);`>0` = 有限(扣库存,到 0 转 sold out)。三态在 hydrate/register_shop 校验/UI enabled/attemptBuy/测试里全程一致(codex must-fix 已对齐)。
- **R5: 卖物品**。MVP 不做(只买)。卖需要 hero 背包选择 UI(WndBag)+ sellPrice 逻辑,留后续 feature。
- **R6: C5 proguard**。LuaShopNpc 走反射 Bundle,`modding.**` keep 已覆盖(m4a/m4b 已加)。

## 参考

- SPD `Shopkeeper.java:201/205/238`(sellPrice/sell/interact)
- SPD `ImpShopkeeper.java:29`(NPC 商店范例)
- SPD `WndOptions`(选项窗口)/ `Item.value():548`(价格)/ `Dungeon.gold`/`Gold`
- m4b `LuaNpc.java` + `LuaNpcRegistry.java`(继承/参考范式)
- m4a `DataDrivenLevel.createMobs`(prefix 分支扩展,`lua_npc:` → `lua_shop:`)
- modding 范式:Registry + hydrate + lua_<type>_id / charId + Actor.findById / 窄 RPD API / modding/ 子包(C2)/ 上游 0 hook / proguard keep(C5)
- 约束 C1-C5 + CLAUDE.md
