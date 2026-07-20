# Proteus 里程碑路线

> 每个里程碑有明确的范围与量化验收，按序推进；验收用 `just duel` 对指定
> catalog 跑 35+ 回合，结果记录在本文「验收记录」一节。架构依据见
> `docs/architecture.md`。对照基线：同版本 `zen.Mirage`（工作区现役最强）与
> `top` catalog。

## M1 骨架（已完成）

范围：模块接线（Gradle、部署、properties）、`core` / `state` / `control` /
`radar` / `move` / `aim` 分层、雷达 infinity lock、能量差开火检测、距离带轨
道移动、线性预测枪、单元测试。

验收（已达成）：

- `just build` / `just lint` 通过，`just deploy proteus` 可加载。
- 对 `RaikoNano` 10 回合：APS 45.0%（基线锚点，不作门槛）。

## M2 波与真冲浪

范围：`wave.Wave` / `wave.Waves` / `wave.GuessFactorBins`；敌方开火建波；精确
交集；访问统计的经验危险；前进/后退/停车真冲浪（最近两条波）；中弹与子弹相
撞时按威力与位置匹配波并学习。

验收：`basic` catalog APS ≥ 75%，且生存率显著高于 M1；对 `RaikoNano` APS
≥ 70%。

## M3 路径冲浪

范围：`MovementSim` 逐 tick 精确模拟；`PathSurfer` best-first 搜索（计划复
用、启发剪枝、三波深度，迭代加深 + tick 预算）；`PathLibrary` 预计算路径
库；位置乘子（距离、MEA 利用）；bullet shadow 计入危险（含跨 tick 半权重阴
影）；移动侧向瞄准发布未来状态并对账。

验收：`basic` catalog APS ≥ 85%；`classic` catalog APS ≥ 60%；无 skipped
turn（诊断统计）。

## M4 KNN 瞄准

范围：`knn.*`（自研 KD 树 + 嵌入）；每 tick 虚拟波；`WaveFeatures` 特征池；
主枪 / 反冲浪枪双 KNN 按我方命中率区间硬切换；didHit / didCollide 标记；扫
描线密度峰选点；开火前一 tick 的临时瞄准波用预测出膛点。

验收：`classic` catalog APS ≥ 70%；对 `BasicGFSurfer`（会躲的对手）APS 较
M3 明显提升。

## M5 危险集成

范围：模拟枪模型（HOT / 线性 / 圆形 / 均值线性 / 当前 GF）；KNN 危险模型
（通用 + 专家）；flattener；反 PM 模拟器；门控激活 + 动态权重 + 平滑 + 阴影
折扣的组合管线；按波缓存危险。

验收：`expert` catalog APS ≥ 45%；对 `Diamond` 类强冲浪者的生存率不低于
Mirage 同版本。

## M6 得分系统

范围：`aim.FirePower` 期望得分威力选择；`aim.ShadowAim` 开火瞬间候选角联合
评分（命中概率 ÷ 阴影后危险）；残局策略（低能量保能量、恰好击杀威力）。

验收：`expert` catalog APS ≥ 55%；`top` catalog 打出非平凡 APS（≥ 25%）。

## M7 对手分类与护盾

范围：`strategy.Strategy`（anti-ram / anti-mirror / anti-HOT / 残局撞击收
割）；护盾模式（敌人瞄准在线分类 + 拦截计划 + 退出条件）；rambot 特化（模
拟 rammer、未来枪热波、近垂直逃向角）。

验收：`roborumble-100` catalog 全量 APS 达到里程碑目标（目标值在 M6 验收后
按差距定）；对已知 ram / mirror 对手的专项 duel 验证分类生效。

## M8 离线训练闭环

范围：`diag` 数据集导出（float32）；`train/` 离线嵌入训练（SGD，交叉熵对
KNN 核密度）；学得的参数生成为 Kotlin 常量回读；训练 / 回读 / 验证流程文档
化。

验收：离线训练后的嵌入对 `expert` / `top` catalog 有稳定正收益（与手工权重
A/B 对比）。

## 验收记录

| 日期 | 里程碑 | 对手 / catalog | 回合 | APS | PWIN | 备注 |
|---|---|---|---|---|---|---|
| 2026-07-20 | M1 | RaikoNano | 10 | 45.0% | 0% | 骨架基线 |
