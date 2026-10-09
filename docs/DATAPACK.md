# 数据包：给龙加自己的"身体主题"

这份文档写给**整合包作者 / 附属作者**：不改一行代码，往数据包里丢一个 json，就能让某种材料铸出来的龙
拥有自己的粒子、伤害类型、命中效果和贴图。

## 一、文件放哪

```
data/<你的命名空间>/modulargolems_config/dragon_bodies/<随便什么名字>.json
```

- 目录体系和本家的材料配置（`.../modulargolems_config/materials/`）**完全同构** —— 学一套就够了。
- 键（`bodies` 里的键）是**材料 id**，也就是你在 `materials` 里定义的那个 id。
- 文件内容的**顶层就是字段**，没有外层包裹键（和本家材料文件的写法一致）。

## 二、字段表

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `match` | `"body"` / `"any"` | `"body"` | `body`：只有**身体部件**用这个材料才算；`any`：任意部件用了就算 |
| `texture` | 贴图 ResourceLocation | 空 | 空 = 按 `dragon_golems:textures/entity/dragon/<材料路径>.png` 找（资源包也能往这个路径塞图） |
| `breathParticle` | 粒子字符串 | `minecraft:flame` | 龙息粒子。支持带参数写法，如 `minecraft:dust{color:[1,0,0],scale:1.5}` |
| `cloudParticle` | 粒子字符串 | `minecraft:dragon_breath` | 龙弹尾迹 / 落点残云的粒子 |
| `breathDamageType` | 伤害类型 id | `dragon_golems:dragon_breath` | 龙息 / 龙弹的伤害类型 |
| `fireSeconds` | 整数 | `3` | 命中后点燃秒数；`0` = 不点火 |
| `effects` | 列表 | 空 | 命中后附加的药水效果，每条 `{ "effect": id, "duration": tick, "amplifier": 等级 }` |

> 没写的字段用上表的默认值 —— 而默认值**就是原来"其它材料"那一档的行为**，
> 所以只写两三个字段的条目也能直接跑。

## 三、匹配顺序（哪一条说了算）

1. **身体部件**那份材料的条目 —— 不看它的 `match`（身体是这条龙的核心，和技能判定同一口径）；
2. 其它部件里写了 `"match": "any"` 的条目；
3. 键为 `dragon_golems:default` 的那条（你可以覆盖它来统一改"其它材料"的表现）；
4. 都没有 → 模组内置兜底（普通火焰 / 龙息紫云 / `dragon_golems:dragon_breath` / 着火 3 秒 / 无附加效果）。

## 四、归属规则（会不会顶掉别人）

`bodies` 用的是**逐键替换**（和本家 `ingredients` 同档）：

- **加自己的材料 = 各加各的**，不会覆盖我们内置的那几条，也不会覆盖别的附属；
- 只有两个数据包给**同一个材料 id** 都写了条目时才互相覆盖 —— 那本来就是冲突，请自觉别抢别人的 id。

## 五、例子

### 最小可用（只改粒子和伤害）

```json
{
  "bodies": {
    "mymod:crystal": {
      "breathParticle": "minecraft:end_rod",
      "cloudParticle": "minecraft:dust{color:[0.4,0.8,1.0],scale:1.5}",
      "breathDamageType": "mymod:crystal_breath",
      "fireSeconds": 0
    }
  }
}
```

### 完整一点（带自己的贴图与效果）

```json
{
  "bodies": {
    "mymod:crystal": {
      "match": "body",
      "texture": "mypack:textures/entity/dragon/crystal.png",
      "breathParticle": "minecraft:end_rod",
      "cloudParticle": "minecraft:dust{color:[0.4,0.8,1.0],scale:1.5}",
      "breathDamageType": "mymod:crystal_breath",
      "fireSeconds": 0,
      "effects": [
        { "effect": "minecraft:levitation", "duration": 60, "amplifier": 0 },
        { "effect": "minecraft:glowing", "duration": 200, "amplifier": 0 }
      ]
    }
  }
}
```

### 覆盖"其它材料"的默认表现

```json
{
  "bodies": {
    "dragon_golems:default": {
      "breathParticle": "minecraft:soul_fire_flame",
      "cloudParticle": "minecraft:soul_fire_flame",
      "fireSeconds": 4
    }
  }
}
```

## 六、注意事项

1. **伤害类型记得进 `bypasses_cooldown`**：龙息每 10 tick 打一跳，如果它会被目标的无敌帧挡掉，
   玩家会看到"龙息时有时无"。内置的两条都写进了 `data/minecraft/tags/damage_type/bypasses_cooldown.json`，
   你自己的伤害类型也要照做（否则还会把旁边地面傀儡的伤害一起吞掉）。
2. **粒子写错不会崩**：解析失败会打一行 `[dragon_bodies] ... 回退到 ...` 警告并退回默认粒子。
3. **贴图路径写错也不会崩**：找不到就退回"按材料路径推"的约定，再退回基础龙贴图，并且只警告一次。
4. **效果 id 必须真实存在**：不存在的效果会被**跳过**（只影响这一条），日志里不会额外提示。
5. **改完 `/reload` 生效**：重载完成后日志里会有一行
   `[dragon_bodies] 合并完成，共 N 条：...`（由 `DragonDebug.CONFIG` 控制），
   一眼就能确认自己的条目有没有被读进来、有没有被顶掉。

## 七、还没做的（第二刀）

下面这些目前仍然写死在代码里，计划下一批搬进同一张表：

- `skills`：这条龙能用哪几个技能槽（现在"音爆大招"仍然只给幽匿身体）；
- `grantModifier`：身体是这个材料时补挂哪条本家 modifier（现在写死 `modulargolems:sonic_boom`）；
- `isSonicBody()` / `isNetherBody()` 这两个判断本身也会跟着数据化，届时"音波系"不再是材料 id 的硬编码。
