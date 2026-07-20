# HANDOVER — Proteus 移交说明

> 给下一个接手的人（或下一个会话）：本文件说明 MechGarden 工作区里
> `zen.Proteus` 机器人的目标、当前进度、架构、经验教训与下一步工作。
> 最后更新：M8 完成（commit `e69b5f4`）。

## 1. 项目与目标

MechGarden 是 Robocode classic 1.11.0 的 Kotlin/JVM 工作区。`bots/proteus`
里的 `zen.Proteus` 是第四代机器人：**以原创代码接近并最终超越
`kc.mega.BeepBoop`**。方法论的参照是 `docs/beepboop-analysis.md`（吸收其
公开发表的思想，不复制代码与类结构）。

- 总纲：`AGENTS.md`（根）+ `bots/proteus/AGENTS.md`（模块规则，优先）
- 架构设计：`bots/proteus/docs/architecture.md`
- 里程碑与验收记录：`bots/proteus/docs/roadmap.md`
- 命令：`just build` / `just lint` / `just fmt` / `just deploy proteus` /
  `just duel -r Proteus -c basic -n 100`

## 2. 当前进度（M1–M8 已完成）

| 里程碑 | 内容 | basic APS（100 回合） |
|---|---|---|
| M1 | 骨架：流水线、Controls 指令帧、GameState、雷达、轨道移动、线性枪 | ~45%（对 RaikoNano） |
| M2 | 敌波 + 精确交集 + 经验危险 + 三选一真冲浪 + GF-bin 枪（虚拟波训练） | 36.4% |
| M3 | bullet shadow 常驻；PathSurfer 建成但**默认禁用**（见 §5.4） | 37.3% |
| M4 | KNN 双枪（主枪/反冲浪枪硬切换，didHit 标记） | 46.1% |
| M5 | 危险集成：门控 + 动态权重 | **58.8%，PWIN 100%** |
| M6 | 守卫式威力 + 计划关联主动阴影 | 59.0%，PWIN 100% |
| M7 | 策略层（anti-HOT/ram/mirror）+ 残局收割 + 在线分类子弹护盾 | 专项 60-76%，PWIN 全 100% |
| M8 | 离线训练闭环（采集/训练/回读/验证），首轮嵌入打平 | 56.1% |

classic catalog（RaikoMX / Lacrimas / BlestPain）：M5 后 40.9%。对照基线：
Mirage 同 basic 为 78.3% APS / 93.3% survival。主要差距仍在**防守**
（taken/r 40.5 vs Mirage 24.8）。

里程碑 M1–M8 已全部完成。基线：basic ~57%、classic ~37%、expert 34.8%、top 16.1%（DrussGT 18.1 / ScalarR 15.0 / BeepBoop 15.2）。

## 3. 架构速览

包根 `zen.proteus`；外壳 `zen.Proteus`（一行具体类），实现在
`zen.proteus.Proteus`（abstract）。每 tick 流水线（数据依赖序）：

1. `state.GameState` — 快照自身、扫描重建敌人、能量差开火检测（补偿我方
   命中伤害与机器人碰撞 0.6）、历史环形缓冲。
2. 子弹终结事件 → 匹配波（命中学习）。
3. `radar.Radar` — infinity lock / 丢锁重搜。
4. `move.Mover` — 敌波推进（波用「移动前的我方方块」测试，引擎顺序）→
   `DangerEstimator` 组合危险 → 三选一冲浪（`Surfer`）或轨道回退。我方
   在飞子弹（`OurBullets`）→ `BulletShadows` 阴影折减危险。
5. `aim.Aimer` — 每 tick 虚拟波 + 实弹波训练双 KNN 树（`knn.KnnModel`），
   按我方命中率区间硬切换主枪/反冲浪枪；开火门控 = 枪热归零 + 炮管对准
   到能命中的角度 + 能量余量。
6. `control.Controls.apply()` 统一发 `set*`，主循环 `execute()` 提交。

关键约定（全部在 `bots/proteus/AGENTS.md` 也有）：

- 角度一律弧度、变量名带 `Radians` 后缀；罗盘北为 0、顺时针。
- 子系统只写自己的 Controls 通道（雷达/车身/炮管开火），null = 不动。
- 学习态（危险集成、KNN 树、GF 轮廓）走 per-enemy 静态注册表，跨回合存活；
  不写 Robocode 数据文件。
- 单元测试用 kotlin.test（core 数学、波、危险、冲浪、KNN 都有覆盖）。

## 4. 下一步方向

按价值排序（详见 `bots/proteus/docs/roadmap.md` 与 `docs/training.md`）：

1. **更大的数据集 → 更好的嵌入**：expert/top 对手也采集（
   `scripts/collect_dataset.py -c expert top`），样本量上去后重训
   `Features.WEIGHTS`；进一步按对手分训专家嵌入。
2. **危险模型权重离线化**：把 M8 的训练器复用到 `DangerEstimator` 的模型
   权重与门控阈值。
3. **PathSurfer 排查**（仍禁用，`Mover.MOVEMENT_ENGINE`）：需逐 tick 遥测
   查重规划抖动，不要盲开。
4. **top tier 推进**：top 目前 16.1%，缺口仍在防守（taken/r ~49）。

## 5. 经验教训（别再踩一遍）

1. **波时序**：子弹在 `loadCommands`（回合开始）于敌人**上一 tick 位置**
   出膛，同回合 `updateBullets` 就移动第一段；碰撞检测用机器人**移动前**
   的方块。半径公式 `r(t) = (t − fireTime + 1) × speed`。敌方波起点 =
   敌人 T−1 位置；我方波起点 = 我方当前位置、fireTime = T+1。
2. **幻影波来源**：机器人碰撞双方各掉 0.6 能量 → 开火检测必须补偿
   （`GameState.noteRobotCollision`）；我方命中也要补偿
   （`noteOurBulletHit`）。
3. **距离带符号**：航向相对「指向敌人的方位」时，负偏置才是拉近（正偏
   置推远）。M1/M2 在这里栽过一次。
4. **路径搜索负结果**：PathSurfer（best-first、计划复用、节点预算）在实
   战中稳定输给三选一冲浪（basic ~29 vs ~37；有集成后 32.7 vs 58.8）。
   它会穿过危险模型的窄缝并制造低速窗口。默认引擎
   `Mover.MOVEMENT_ENGINE = THREE_OPTION`；重开前需要逐 tick 遥测排查，
   不要盲目启用。
5. **危险密度查询必须核平滑**：原始计数直方图有空档，错 bin 就归零
   （M5 组合器统一走 `GuessFactorBins.mass`）。
6. **Robocode 1.11**：`setFire()` 返回 void；要拿 `Bullet` 用
   `setFireBullet()`（与 setFire 同队列、次回合出膛）。机器人主类必须
   具体（`zen.X` 外壳 + `zen.x.X` 抽象实现），否则 "invalid robot"。
7. **A/B 对比**：35 回合噪声 ±5 APS；结论性数字用 100 回合。调参一次改
   一个变量，避免死亡螺旋。
8. **编辑纪律**：改完立刻 `./gradlew :bots:proteus:spotlessApply` 再编
   译；edit 工具按原文精确匹配，spotless 重排后要重读文件。

## 6. 仓库状态

- Proteus 最新：`e69b5f4`（M8）。
- 参考机器人已部署在 `robocode/robots/`（basic/classic 可直接 duel；
  `top` catalog 含 BeepBoop 本体）。
- Proteus 版本号：`bots/proteus/src/main/resources/zen/Proteus.properties`
  （当前 0.8.0）。
