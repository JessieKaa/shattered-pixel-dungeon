# PLAN: M19f — Alchemy recipes portability / minimal import

## Goal
调研 remixed `alchemy_recipes.json` / `alchemy_recipes.lua` 与 fork 当前 modding 能力,尽可能导入最小可用 recipe 内容;若缺引擎 hook,产出精确 portability assessment 和测试保护,为后续炼金 API milestone 定范围。

## Context
M17 之后 mob/item/spell/buff/NPC/level 都有落地路径,alchemy 是未系统处理的 gap。remixed 有 `scripts/alchemy_recipes.json` 和 `alchemy_recipes.lua`,但 fork 是否已有 recipe 注册/炼金 hook 未确认。本 feature 可以是“最小导入”或“不可导入但完成阻塞清单”,以真实代码为准。

## Research findings (M19f)
1. **fork 现有 alchemy 系统**: `core/.../scenes/AlchemyScene.java` + `items/Recipe.java`。
   - `Recipe.findRecipes(ArrayList<Item>)` 按固定长度 1/2/3 扫描静态数组 (`oneIngredientRecipes` / `twoIngredientRecipes` / `threeIngredientRecipes`)。
   - 每个 recipe 是 Java 类,实现 `testIngredients/cost/brew/sampleOutput`。
   - 没有运行时注册 hook,也不支持 JSON/Lua 动态 recipe。
2. **remixed 系统**: `RemixedDungeon/.../alchemy/AlchemyRecipes.java`。
   - JSON 格式 `{recipes:[{input:[{name,count}],outputs:[{name,count}]}]}`。
   - Lua API `RPD.AlchemyRecipes.registerRecipeFromLua(inputList, outputNameOrList)`。
   - 支持 item/mob/carcass 作为输入/输出,并有 validation/lookup/可用 recipe 查询。
3. **fork modding 现状**: `LuaEngine` 只暴露 `register_item/mob/ally/hero/spell/npc/shop/buff/talent/painter/trap` + `RPD.*`。
   - 无 `register_alchemy_recipe`,RPD 中无 `AlchemyRecipes`。
   - remixed 依赖的 `ItemFactory.itemByName` / `MobFactory.mobByName` / `Carcass` 等机制 fork 没有。
4. **结论**: 当前属于 **API-blocked**,不具备“最小导入 2-3 个 recipe”的引擎 hook。

## Scope of this feature
- **不做**大炼金系统重构。
- **做**精确阻塞清单:
  - 新增 `core/src/test/.../modding/AlchemyRecipePortabilityTest.java`,断言当前无 recipe registry、无 RPD.AlchemyRecipes、remixed JSON 格式不被误加载。
  - 更新 `docs/PLAN-m19f-alchemy-recipes.md` 记录阻塞清单和后续 M20 API 建议。
- 若发现可轻量接入的隐藏 hook,则按 acceptance 改 recipe 文件;否则保持测试保护。

## Files
- `docs/PLAN-m19f-alchemy-recipes.md` (本文件,持续更新)
- 新增 `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/modding/AlchemyRecipePortabilityTest.java`
- 不修改 Java 生产代码(无 hook,硬改 Recipe/AlchemyScene 会超出本 feature 范围)

## Steps
1. 复核 `Recipe.java`、`AlchemyScene.java`,确认无动态注册 hook。
2. 阅读 remixed `AlchemyRecipes.java`/`AlchemyRecipe.java`/`alchemy_recipes.json`/`alchemy_recipes.lua`,列出 JSON/Lua 依赖的 API。
3. 在 `LuaEngine`/`RpdApi` 确认无 alchemy registry 后,写 `AlchemyRecipePortabilityTest`:
   - 初始化 headless libgdx + 重置 mod/Lua 状态。
   - 启用 `remixed_full`,调用 `LuaEngine.init()`。
   - 断言 `LuaItemRegistry`/`LuaMobRegistry` 等正常加载(回归基线)。
   - 断言不存在 RPD.AlchemyRecipes(通过 Lua `RPD.get("AlchemyRecipes")` 或反射确认)。
   - 断言 `Recipe.findRecipes` 仍只返回静态内置 recipe(用已知的 Potion.SeedToPotion 输入验证)。
4. 运行 `./gradlew :core:test --tests '*AlchemyRecipePortabilityTest*'` 绿。
5. 全量 `./gradlew :core:test` 绿(或仅已知 flaky GeneratorLuaItemTest 失败,按 memory 判定)。
6. `git add` 测试文件 + PLAN,`git commit`。
7. codex_reviewer 评审(阶段 2)。

## Acceptance
- [x] 明确结论:可导入最小 recipe 或 API-blocked。→ **API-blocked**
- [ ] 若 blocked:输出精确缺失 API/hook 清单,不做半成品。
- [ ] 新增 `AlchemyRecipePortabilityTest` 并通过。
- [ ] 不提交 `.claude/`;不用 `git add -A`。
- [ ] `./gradlew :core:test` 绿(flaky 测试按 memory 处理)。
