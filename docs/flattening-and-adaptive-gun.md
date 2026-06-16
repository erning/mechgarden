# Flattener 与 Adaptive Gun 简介

> 来源：[RoboWiki / Flattener](https://robowiki.net/wiki/Flattener)、
> [RoboWiki / Virtual Guns](https://robowiki.net/wiki/Virtual_Guns)、
> [RoboWiki / DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT)、
> [RoboWiki / BeepBoop/Understanding BeepBoop](https://robowiki.net/wiki/BeepBoop/Understanding_BeepBoop)。
>
> 这是 Robocode 1v1 中一场永恒的“军备竞赛”：
> **Flattener** 让运动更难被统计学习，**Adaptive Gun** 让瞄准能跟上对手的变化。

## 1. 背景：统计枪 vs 冲浪者

- **统计枪**（如 Visit Count Stats、KNN / DC GF gun）通过记录“在什么情况下对手会出现在哪个 Guess Factor”，
  找到高频区域并朝那里打。
- **Wave Surfer** 通过 wave tracking 知道敌方可能朝哪里打，主动避开危险区。

于是出现对抗：

- 冲浪者越躲，统计枪越学；统计枪越学，冲浪者越要变。
- **Flattener** 和 **Adaptive Gun** 就是这场对抗的两把利器。

## 2. Flattener

### 2.1 基本思想

普通 wave surfing 只记录“敌方子弹真的打中我的位置”（`onHitByBullet`），
然后避开这些位置。这叫 **hit surfing**。

Flattener 则更进一步：

- **每个敌方 wave 经过我时**，都把 wave 上我能到达的区域均匀加上“虚拟危险”。
- 这样统计枪看到的是一个**平坦的分布**，找不到明显的高峰。
- 目标不是“躲开已知的弹着点”，而是“让我的运动在任何统计模型下都接近均匀分布”。

### 2.2 为什么叫“flatten”

如果把运动在 Guess Factor 上的分布画成直方图：

- 没有 flattener 时，某些 GF 柱很高（对手喜欢打这里）。
- 使用 flattener 后，所有 GF 的柱高趋于平均，分布被“压平”。

### 2.3 典型实现

以 `DrussGT` 为例：

- 维护多个 stat buffer：普通 hit buffer、flattener buffer、tick-flattener、
  anti-bullet-shadow flattener 等。
- **只在敌方命中率超过阈值（如约 9%）时才启用 flattener**。
- 启用后，flattener buffer 与普通 hit buffer 权重相等。

### 2.4 收益与代价

| 收益 | 代价 |
|------|------|
| 对抗快速学习的统计枪非常有效 | 对简单枪（如线性瞄准、定角瞄准）反而可能更差 |
| 让对手难以建立稳定的命中模型 | 持续 flatten 会让自己也“忘记”哪里安全 |
| 高阶冲浪者必备组件 | 调参复杂，阈值、权重、buffer 数量都要仔细调 |

> `DrussGT` 作者 Skilgannon 的解释：
> “如果全程 flatten，我们永远学不到敌人在哪里射击，也就无法主动躲开。
> 只有面对快速适应的枪时，flattener 才必要。”

### 2.5 与 Hit Surfing 的关系

- **Hit Surfing**：被打了才知道躲哪里，对慢速学习枪有效。
- **Flattener**：不被打也要让运动平坦，对快速学习枪有效。
- 高阶机器人通常**两者结合**，根据对手命中率动态切换权重。

## 3. Adaptive Gun

### 3.1 基本思想

Adaptive gun（自适应枪）指能根据对手行为变化调整瞄准策略的枪。

在 Robocode 里，它通常表现为：

- **学习枪**：用 VCS、KNN / DC、神经网络等方法，从命中/未命中中学习。
- **滚动平均（rolling average）**：让旧数据淡出，新数据更重要。
- **多枪切换（Virtual Guns）**：同时运行多种枪，选当前表现最好的那把。

### 3.2 Anti-Surfer Gun

Adaptive gun 中最重要的一种是 **anti-surfer gun**（反冲浪枪）。

冲浪者会主动避开你之前命中的区域，所以普通学习枪打他们时，
命中率会随时间下降。Anti-surfer gun 的应对思路：

- **高衰减 / 高滚动率**：只关注最近几发的数据，快速跟踪冲浪者的变化。
- **不同的特征分段（segmentation）**：使用更能刻画冲浪者行为的特征，
  如最近是否被命中、方向改变频率、wall distance 等。
- **命中反馈特征**：记录“上次命中后对手是否改变了运动模式”。

`BeepBoop` 的 anti-surfer gun 甚至使用了一个 `did-hit` 特征：

- 训练时，被命中前后经过的 wave 标记为 `did-hit = 1`。
- 瞄准时把 `did-hit` 固定为 0，从而避免使用那些对手会主动避开的旧命中数据。

### 3.3 Virtual Guns

Virtual Guns 是实践 adaptive gun 的常见框架：

- 维护多把枪（如普通 GF gun、anti-random gun、anti-surfer gun、PM gun）。
- 每发子弹都记录每把枪会瞄准的角度（virtual bullet）。
- wave 经过后，看哪些 virtual bullet 会命中，给对应枪加分。
- 开火时选择当前得分最高的枪。

优点：自动适应不同对手；
缺点：如果切换策略不好，可能反而比单把最强枪差。

## 4. Flattener 与 Adaptive Gun 的对抗

这是一场动态博弈：

1. **普通冲浪者** 靠 hit surfing 躲普通统计枪。
2. **Adaptive gun** 发现对手在躲，于是加 anti-surfer 策略，用高衰减跟踪最新运动。
3. **Flattener** 让冲浪者的运动分布变平，使 adaptive gun 找不到高峰。
4. **更聪明的 adaptive gun** 使用更精细的特征和更短的窗口，试图在平坦分布中捕捉微小模式。
5. **Crowd Surfing**（如 `BeepBoop`）同时维护多个 danger estimator，
   动态加权那些能准确预测敌方火力的模型。

## 5. 对 MechGarden 的启示

Fencer 和 Ronin 目前的枪和运动：

- **Fencer**：虚拟枪 GF 阵列、wave surfing、bullet shadows。
- **Ronin**：DC KNN 主炮、虚拟枪、自适应 firepower / movement profile、wave surfing。

两者都还没有显式的 **flattener** 组件。如果未来要加入：

- 需要维护额外的 flattener buffer。
- 需要确定启用阈值（如敌方命中率、对手机器人类型）。
- 需要与普通 hit surfing buffer 动态加权组合。

对于枪：

- Ronin 的 DC gun 已经具备一定的自适应性。
- 如果要专门提升对冲浪者的命中率，可以考虑增加一把 **anti-surfer gun**，
  使用更短的数据窗口和冲浪者-specific 特征。
- Virtual Guns 系统可以自动在“普通 GF/DC gun”和“anti-surfer gun”之间切换。

## 6. 一句话总结

> **Flattener** 是冲浪者的“反学习”武器，让统计枪看不到高峰；
> **Adaptive Gun** 是枪的“学习加速器”，试图在对手变化后仍然能打中。
> 两者共同推动 1v1 高端对局进入更高层次的动态博弈。

## 参考

- [RoboWiki / Flattener](https://robowiki.net/wiki/Flattener)
- [RoboWiki / Virtual Guns](https://robowiki.net/wiki/Virtual_Guns)
- [RoboWiki / Wave Surfing](https://robowiki.net/wiki/Wave_Surfing)
- [RoboWiki / DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT)
- [RoboWiki / BeepBoop/Understanding BeepBoop](https://robowiki.net/wiki/BeepBoop/Understanding_BeepBoop)
