# 模型组：龙的网格是怎么拆的

这份文档给"要往龙身上加东西"的人看：**装甲、联动装饰、渲染层**都应该挂在哪一组、
坐标怎么换算、贴图跟哪一组走。几何本身来自原版末影龙网格（`ModelLayers.ENDER_DRAGON`），
`DragonGolemModel` 只做三件事：**分组**、**摆姿势**、**按组换贴图**。

## 一、五个模型组（`DragonGolemPartType`）

枚举顺序 **不能改**：装配时材料按网格扫描顺序写进列表，渲染时按 `ordinal()` 取材料
（`WINGS → HEAD → BODY → TAIL → LEG`，对应装配表第一行到第三行）。

| 组 | 枚举 / `GolemSlot` | 实际画哪些 `ModelPart` | 动画 |
|---|---|---|---|
| **WINGS** | `WINGS` / `UP` | `left_wing` + `right_wing`（各带 `*_wing_tip` 子部件） | `xRot/yRot/zRot` 扇动 + 翼尖 `zRot` 摆动 |
| **HEAD** | `HEAD` / `LEFT` | `head`（含 `jaw` 子部件）+ **5 段前颈**（同一个 `neck` 部件重复摆 5 次） | 头随视线偏航/俯仰（±60°/±40°）；5 段脖子按比例插值弯过去；下颌随拍翅/喷息开合 |
| **BODY** | `BODY` / `MIDDLE` | `body` | 本身不摆姿势；俯仰/侧倾在**模型根**上做 |
| **TAIL** | `TAIL` / `RIGHT` | **12 段尾巴**（同一个 `neck` 部件重复摆 12 次） | 每段 `sin` 相位摆动 |
| **LEG** | `LEG` / `DOWN` | 4 条腿（`left/right_front_leg`、`left/right_hind_leg`，各带 `*_tip`/`*_foot`） | 固定折叠角 + 随拍翅上下浮 |

> `neck` 这个 `ModelPart` 被"前颈 5 段 + 尾巴 12 段"复用，所以它**不属于任何单独一组**：
> 画 HEAD 时用 `frontSeg` 的位置摆 5 次，画 TAIL 时用 `tailSeg` 摆 12 次。

## 二、组件树（原版末影龙的枢轴/盒子，单位：模型像素）

```
root
├── head                    枢轴 (0,0,0)
│   ├── upperlip            (-6,-1,-24)  12×5×16
│   ├── upperhead           (-8,-8,-10)  16×16×16
│   ├── scale / nostril     左右各一对
│   └── jaw                 枢轴 (0,4,-8)，盒 (-6,0,-16) 12×4×16   ← 张嘴就转它
├── neck                    盒 (-5,-5,-5) 10×10×10（前颈/尾巴共用，位置由代码逐段摆）
├── body                    枢轴 (0,4,8)，盒 (-12,0,-16) 24×24×64  ← 长条；3 个背脊 scale
├── left_wing               枢轴 (12,5,2)；bone 56×8×8 + skin 56×0×56
│   └── left_wing_tip       枢轴 (56,0,0)；bone 56×4×4 + skin 56×0×56
├── right_wing              枢轴 (-12,5,2)，镜像
│   └── right_wing_tip      枢轴 (-56,0,0)，镜像
├── left_front_leg          枢轴 (12,20,2)，盒 8×24×8
│   └── left_front_leg_tip  (0,20,-1) 6×24×6
│       └── left_front_foot (0,23,0) 8×4×16
├── left_hind_leg           枢轴 (16,16,42)，盒 16×32×16
│   └── left_hind_leg_tip   (0,32,-4) 12×32×12
│       └── left_hind_foot  (0,31,4) 18×6×24
└── right_front_leg / right_hind_leg（镜像，x 取负）
```

几个"量感"数字（×`MODEL_SCALE` 换格）：

- 模型像素 → 格：`MODEL_SCALE(1.7) / 16 ≈ 0.106 格/px`；
- 躯干：24 × 24 × 64 px ≈ **2.55 × 2.55 × 6.8 格**；
- 一侧翅膀：56+56 = 112 px ≈ 11.9 格（两翼展开约 23.8 格，加躯干 ≈ 26 格）；
- 前颈：5 × 10 px = 50 px ≈ 5.3 格；尾巴：12 × 10 px = 120 px ≈ 12.75 格；
- 头：16 px 方块 ≈ 1.7 格，上唇往前再伸 24 px ≈ 2.55 格。

## 三、坐标换算（挂东西前必须知道）

1. **模型空间 Y 向下**：本家 `LivingEntityRenderer` 先做 `scale(-1,-1,1)`，模型自己再
   `translate(0, -MODEL_LIFT, 0)`、`scale(MODEL_SCALE)`。
   所以"模型里的 +y"在世界里是**向下**；写偏移时往上抬用**负 y**。
2. **模型像素 → 世界高度**：`世界Y = MODEL_LIFT(1.36) + 1.501 - px * MODEL_SCALE / 16`
   （`1.501` 是本家渲染器的 `translate(0,-1.501,0)`）；
3. **所有部件都相对 root**：`head` 的枢轴在 (0,0,0)，但代码每帧用
   `head.setPos(headX, headY, headZ)` 把它挪到脖子末端；
   `body` 的枢轴是 `(0,4,8)`；翅膀/腿的枢轴见上表。
4. 给渲染层用的现成入口（`DragonGolemModel`，都假设**先调过** `applyRootTransform`）：
   - `applyRootTransform(pose)`：复刻实体渲染的根变换（抬高 → 朝向 → 俯仰 → 侧倾 → 缩放）；
   - `translateToBackAnchor(pose, upPx, backPx)`：躯干 + 背脊偏移（披风用的就是它）；
   - `translateToBodyAnchor(pose)`：躯干枢轴；
   - `translateToHeadAnchor(pose)`：头部枢轴（含下颌子部件）；
   - `translateToWingAnchor(pose, left)`：翼根；
   - `translateToLegAnchor(pose, index)`：`0/1/2/3` = 左前/右前/左后/右后；
   - `translateToNeckAnchor(pose, index)` / `translateToTailAnchor(pose, index)`：
     第 N 段前颈 / 尾巴（直接复刻该段的平移+旋转，不改共享的 `neck` 部件）。

   `DragonCapeLayer` 就是"先 `applyRootTransform`，再挂点，再画"的完整例子。

## 四、贴图跟着哪一组走

每个组是**独立一次** `renderToBufferInternal(part, ...)`，贴图由调用方（本家
`AbstractGolemRenderer.renderAllParts`）按**该组自己的材料**绑定
（`IGolemModel.getTextureLocationInternal(material)` → `textures/entity/dragon/<材料>.png`）。

- 所以**混装材料**时每组各用各的贴图（身体铁、翅膀金）；
- **皮肤**（`GolemFacade`）会把**所有组**的渲染材料换成皮肤材料 → 有贴图就整条换皮，
  没画过就退回**身体组**的贴图；
- 想在龙身上加"另一张图"的东西（装甲、发光装饰），**不能**走 `renderToBufferInternal`，
  因为那一趟已经绑好了龙自己的贴图；要写独立的 `RenderLayer`（见 `DragonCapeLayer`）。

## 五、判定箱子箱 ↔ 模型组

子碰撞箱只是"按模型摆的立方体"，不跟组一一对应（一个组可能拆成多个箱子）：

| 子箱 | 数量 | 基准尺寸 | 位置来源 |
|---|---|---|---|
| `head` | 1 | 2.4 × 2.4 | `HEAD_UP_PX` / 头部前伸 |
| `neck` | 5 | 2.0 × 2.0 | `NECK_SEGMENTS_PX = {27, 47}`（每段一格） |
| `torso` | 4 | 2.4 × 2.4 | `TORSO_SEGMENTS_PX = {8, -14, -36, -54}` |
| `tail` | 6 | 2.0 × 2.0 | `TAIL_SEGMENTS = {1,3,5,7,9,11}`（隔段取） |
| `left_wing` / `right_wing` | 2 | 5.0 × 1.0 | 翼根位置 |

改模型尺寸时判定箱**不会自动跟**（`MODEL_SCALE` 只动模型），要同步改
`DragonGolemEntity.rotateAndLift()` / `placePart()` 那一套和 `DragonGolemItems.ENTITY` 的 `.sized(...)`
（旧文里的 `partUp()` 已经不存在）。

## 六、要加装甲 / 联动内容时怎么接

### 6.1 物品侧（当前已经有四件龙甲）

龙甲不是“待实现”状态，当前代码里已经有：

- `DragonArmorItem`：继承 `GolemEquipmentItem`，实现 `GolemModelItem`；
- 四件 × 铁/钻石/下界合金 = 12 个物品，注册在 `DragonGolemItems.ARMOR_*`；
- 槽位约定：龙头 `HEAD`、龙身 `CHEST`、四肢 `LEGS`、龙尾 `FEET`
  （`DragonGolemMenuControl.fillMenu()` 的四个甲槽）。

新增同槽位的甲只需要：

- `isFor(EntityType)` 对龙的实体类型返回 true；
- `getSlot()` 返回上面四个槽之一；
- `getGolemModifiers(...)` 给出护甲/韧性/击退抗性。

`DragonGolemEquipHandler` 已经把“本家漏掉龙类型分支”补上了：任何
`IGolemEquipmentItem` 只要 `isFor(龙)`，就会按它自己的 `getSlot()` 报给菜单，
不需要再动 `DragonGolemMenuControl`。

### 6.2 渲染侧（当前的 `DragonArmorLayer`）

当前实现不是“照 `DogArmorRenderer` 再写一个整层”，而是照本家金属傀儡的**多护甲**写法：

1. `DragonArmorModel` 用 `GolemModelPath` 注册四条路径表
   （`DragonArmorItem.PATH_HEAD/BODY/LIMBS/TAIL`）；
2. `DragonArmorLayer` 按物品的 `getModelPath()` 取路径、按 `getModelTexture()` 取 512×512 图集，
   沿路径把祖先 `translateAndRotate` 压栈后只画链尾甲片；
3. 姿势由 `DragonGolemModel.applyArmorPose(DragonArmorModel)` 每帧从本体骨架拷过去；
4. `DragonGolemRenderer` 构造里依次挂 `DragonArmorLayer` 和 `DragonCapeLayer`。

新增一套同槽位的新甲：加物品 + 在 `DragonArmorModel` 注册 path，层本身不用改。
如果要做**新的槽位/新模型层**，才需要改 `DragonArmorLayer` 的 `renderPiece` 列表和菜单。

`DragonArmorLayer` 目前只认 `DragonArmorItem`；第三方如果做别的 `GolemModelItem` 龙甲，
装备事件能穿、但不会被这一层画出来 —— 这是当前实现边界，不是“全数据驱动”。

### 6.3 各组的"装甲挂点"建议

| 想做 | 挂哪个组件 | 备注 |
|---|---|---|
| 头盔 / 面甲 / 头冠 | `head`（含 `jaw`） | 跟随 `head.setPos` + 视线角度；嘴部装饰要避开 `jaw` 的开合范围 |
| 胸甲 / 背甲 | `body` | 躯干 24×24×64 px；背脊上方 `up ≈ 12~13 px`（披风就是这条），两侧 ±12 px |
| 腿甲 / 爪套 | 4 条腿的 `leg`/`tip`/`foot` | 前腿枢轴 (12,20,2)，后腿 (16,16,42)；注意腿有固定折叠角 |
| 翼甲 / 翼膜挂饰 | `left_wing` / `right_wing` + `*_wing_tip` | 翼在每帧都在转，挂件会跟着扇；翼根枢轴 (±12,5,2) |
| 尾甲 / 尾环 | 12 段尾巴（`neck` 部件，`tailSeg`） | 段位置是动画算出来的，按段号取 `tailSeg[i]` 的 `{x,y,z}` |
| 颈甲 / 项圈 | 5 段前颈（`frontSeg`） | 同上，按段号取 `frontSeg[i]` |

> 所有偏移都用**模型像素**（1 格 = 16），且"往上"是**负 Y**；
> 缩放已经在 `applyRootTransform` 里乘过 `MODEL_SCALE`，层里不用再乘。

### 6.4 已经接好的例子：龙铠

龙铠（`龙铠.bbmodel` 导出）走的是**本家金属傀儡那套"多护甲"写法**：

- 物品实现本家的 `GolemModelItem`：
  - `getModelPath()` → 这件甲用哪张 `GolemModelPath`（`DragonArmorItem.PATH_HEAD/BODY/LIMBS/TAIL`）；
  - `getModelTexture()` → 这件甲自己的 512×512 图集；
- `DragonArmorModel` 里用本家的 `GolemModelPath` 注册四张路径表，每条链从模型根的子骨
  一路写到甲片（例：`neck→neck2→…→neck5→head→head1`、`tail→tail2→…→tail12→Tail_shield12`）；
- 渲染层 `DragonArmorLayer` 只认 `GolemModelPath`：
  取 path → 按 `path.models()` 拿/烘对应的护甲模型（缓存，等价于本家的
  `GolemEquipmentRenderer.map`）→ 绑定物品图集 → 沿每条链把祖先 `translateAndRotate` 压栈、
  只画链尾甲片。**本体照常画**，所以是"穿甲"不是"换皮"。

加一件新甲 / 换一整套新模型：

1. 新物品实现 `GolemModelItem`（或复用 `DragonArmorItem`，换 path + 图集）；
2. 在 `DragonArmorModel` 里注册新 path（`GolemModelPath.register`），链尾指向新甲片；
3. 换整套模型时，新模型注册一个新的 `ModelLayerLocation`，path 的 `models()` 指向它即可——
   渲染层的模型缓存会自动按 `models()` 烘第二套。

导出模型里那条"复制整条龙"的骨架方块，构建时已剥掉（`build/_inspect/strip_armor_base.py`），
路径渲染本来就只画链尾，剥掉只是省顶点；**作者在 Blockbench 里往同名骨骼下加甲片即可**，
不需要手摆坐标。

- 姿势由 `DragonGolemModel.applyArmorPose()` 每帧拷过去：脖子/尾巴是**嵌套链**，
  所以子骨给的是"后一段角减前一段角"，其余部位直接抄角度；甲片挂在这些骨下面，自动同步。
- 注意：剥骨架方块是按**已知骨名**做的；作者如果改了骨架骨的名字，需要同步
  `strip_armor_base.py` 的名单。

如果要给别的部位做装甲/装饰，照这个结构加根级分支 + 一个 `RenderLayer` 即可，
坐标换算和挂点仍然用第三节那几个 `translateTo*Anchor`。

## 七、改模型时的牵连清单

- `MODEL_SCALE` / `MODEL_LIFT` 改比例时，判定箱（`DragonGolemEntity`）和
  `GUI_PREVIEW_TARGET_BLOCKS` / 物品预览的实测都会跟着受影响；
- 五个组的**枚举顺序**不能动（材料按 ordinal 取）；
- 新增 `ModelPart` 时记得在 `renderToBufferInternal` 的 switch 里归组，否则不会被画；
- 部件贴图命名约定：`textures/entity/dragon/<材料路径>.png`，找不到依次回退
  `base.png` → `enderdragon.png` → 原版末影龙贴图（见 `getTextureLocationInternal`）。
