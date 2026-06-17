# RoboRumble / LiteRumble 评分指标说明

我们的对战榜单参照 **RoboRumble**（Robocode 社区的官方 1v1 天梯）的评分口径。
公开排名见 LiteRumble，例如本仓库参照的 GigaRumble 组：
<https://literumble.appspot.com/Rankings?game=gigarumble>。

LiteRumble 的排名表每个机器人一行，列依次为：

> APS · APS CI · PWIN · ANPP · Vote · Survival · Pairings · Battles

本文解释每一列的含义与算法，并说明本仓库的本地 `just duel --catalog` 能复现哪些、
不能复现哪些。权威定义见 LiteRumble 官方说明页
<https://literumble.appspot.com/ScoreExplanation>。

## 基本概念：配对（pairing）与百分制得分

天梯里每两个机器人组成一个**配对（pairing）**，反复对打若干场（battle），
每场若干回合（round）。一个配对的成绩用**百分制得分（percentage score）**表示：

```
配对得分 = 100 × 我方总分 / (我方总分 + 对手总分)
```

50% 表示势均力敌，>50% 表示占上风。下面的多数指标都是在"对全部对手的配对
得分"之上做不同的汇总。

## 各列含义

| 列 | 名称 | 含义与算法 | 越高越好？ |
|----|------|-----------|:---------:|
| **APS** | Average Percentage Score（平均百分制得分） | 对每个对手算出配对得分，再对所有对手取平均。天梯的**主排名指标**。 | ✅ |
| **APS CI** | APS 置信区间 | APS 的 95% 置信区间（±值），由各场结果的离散程度估出；样本越多区间越窄。每个配对需 ≥2 场才有意义，否则显示 `n/a`。 | 区间越窄越可信 |
| **PWIN** | Percentage Win（配对胜率） | 配对得分 >50% 的对手所占百分比，即"打赢了多少对手"。平手（恰好 50%）记半个。公式：`50 × PL / Pairings + 50`（PL = 胜场数 − 负场数）。 | ✅ |
| **ANPP** | Average Normalised Percentage Pair（归一化配对得分均值） | 先把"全场所有机器人对同一个对手"的得分归一化（该对手面前最强者=100%、最弱者=0%），再对所有对手取平均。**依赖整个天梯全场数据**。 | ✅ |
| **Vote** | 投票分 | 每个机器人给"最克制自己（自己打得最差）的那个对手"投一票；得票越多说明你是越多机器人的克星。按参赛总数归一。**依赖整个天梯全场数据**。 | ✅ |
| **Survival** | 存活率 | 你比对手活得久的回合数占总回合数的百分比：`100 × 存活回合 / 总回合`。1v1 中等价于生存分占比 `我方生存分 /(我方+对手生存分)`。 | ✅ |
| **Pairings** | 配对数 | 已交手的不同对手数量。满天梯时等于"参赛机器人数 − 1"。 | 越全越好 |
| **Battles** | 对战场数 | 累计打过的总场数（一个配对会随时间反复对打多场以降低方差）。 | 样本越多越稳 |

## 本地 catalog duel 能复现哪些

`just duel -r <robot> -c <catalog> -n <rounds>` 让我们的挑战者依次对
catalog 里的每个对手各打一场（`rounds` 回合），按上面同样的口径汇总。能**忠实
复现**的指标：

- **APS** —— 各对手配对得分的平均。
- **PWIN** —— 配对得分 >50% 的对手占比（平手记半个）。
- **Survival** —— 各对手存活率的平均。
- **Pairings** —— catalog 里成功出结果的对手数。

逐对手一行还会额外列出我们自己的诊断量（**非** rumble 指标）：

- **DEALT/r、TAKEN/r** —— 每回合我们打出 / 挨到的子弹伤害，分别衡量**枪**与
  **走位**的质量。

## 本地 catalog duel 不能复现哪些

下面这些**需要整个天梯全场所有机器人互打的数据**，单机"一个 bot 打全场"的
本地 catalog duel 无从计算，只能在公开 LiteRumble 上看：

- **ANPP** —— 归一化要知道"全场最强 / 最弱者对同一对手"的成绩。
- **Vote** —— 需要每个其它机器人各自的克星归属。
- **APS CI** —— 我们每个对手只打一场，没有"多场之间的离散度"。

## 一个对照例子

GigaRumble 榜首 `kc.mega.BeepBoop 2.0` 的公开数据（快照 2026-04）：

| APS | PWIN | ANPP | Vote | Survival | Pairings | Battles |
|----:|-----:|-----:|-----:|---------:|---------:|--------:|
| 80.58 | 100.00 | 98.21 | 93.10 | 94.80 | 29 | 313 |

读法：它对全场 29 个对手平均拿到 **80.58%** 的得分，且**全部 29 个对手都被它
压在 50% 以上**（PWIN 100%），平均能在 **94.8%** 的回合里活得比对手久。

## 参考

- LiteRumble 评分说明：<https://literumble.appspot.com/ScoreExplanation>
- GigaRumble 排名：<https://literumble.appspot.com/Rankings?game=gigarumble>
- 本仓库的 catalog 与跑分流程见 [`bots/ronin/docs/benchmarks.md`](../bots/ronin/docs/benchmarks.md)。
