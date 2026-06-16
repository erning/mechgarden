# Guess Factor 详解

> 来源：[RoboWiki / Guess Factor](https://robowiki.net/wiki/Guess_Factor)、
> [RoboWiki / Maximum Escape Angle](https://robowiki.net/wiki/Maximum_Escape_Angle)、
> [RoboWiki / GuessFactor Targeting (traditional)](https://robowiki.net/wiki/GuessFactor_Targeting_(traditional))、
> [RoboWiki / Wave Surfing](https://robowiki.net/wiki/Wave_Surfing)。
>
> Guess Factor（GF）是 Robocode 统计瞄准和 wave surfing 的共同语言。
> 它把敌人可能的逃逸角度归一化到 [-1, 1]，让不同距离、不同子弹速度下的经验可以统一统计。

## 1. 为什么需要 GF

如果你直接朝敌人当前位置开火（head-on），除非敌人不动，否则大概率打不中。

敌人会横向移动。等子弹飞到他那里时，他已经跑了。

这个偏移角取决于：

- 敌我距离
- 子弹速度（由开火威力决定）
- 敌人最大横向速度（8 像素/回合）

GF 的作用就是把这些变量**归一化**，让“敌人跑了一半”或“敌人跑到极限”成为与距离、子弹速度无关的通用描述。

## 2. 最大逃逸角（MEA）

敌人以最大速度 8 像素/回合横向移动，子弹以速度 `bulletSpeed` 飞行。
敌人能偏离 head-on 方向的最大角度叫 **Maximum Escape Angle（MEA）**：

```text
MEA = asin(8 / bulletSpeed)
```

| 子弹威力 | 子弹速度 | MEA |
|---------|---------|-----|
| 0.1 | 19.7 | 23.9° |
| 1.0 | 17.0 | 28.1° |
| 2.0 | 14.0 | 34.8° |
| 3.0 | 11.0 | 46.7° |

- 子弹越快，敌人越难跑掉，MEA 越小。
- 子弹越慢，敌人越容易跑掉，MEA 越大。

## 3. GF 的定义

GF 把 MEA 归一化到 [-1, 1]：

| GF | 含义 |
|----|------|
| 0 | head-on，正对敌人当前位置 |
| +1 | 敌人沿当前横向方向最大逃逸的极限 |
| -1 | 敌人沿当前横向方向反向最大逃逸的极限 |
| 0.5 | 敌人逃到 MEA 一半的位置 |

换算公式：

```text
firingAngle = headOnAngle + direction × GF × MEA
```

其中：

- `headOnAngle` 是正对你的敌人的角度。
- `direction` 是敌人的横向运动方向符号（+1 或 -1）。
- `MEA` 是当前子弹对应的最大逃逸角。

## 4. 为什么 GF 比原始角度更好

| | 原始角度 | GF |
|--|---------|----|
| 距离影响 | 同样角度偏移，近距离和远距离落点差很多 | 归一化后无关 |
| 子弹速度影响 | 不同速度下敌人逃逸范围不同 | 统一映射到 [-1, 1] |
| 可统计性 | 很难汇总不同情况 | 所有历史 GF 可以直接比较 |
| 跨情况通用 | 差 | 强 |

GF 让“敌人倾向于往 GF=0.3 跑”成为一个可以在任何距离、任何子弹速度下复用的知识。

## 5. GF 在瞄准中的应用

### 5.1 Visit Count Stats（VCS）

传统 GuessFactor Targeting 的核心：

1. 把战场情况按特征分段（segmentation），例如：
   - 距离段
   - 横向速度段
   - 加速度段
   - 离墙距离段
2. 每次开火后，记录实际命中的 GF。
3. 命中后，给对应 segment、对应 GF bin 加分。
4. 下次类似情况时，选得分最高的 GF 开火。

数据结构大致是：

```text
stats[segment][gfBin]++

aimGF = argmax_gf stats[currentSegment][gf]
```

### 5.2 与 DC / KNN 结合

DC / KNN 枪不直接维护 GF bin 数组，而是保存每条历史记录的实际命中 GF。

查询时：

1. 找到与当前情况最相似的 K 条记录。
2. 这些记录各自有一个命中 GF。
3. 用 kernel density estimation 找最密集的 GF 区域。
4. 朝该区域开火。

GF 在这里仍然是统一的“命中位置语言”。

## 6. GF 在冲浪中的应用

Wave surfer 反过来用 GF：

1. 敌人开火时，以敌人位置为圆心、子弹速度为半径创建一个 wave。
2. wave 扩散到机器人当前位置时，计算机器人处于该 wave 的哪个 GF。
3. 如果过去在这个 GF 上被命中过，那么这个 GF 就是危险区。
4. 移动时选择危险低的 GF 方向。

所以：

- **枪** 学习“敌人会出现在哪个 GF”。
- **冲浪** 学习“敌人会朝哪个 GF 开火”。

## 7. 具体例子

假设：

- 敌人在你正北方向，距离 400 像素。
- 你开 power=2 的子弹，速度 `20 − 3 × 2 = 14` 像素/回合。
- 子弹飞行时间约 `400 / 14 ≈ 28.6` 回合。
- MEA = `asin(8 / 14) ≈ 34.8°`。

如果敌人以最大速度垂直于你视线向右跑，他最终会在你视线的 `+34.8°` 方向，即 GF=+1。

如果你猜他只跑了一半，就瞄准 GF=+0.5，即 `+17.4°`。

## 8. 方向约定

GF 的符号需要和敌人的横向运动方向绑定。

常见约定：

```text
direction = sign(lateralVelocity)
firingAngle = headOnAngle + direction × GF × MEA
```

这样：

- `lateralVelocity > 0` 时，GF=+1 表示“继续沿当前方向跑”。
- `lateralVelocity < 0` 时，GF=+1 表示“沿当前方向的反方向跑”。

始终保持 GF=+1 代表“敌人按当前横向方向最大逃逸”。

## 9. GF 的局限

GF 是一个**一阶近似**，它假设：

- 敌人会保持当前横向方向运动。
- 敌人不会加速、减速、转向、撞墙。
- 敌人不会故意反向或做其他复杂动作。

实际高端机器人会在 GF 基础上加入更多特征：

- 距离
- 横向速度 / 接近速度
- 加速度
- 离墙距离
- 子弹飞行时间
- 上一发命中后的反应

这就是 GF targeting 发展到 DC / KNN 的原因。

## 10. 在 MechGarden 中的位置

Fencer 和 Ronin 都使用 GF 作为底层表示：

- **Fencer**：虚拟枪 GF 阵列，用 Visit Count Stats 统计命中 GF。
- **Ronin**：DC KNN 主炮保存历史记录的命中 GF，冲浪层用 GF 评估危险。

无论哪种实现，GF 都是把连续战场状态映射到可统计空间的关键桥梁。

## 11. 一句话总结

> **Guess Factor = 把敌人可能的逃逸角度归一化到 [-1, 1]。**
>
> 它让不同距离、不同子弹速度下的瞄准和冲浪经验可以统一比较和统计，
> 是 Robocode 1v1 最核心的概念之一。

## 参考

- [RoboWiki / Guess Factor](https://robowiki.net/wiki/Guess_Factor)
- [RoboWiki / Maximum Escape Angle](https://robowiki.net/wiki/Maximum_Escape_Angle)
- [RoboWiki / GuessFactor Targeting (traditional)](https://robowiki.net/wiki/GuessFactor_Targeting_(traditional))
- [RoboWiki / Wave Surfing](https://robowiki.net/wiki/Wave_Surfing)
- `docs/dc-knn-targeting.md` — DC / KNN 如何用 GF 作为命中位置语言
