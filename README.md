# Dragom Golems（巨龙傀儡）

给「傀儡装配」（Modular Golems）加的一条**龙形傀儡**：一个傀儡种类、五个部件、一个成品、一个胚料、
五个装材料的部位配置，外加自己的创造栏、装备界面、JEI 说明页与图标预览。
从 `golems_arsenal`（傀儡军械库）里拆出来单独维护 —— 武器 / 科技 / 铁魔法那些留在那边，这边一概没有。

## 这条龙会干什么

**常态：锥形龙息**（无冷却，用来填两次大招之间的空档）

- 判定是从嘴部往前的一条锥体，长度随体型放大（基准 24 格，泰坦体型约 45 格）。
- 粒子、伤害类型与附带效果由**身体部件**的材料决定：

| 身体材料 | 龙息表现 | 附带效果 |
|---|---|---|
| 普通（铁 / 金 / 铜 …） | 火焰 | 着火 3 秒 |
| 下界合金 | 灵魂火 | 着火 5 秒 + 凋零 |
| 幽匿 | 音波（用原版 `sonic_boom` 做伤害：穿护甲与附魔，但吃抗性药水） | 黑暗 + 虚弱 |

**大招三个**（按可用性加权随机挑，不是固定循环）

- **俯冲**：拉升后俯冲掠过，接触盒随体型往下铺；独立 30 秒冷却。
- **龙弹**：吐一颗带残留云的火球；与音爆**共享 15 秒冷却**（谁先满足条件谁放）。
- **音爆**：线状穿透，沿途目标全中，用本家的 `echo_attack`（真伤）。**只有幽匿身体的龙有这一招** ——
  所以幽匿龙 = 音波龙，其它材料回到「俯冲 / 龙息 / 龙弹」三招。

**所有几何都跟着体型走**：判定箱、悬停高度、转向速率、漫游与归位距离、俯冲的起止高度与接触盒、
音爆光束长度、环绕半径、龙息锥长。装「泰坦升级」（+300% 体型）之后不是"一条大模型配一套小判定"，
而是整套按倍率放大。

**和本家的接口**

- 五个部件都能在本家的铁砧上铸材料（铜 / 金 / 铁 / 下界合金 / 幽匿），龙身上也能用本家其它傀儡材料。
- 创造栏除了自己那一页，还会往本家的「傀儡装配 - 傀儡」页补上「五个部件 × 每种材料」
  与「每种材料一条纯部件龙」。
- 本家置换/分解台与装备界面里那条悬浮 3D 预览会自动缩放，不会糊满界面（26 格长的龙按本家的
  "每格 18 像素"画出来是 479 像素）。
- JEI 里有胚料、部件、成品的说明页（不装 JEI 也能正常玩）。

## 依赖

| 依赖 | 版本 | 说明 |
|---|---|---|
| Minecraft | 1.20.1 | |
| Forge | 47.x（开发用 47.4.20） | |
| 傀儡装配 Modular Golems | ≥ 2.7.3 | **必装**，龙是它的一个 `GolemType` |
| l2library | ≥ 2.5.3 | `L2Registrate` 来自这里 |
| Registrate | MC1.20-1.3.11 | 编译期依赖，运行时由本家提供 |

`libs/` 里放着本家与 l2 系列的成品 jar（`flatDir` 直接引用），**这个目录不进版本库**，
换机器时记得把 jar 放回来，或者在 `build.gradle` 里改成从 maven 拉。

## 安装

1. 先装 **Modular Golems ≥ 2.7.3** 和 **l2library ≥ 2.5.3**（本家自己的前置）。
2. 把 `dragom_golems-<版本>.jar` 丢进 `mods/`。
3. **不要和 `golems_arsenal` 一起装**：两边都注册了一条龙，创造栏会看到两份、
   两个傀儡种类在装备界面里也各占一项。

## 构建

```
./gradlew build
```

产物在 `build/libs/dragom_golems-<版本>.jar`。

## 代码结构

```
src/main/java/a_silly_cat/dragom_golems/
  Dragom_golems.java              主类（@Mod）：MODID / id() / 初始化注册
  init/ModCreativeTabs.java       自己的创造栏；另外往本家的傀儡页补物品
  compat/jei/                     JEI 说明页（JEI 不在也能正常玩）
  dragon/                         服务端与两端共用：实体 / 种类 / 部件枚举 / 装备栏 / AI / 全部注册
  client/                         仅客户端：模型 / 渲染器 / 装备界面底图 / 悬浮图标 / 预览摆位

src/main/resources/
  assets/dragom_golems/           物品模型、lang、龙的贴图
  data/dragom_golems/             材料与部件配置、7 条配方
  data/modulargolems/tags/        往本家的标签里追加（命名空间保持 modulargolems 不动）

docs/
  DEPENDENCIES.md                 依赖与各文件用到了什么
  JEI.md                          JEI 里哪些页是本家白送的、我们补了什么
  MOUNT.md                        升级/坐骑/载客的反查结果：哪条升级被挡、龙最多载几个、怎么落地
  RENDERING.md                    界面与渲染代码地图：哪块画面归哪个文件管
  DEV-RUN.md                      开发环境为什么跑不起来、run/mods 里那三个 jar 是干嘛的
```

## 尺寸与预览：几个旋钮各管一段

别混着调，细节都在 `docs/RENDERING.md`：

- **世界里的碰撞箱** → `dragon/DragonGolemItems` 的 `ENTITY`，现在 `.sized(2.6F, 2.2F)`。
- **看起来多大** → `client/DragonGolemModel` 的 `MODEL_SCALE`（现在 1.7），抬高量 `MODEL_LIFT`（1.36）
  按 0.8 的比例跟着改，就只是等比缩放、站位不变。
- **尺寸档位** → `dragon/DragonGolemEntity.createAttributes` 的 `GOLEM_SIZE`，现在 **5.0**。
  本家的 `getScale()` 是"当前值 / 默认值"的**倍率**，所以默认值本身不改变大小；
  基准取 5 只是让本家 `size_up` 的每级 +0.5 从"放大 50%"变成"放大 10%"。
  这条属性还会影响判定箱、台阶高度、重铸缩放，以及载客上限的计算（见 `docs/MOUNT.md`）。
- **各种图标与预览** → `client/DragonGolemModel` 顶部的 `PREVIEW_TARGET_PX`（五个部件的背包图标，
  按部件分档）、`HOLDER_TARGET_PX`（成品图标）、`GUI_PREVIEW_TARGET_BLOCKS`（本家界面里那条悬浮预览）。

## 移植说明

从 `golems_arsenal` 拆出来时只做了命名空间替换，游戏逻辑一行没动：

| 原（golems_arsenal） | 现（dragom_golems） |
|---|---|
| 包名 `a_silly_cat.golems_arsenal` | `a_silly_cat.dragom_golems` |
| `Golems_arsenal.MODID` / `.id(...)` | `Dragom_golems.MODID` / `.id(...)` |
| `assets/golems_arsenal/...`、`data/golems_arsenal/...` | `assets/dragom_golems/...`、`data/dragom_golems/...` |
| json 里的 `golems_arsenal:xxx` | `dragom_golems:xxx` |
| `data/modulargolems/tags/...` 的命名空间 | **不变**，只换里面的物品/实体 id |
| 创造栏挂在 `golems_arsenal` 的标签页里 | 新建独立标签页 `itemGroup.dragom_golems` |

物品的注册名（`dragon_golem_head` 等）、材料 id（`iron` / `gold` / …）和贴图文件名都保持原样，
所以材料/配方/贴图那套对应关系不需要重新推一遍。移植前的原件只在本地留作对照，不进版本库。

## 还没做的

1. **胚料的价格是暂时的**。现在一共 7 条配方：`钻石 + 铁块 → 胚料`（合成台）、
   「胚料 → 五个部件」（切石机，一块胚料切一个）、「五部件 → 成品」（装配台）。
   注意**胚料是消耗品**：一条龙要 5 个部件，也就是 5 块胚料（= 5 钻石 + 5 铁块），
   再叠加铁砧铸材料的开销 —— 胚料只是门槛，不是主要花费。
   参照本家 `metal_golem_template`（4 黏土球 + 4 木棍 + 1 铜锭），这个档位是"比金属傀儡贵一号"，数值待定。
2. **装备栏是犬型两格**（头盔位 + 胸甲位），没有主手与远程槽。
3. **载客走不通**。本家给狗留的载客通道对这条 26 格长的龙不成立（宽度预算算不过来），
   反查过程与结论见 `docs/MOUNT.md`。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
