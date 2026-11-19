# 不清不楚 / 已知边界（2026-11-14 清理后）

> **这份清单管什么**：代码当前事实里仍然“看起来像 bug / 看不出是不是故意”的地方，
> 以及文档里仍然需要读者自己判断的边界。
>
> 2026-11-14 已经做过的清理：
>
> - `DragonDebug.RIDE / RENDER / CONFIG` 全部改为 `false`；
> - `pathfinding.pathLog` 默认改为 `false`（`flightLog` 本来就是 false）；
> - README、RENDERING、JEI、MODEL、CONFIG、MOUNT 按当前 0.6 / 2.7.5 代码更新；
> - 旧 `AUDIT-MG-ADAPTATION.md` 已加“历史文档”横幅；
> - 龙甲/装备槽/巡逻等已经落地的部分，不再在文档里写“还没做”。
>
> 证据优先级：**当前代码 > 数据/资源 > README/docs > 注释**。行号以 2026-11-14 工作区为准。

---

## 一、仍然会咬人的语义

### 1. `isFreeFlight()` 有两个不同含义

- `DragonFlightAssist.isFreeFlight()`：玩家按 H 打开的“自由飞行”开关。
- `DragonGolemEntity.isFreeFlight()`：直接返回 `noPhysics`。

`noPhysics` 还会被以下状态打开：

- 洞穴幽灵（`isCaveGhostActive()`）；
- 自动脱困 / 嵌墙救援（`DragonFlightAssist`）；
- 常驻幽灵（`stickyGhost`）；
- `setFreeFlight(true)`。

所以 `DragonRiderKeys` 里按 H 时读到的 `isFreeFlight()`，并不等于“玩家开关”；
自动幽灵会被当成“玩家开了自由飞行”。`isGhostFlight()` 又是 `noPhysics` 的另一个同义方法，
两个名字看起来有区别，实际没有。

`setFreeFlight(false)` 还会直接把 `noPhysics` 写成 false（除非正在脱困），
可能把洞穴幽灵/常驻幽灵临时关掉一拍，下一 tick 再被 `FlightAssist` 打开。

**当前结论**：这是“客户端需要同步字段”与“玩家开关”混在一起的遗留；不是文档能解决的，
要么加一个单独的同步 flag，要么把 `isFreeFlight()` 改名成 `isNoClip()` 之类的语义。

### 2. 龙甲堆叠（已修）

`DragonGolemItems.armor(...)` 已经显式 `p.stacksTo(1)`（[DragonGolemItems.java:147-150](src/main/java/a_silly_cat/dragon_golems/dragon/DragonGolemItems.java#L147-L150)），
和本家大型傀儡甲一致；旧版“默认 64 个一组”的结论已不成立。

### 3. 两个配置项是“死/半死”的

| 配置 | 当前事实 |
|---|---|
| `breath.damageMultiplier` | `DragonGolemConfig.breathDamageMultiplier()` 有 getter，但**没有任何调用点**；改它不影响龙息伤害 |
| `cave.guidedDamage` | getter 硬编码 `return false`；`cave.guideRange` / `guideYTolerance` 只在 `if (caveGuidedDamage())` 分支里用 → 整个“引导式喷息”分支实际不可达 |

**当前结论**：要么接回代码，要么删掉配置项/分支；现在文档已标注“不生效”，但代码里仍是死配置。

### 4. 龙甲数值是“每件”，不是“整套”

`DragonArmorMaterial` 的护甲/韧性/击退抗性都是**单件**数值；四件合计是单件的四倍。
例如下界合金每件 `knockbackResistance = 0.2`，四件合计 0.8。
代码注释已经写清，但 README/旧文档里“数值对齐本家大型傀儡甲”容易被读成整套数值。

**当前结论**：已加注释，不再算“不清不楚”；如果设计想要“整套 0.2”，需要改代码。

### 5. GUI 预览阈值与 `GolemPreviewUtil` 的除法

`DragonGolemRenderer.GUI_PREVIEW_POSE_SCALE = 10`，判断“姿势栈缩放 > 10 才是 GUI 预览”。
但 2.7.5 的 `GolemPreviewUtil` 会把 `scale` 除以 `golem.getScale()`：

- 普通体型：18 / 1 = 18 > 10，会额外缩；
- 泰坦 4 倍：18 / 4 = 4.5 < 10，不会额外缩，最终总缩放仍是 18，预览偏大。

**当前结论**：阈值逻辑对泰坦体型不成立；未改行为，记录为已知边界。

---

## 二、代码里仍然“看着可疑”的边界

| 位置 | 当前事实 |
|---|---|
| `DragonCapeLayer.hiddenFromLocalCamera()` | 用 `mc.getCameraEntity() == dragon` 判第一人称；实际相机实体是玩家，基本永远 false。真正隐藏披风的是 `render` 开头的 `hasPassenger` 检查（对本地乘客一律不画，不区分视角）。 |
| `DragonGolemMenuControl.getSlotDefinition()` | 仍返回 `EquipmentsMenu.DOG_SLOTS`（2 格），但实际菜单由 `fillMenu()` 注册 7 格；2.7.5 的 `GolemMenuControl` 已把该方法标 `@Deprecated`，整包没有调用点。 |
| `DragonPatrolGoal.ready()` | 先判断 `getMode() == ROUTE`，再判断 `isFreeWander()`；后者必然 false，属于死条件。 |
| `DragonGolemEntity.isNetherBody()` | `public`，全仓库无调用。 |
| `DragonGolemEntity.hasMaterial(ResourceLocation)` | `private`，全仓库无调用。 |
| `DragonIdleGoal.groundHeight(double,double)` | `private`，全仓库无调用。 |
| `DragonRiderControl.groundHeight(DragonGolemEntity)` | `private`，全仓库无调用。 |
| `DragonGolemEntity.ATTACK_DIAGNOSTIC` | `false`，`diagnoseAttack()` 默认永远不跑。 |
| `DragonArmorItem.material()` / `armorTexture()` | 只有定义，没有内部调用点（可能想作为外部 API，但文档没写）。 |
| `DragonGolemConfig.java.bak` | 旧配置备份仍在 `src/main/java`；`.gitignore` 忽略 `*.bak`，但工作区存在。 |
| `DragonArmorLayer` | 只认 `DragonArmorItem`；第三方 `GolemModelItem` 龙甲能穿但不会被这一层画出来。 |
| `DragonGolemRenderer` 的 GUI 预览 | 见第一节第 5 条。 |

---

## 三、已经清理掉/归档的（不要再当现状）

| 原问题 | 现状 |
|---|---|
| `DragonDebug` 三个开关全开 | 已全部改为 `false`；`pathfinding.pathLog` 也改为 false |
| README 依赖 ≥2.7.3 / “7 条配方” / “犬型两格” / “浮窗 2×3” | 已改为 2.7.5 / 21 条 / 七格 / 2×4 |
| 龙甲“还没做”的四处注释 | `DragonGolemMenuControl`、`DragonGolemEquipHandler`、`DragonArmorMaterial`、`docs/MODEL.md` 已改 |
| `DragonGolemType` “不额外覆盖升级槽” | 已改成“读配置，默认 5” |
| `DragonGolemItems` “四个部件” | 已改成“五个部件 + 龙铠” |
| `.sized(2.4F, 2.6F)` | 已改为实际的 `.sized(2.6F, 2.2F)` |
| `DragonGolemModel` / `docs/MODEL.md` 引用 `partUp()` | 已改成 `rotateAndLift()` / `placePart()` |
| `DragonArmorModel` 注释 8/2 像素 | 已改成实际的 6/0 像素 |
| `DragonCapeLayer` “第一人称不画” | 已改成“本地乘客一律不画；hiddenFromLocalCamera 基本不命中” |
| `DragonArmorMaterial` 写“原版护甲 2/6/5/2” | 已改成“对齐本家大型傀儡甲，不是原版” |
| `breath.diminishing` 文档写 true | 已改成 false（代码默认） |
| `cave.dash` 注释“默认开” | 已改成“默认关” |
| `cave.guidedDamage` 注释“默认 true” | 已改成“getter 固定 false，改配置不生效” |
| README/JEI/RENDERING/MODEL 旧版本/旧图案/旧槽位 | 已按当前代码更新 |
| 旧 `AUDIT-MG-ADAPTATION.md` | 已加“历史文档”横幅；正文仍是 2.7.3/2.7.5 混合，作为历史记录保留 |

---

## 四、结构 / 单一事实源

这些不是“错”，但决定了以后还会不会继续出现同类问题：

1. **`DragonGolemEntity` 仍然是 4,226 行的 god class**：AI、悬停、骑乘、子碰撞箱、水晶、
   护甲位置、渲染常量、日志全在一起。
2. **`MODEL_SCALE = 1.7` / `MODEL_LIFT = 1.36` 同时定义在 `DragonGolemModel` 和
   `DragonGolemEntity`**，注释都要求同步；`DragonArmorModel` / docs 也引用这些数字。
3. **多套移动/寻路/noPhysics**：
   - 7 个 goal / helper 会写 `setDiveVelocity`；
   - `DragonPathFollower` 自己跑 `PathFinder`，`DragonFlyingNavigation` 也是 `PathNavigation`，
     `DragonFlyNodeEvaluator` 两边共用；
   - `noPhysics` 有 4 处直接写，`DragonFlightAssist` 里却写着“唯一写入口”。
4. **`isFreeFlight` / `isGhostFlight` / `isCaveGhostActive` 三个概念交叉**（见第一节）。
5. **文档引用构建目录**：`docs/MODEL.md` 引用 `build/_inspect/strip_armor_base.py`，
   旧 AUDIT 引用 `build/_inspect/make_armor_recipes.py`；`build/` 被 `.gitignore` 忽略，
   `gradlew clean` 后脚本就没了。
6. **三个本家版本同时在场**：`libs/modulargolems-2.6.34-sources.jar`、`2.7.3.jar`、
   `2.7.5.jar`；`dev/xkmc` 是 2.6.34，`build/_mg273src` 是 2.7.3，`gradle.properties`/`mods.toml`
   是 2.7.5。**当前唯一权威是 2.7.5 + `mods.toml`**。

---

## 五、建议

1. **先修 2 个会咬人的**：`isFreeFlight()` 语义、GUI 预览阈值。
2. **再决定 2 个死配置**：`breath.damageMultiplier` / `cave.guidedDamage` 接回或删除。
3. **最后做结构清理**：`MODEL_SCALE`/`MODEL_LIFT` 单点化、`DragonGolemEntity` 拆常量/渲染相关部分、
   把 `build/_inspect` 脚本移到 `dev/tools`。
4. `AUDIT-MG-ADAPTATION.md` 只需要保持“历史文档”横幅，不必再维护正文；
   当前状态看 README + 本文件 + 代码。
