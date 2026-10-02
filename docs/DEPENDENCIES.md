# 依赖说明

这个 mod 只有一层：**傀儡装配（Modular Golems）**。龙不是自己写的一套实体，
而是本家的一个 `GolemType`，所以实体、部件、渲染、装备栏、标签都由本家现成的框架承担。

## 硬依赖

| 依赖 | 用途 |
|---|---|
| Minecraft 1.20.1 + Forge 47.x | |
| `modulargolems` ≥ 2.7.3 | `GolemType` / `GolemPart` / `AbstractGolemRenderer` / `SweepGolemEntity` / `EquipmentsMenu` / `modulargolems` 标签 |
| `l2library` ≥ 2.5.3 | `L2Registrate`（注册入口）、`MenuLayoutConfig`（装备界面渲染） |

运行时 `l2serial`、`mob_weapon_api`、`l2damagetracker` 这些由本家 jarjar 提供，不用单独装；
编译期必须能看见，所以 `build.gradle` 里列全了（jar 在 `libs/`）。

## 各文件依赖

| 文件 | 依赖的东西 |
|---|---|
| `dragon/DragonGolemItems.java` | Registrate（`L2Registrate`）、本家 `GolemType`/`GolemHolder`/`GolemPart` 与 `GolemTypes.TYPES` 注册表 |
| `dragon/DragonGolemEntity.java` | 本家 `SweepGolemEntity`（保留武器系统）、l2serial `@SerialClass`、原版飞行寻路 |
| `dragon/DragonGolemPartType.java` | 本家 `IGolemPart` / `GolemSlot`；物品预览变换要调客户端模型 |
| `dragon/DragonGolemType.java` | 本家 `GolemType` 的菜单 / 悬浮 / 图标三个扩展点 |
| `dragon/DragonGolemMenuControl.java` | 本家 `SweepGolemMenuControl`、`EquipmentsMenu.DOG_SLOTS` |
| `client/DragonGolemModel.java` | 本家 `IGolemModel`、原版末影龙网格（`ModelLayers.ENDER_DRAGON`） |
| `client/DragonGolemRenderer.java` | 本家 `AbstractGolemRenderer` |
| `client/DragonGolemScreenControl.java` | l2library `MenuLayoutConfig.ScreenRenderer`、本家 `GolemScreenControl` |
| `client/DragonGolemOverlayControl.java` | 本家 `GolemOverlayControl` / `GolemStatusOverlay` |
| `compat/jei/DragonGolemsJeiPlugin.java` | JEI 15 的 `IModPlugin` / `IRecipeRegistration`；**只在装了 JEI 时才会被加载**。另外读了本家 `GolemMaterialConfig` / `GolemPart`（为了把说明页登记到每个材料变体上） |

JEI 在 `build.gradle` 里是 `compileOnly`（jar 在 `libs/`）：编译要能看见它，运行时没有也不会出问题。

## 没有的东西

这个仓库不含 mixin、不含网络包、不含可选兼容层——龙傀儡不需要它们。
从 `golems_arsenal` 拆出来的 `mixin/`、`network/`、`compat/`、`tech/`、`base/`
全部留在了原仓，别顺手抄回来。

## 数据文件

`data/modulargolems/tags/**` 的命名空间必须是 `modulargolems`：这些文件是**往本家的标签里追加**
条目（数据包标签会合并），改成自己的命名空间就等于另建一份没人读的标签。

`data/dragon_golems/modulargolems_config/` 下的材料与部件配置则是自己的命名空间，
里面的 id 必须和 `DragonGolemItems` 的注册名、以及贴图文件名对得上。
