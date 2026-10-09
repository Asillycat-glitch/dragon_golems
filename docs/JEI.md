# JEI 适配

## 一、结论：配方页本来就是本家白送的

| 配方 | JEI 里出现的位置 | 谁生成的 |
|---|---|---|
| 5 条切石机（胚料 → 部件） | 切石机 | 原版 + JEI 自带，不需要我们写 |
| 装配（五部件 → 成品） | 工作台，带本家的部件格扩展 | 本家按**配方类**注册的 `GolemAssemblyExtension` |
| 部件 + 材料 → 带材料的部件 | 铁砧 | 本家 `GolemJEIPlugin`：`GolemPart.LIST` × `GolemMaterialConfig.getAllMaterials()` |
| 成品 + 修复材料 → 满耐久成品 | 铁砧 | 同上（遍历所有 `GolemType`，含龙） |
| 成品 + 升级物品 | 铁砧 | 同上（`UpgradeItem.LIST` × 所有 `GolemType`，含龙） |

依据（对着 `modulargolems-2.7.3.jar` 与 sources 核过）：

- `dev.xkmc.modulargolems.compat.jei.GolemJEIPlugin#registerRecipes` →
  `addPartCraftRecipes` / `addRepairRecipes` / `addUpgradeRecipes` → `registration.addRecipes(RecipeTypes.ANVIL, ...)`
- 同一类的 `registerVanillaCategoryExtensions` →
  `getCraftingCategory().addCategoryExtension(GolemAssembleRecipe.class, GolemAssemblyExtension::new)`
  （按**类**注册，所以任何 mod 的 `golem_assemble` 配方都吃到这套展开逻辑）
- `GolemPart.LIST` 是全局静态列表，`GolemPart` 构造时就把自己加进去；我们的 5 个部件走的是同一个构造函数
- `GolemMaterialConfig.getAllMaterials()` 合并所有数据包里 `modulargolems_config/materials/*.json` 的材料，
  我们的 `data/dragon_golems/modulargolems_config/materials/dragon.json` 在里面
- 装配配方为什么算"工作台"：l2library 的 `AbstractShapedRecipe extends ShapedRecipe`，
  `getType()` 就是 `minecraft:crafting`

**所以部件的铁砧页连"要几个材料"都是自动算对的**（本家用 `GolemPart.count` 生成材料数量，
五个部件现在都是 64 = 一组）。

## 二、我们加了什么

`compat/jei/DragonGolemsJeiPlugin`（JEI 可选，缺了也不影响游戏）只补本家没有的东西——
给胚料、五个部件、成品加 JEI 信息页（`IRecipeRegistration#addItemStackInfo`）：

- 胚料：怎么在切石机里切成五个部件。
- 部件：铁砧上怎么铸材料、一次要给够几个、本模组材料只允许装在龙部件上、属性公式、本部件的权重。
- 成品：装配图案 `H / LBR / G`、装备界面是犬型两格、放出来离地 3 格悬停。

一个容易踩的坑：**部件的说明页要按材料变体登记**。本家给 `GolemPart.LIST` 全体注册了 subtype 解释器
（subtype = 材料 id），只登记"裸部件"的话，点"已经铸进铁的龙头"是翻不到说明的。
所以插件里对每个部件都登记了 `裸部件 + 每种材料一份`。

## 三、两个"看着多余"其实是正常的

1. **本家的铁砧页会把所有材料 × 所有部件都铺开**，所以我们的 5 种材料也会出现在本家傀儡
   （金属 / 类人 / 犬）的部件页上，但真拿去做会被 `partLimitation` 拦下（本家生成 JEI 页时并不查
   partLimitation，而 JEI 没有"删掉某页"的接口）。
   `modulargolems:special_crafting_material` 这个标签确实能让本家跳过生成，但它在
   `CraftEventListeners.onAnvilCraft` 里同时参与玩法判断（进这个标签的材料在铁砧上直接不能用），
   **不要**拿它当 JEI 开关。
2. **同一个部件会出现"铁锭版"和"铁块版"两页、外观一模一样**：因为本家在 `vanilla.json` 里定义的
   `modulargolems:iron` 用的是铁锭、数值与我们 `dragon_golems:iron`（铁块）完全相同，而本家材料
   没有 partLimitation，所以两种都能铸进龙部件。这是设计取舍（龙专属材料只锁部件、不锁数值），不是 bug。

## 四、以后想扩展

- 本家在 `GolemJEIPlugin#registerRecipes` 末尾往 Forge 事件总线发了一个 `CustomRecipeEvent`，
  里面带着 `IRecipeRegistration`。想通过本家的注册流程补铁砧页，订阅它即可。
- 想给装备界面加 JEI 的"幽灵槽/拖拽"支持，就在 `registerGuiHandlers` 里对
  `EquipmentsScreen` 注册 `IGuiHandlerRegistration`（本家自己也是这么接的）。
