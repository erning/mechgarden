# GoTo Surfing 与 Path Surfing 对比

> 来源：[RoboWiki / Wave Surfing](https://robowiki.net/wiki/Wave_Surfing)、
> [RoboWiki / Wave Surfing/True Surfing](https://robowiki.net/wiki/Wave_Surfing/True_Surfing)、
> [RoboWiki / DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT)、
> [RoboWiki / BeepBoop/Understanding BeepBoop](https://robowiki.net/wiki/BeepBoop/Understanding_BeepBoop)。
>
> 两种都是 Robocode 1v1 中 Wave Surfing 的进阶实现风格：
> **GoTo Surfing** 选“点”，**Path Surfing** 选“路径”。

## 1. 一句话定义

- **GoTo Surfing**：在当前可达区域内生成若干候选目标点，评估每个点的危险，
  然后直接移动到最安全的点。
- **Path Surfing**：在移动序列空间（forward / backward / stop 的组合）中搜索，
  评估整条路径以及路径终点状态的危险。

## 2. 核心差异

| 维度 | GoTo Surfing | Path Surfing |
|------|-------------|--------------|
| **决策对象** | 目标点 | 移动序列（path） |
| **关注重点** | 目标点的危险值 | 路径过程 + 终点状态 + 对下一 wave 的影响 |
| **搜索方式** | 生成候选点 → 精确预测命中位置 → 评估危险 | 在路径空间上做类似 A\* 的搜索 |
| **代表机器人** | `DrussGT` | `BeepBoop` |
| **与 True Surfing 的关系** | 偏“目标驱动”，与 True Surfing 差别较大 | 是 True Surfing 的泛化 |

## 3. GoTo Surfing 详解

### 3.1 算法流程

1. **生成候选点**：在 wave 可达区域上生成一组点，要求覆盖不同 Guess Factor、
   保持合理距离、避开墙壁。
2. **精确预测**：对每个候选点，预测机器人若朝该点移动，会在 wave 的哪个位置被命中。
3. **危险评估**：根据 visit count stats、KNN、flattener 等模型评估该命中位置的危险。
4. **移动执行**：选择危险最低的点，用 go-to 函数直接开过去。

### 3.2 优势

- **可达区域大**：通过生成 wave 上的候选点，可以到达 True Surfing 一次决策到不了的位置。
- **距离控制自然**：生成点时可以直接约束与敌方的距离、墙面距离等。
- **上限高**：`DrussGT` 长期排名 RoboRumble 第一，是 GoTo Surfing 的代表作。

### 3.3 劣势与挑战

- 容易只评估“点的危险”，忽略“去这个点的过程”是否安全。
  后来 `DrussGT` 也加入了“到达该点途中的危险”评估。
- 目标点生成质量直接影响效果：太多则慢，太少则覆盖不全。
- 多 wave 处理时，计算量随候选点数量快速增长。

## 4. Path Surfing 详解

### 4.1 算法流程

1. **生成基础路径**：先考虑所有前进、后退、停止的序列（类似 True Surfing 的扩展）。
2. **发现低危险区**：沿着这些路径找到 wave 上的低危险区域。
3. **生成派生路径**：针对低危险区，生成新的路径序列去到达那里，
   例如“前进 3 tick，再后退 5 tick”。
4. **A\* 搜索**：在路径空间中搜索综合危险最低的序列。
5. **复用上一回合的最优路径**：每一 tick 重新评估，但会保留并继续沿用已发现的好路径。

### 4.2 优势

- **考虑“怎么到达”**：可以主动做出“开过安全区再倒回来”之类的操作，
  如果这样有助于应对下一个 wave。
- **终点速度纳入决策**：到达目标点时的速度会影响下一个 wave 的可达区域，
  Path Surfing 能把它考虑进去。
- **更难被学习**：移动序列更丰富，模式更复杂，对 adaptive / anti-surfer gun 更友好。

### 4.3 劣势与挑战

- **实现复杂**：需要在路径空间搜索，branching factor 比单点选择大。
- **调参困难**：路径长度、搜索深度、启发函数都会显著影响表现。
- **计算成本更高**：虽然 `BeepBoop` 用 A\* 优化，但总体仍比 True Surfing 重。

## 5. 选择建议

| 场景 | 推荐风格 | 理由 |
|------|---------|------|
| 想从 True Surfing 渐进升级 | **GoTo Surfing** | 改动相对集中，主要是把单 tick 决策换成目标点搜索 |
| 想最大化多 wave 表现 | **Path Surfing** | 天然把“如何进入下一个 wave”纳入当前决策 |
| 机器人已有成熟的 go-to 移动函数 | **GoTo Surfing** | 可以直接复用现有的点到点移动代码 |
| 追求极致的 anti-surfer / flattening | **Path Surfing** | 路径多样性让对手更难建模你的运动 |

## 6. 与 MechGarden 当前机器人的关系

Fencer 和 Ronin 的运动层更接近 **True Surfing**：

- 每 tick 评估“前进 / 后退 / 停止”等选项的危险。
- 不生成任意目标点，也不在多 tick 路径空间搜索。

如果未来想升级到 GoTo 或 Path Surfing，主要改动在运动搜索模块：

- **GoTo Surfing**：把逐 tick 评估扩展为“生成未来目标点 + 评估到达危险”。
- **Path Surfing**：把逐 tick 评估扩展为“多 tick 移动序列 + A\* 搜索 + 终点状态评估”。

两者都需要保持现有的精确预测（precise prediction）和 wave tracking 能力不变。

## 参考

- [RoboWiki / Wave Surfing](https://robowiki.net/wiki/Wave_Surfing)
- [RoboWiki / Wave Surfing/True Surfing](https://robowiki.net/wiki/Wave_Surfing/True_Surfing)
- [RoboWiki / DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT)
- [RoboWiki / BeepBoop/Understanding BeepBoop](https://robowiki.net/wiki/BeepBoop/Understanding_BeepBoop)
- `docs/robocode-physics.md` — 精确预测需要的物理规则
