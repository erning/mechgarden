# DC / KNN 瞄准详解

> 来源：[RoboWiki / Dynamic Clustering](https://robowiki.net/wiki/Dynamic_Clustering)、
> [RoboWiki / K-Nearest Neighbor](https://robowiki.net/wiki/K-Nearest_Neighbor)、
> [RoboWiki / DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT)、
> [RoboWiki / GuessFactor Targeting (traditional)](https://robowiki.net/wiki/GuessFactor_Targeting_(traditional))。
>
> DC / KNN 是现代 Robocode 1v1 高端瞄准的核心技术之一，
> 也是 Ronin 主炮的基础。

## 1. 核心思想：找“最像现在”的过去

每次朝敌人开火时，机器人可以记录一组“当时的情况”以及“这发子弹最终打中了哪里”。

下一次开火前，机器人问：

> 历史上哪几次情况和现在最像？它们都打中了哪里？

**KNN（K-Nearest Neighbor）** 的回答方式是：

1. 把当前情况表示成一个特征向量。
2. 在历史记录中找到 K 个距离最近的向量。
3. 看这 K 个邻居的命中位置在哪里。
4. 朝“邻居们最密集命中”的方向开火。

## 2. 三个关键要素

### 2.1 特征（Attributes）

特征是描述“当前战场状态”的数字。常见的有：

| 特征 | 符号/名称 | 含义 |
|------|----------|------|
| 敌我距离 | distance | 当前离敌人多远 |
| 横向速度 | lateral velocity | 敌人垂直于视线的速度 |
| 接近速度 | advancing velocity | 敌人朝向或远离你的速度 |
| 加速度 | acceleration | 速度变化量 |
| 方向改变 | heading change / turn rate | 敌人是否正在转向 |
| 离墙距离 | wall distance | 敌人离最近墙壁多远 |
| 子弹飞行时间 | BFT（bullet flight time） | 子弹预计多久到达 |
| 当前 Guess Factor | current GF | 敌人当前在你视野中的相对角度 |

特征选得好不好，直接决定 KNN 能不能找到真正“类似”的历史记录。

### 2.2 距离函数

不同特征的量纲不同。距离 500 和速度 8 不能直接相减，否则距离会压过速度。

所以要给每个特征加权：

```text
Manhattan(a, b) = Σ weight[i] × |a[i] − b[i]|

Euclidean(a, b) = sqrt(Σ weight[i] × (a[i] − b[i])²)
```

**DC（Dynamic Clustering）** 的关键含义就在这些权重上：

- 权重不是写死的，而是可以针对对手动态调整。
- 不同对手对不同特征的敏感度不同。
- Ronin 嵌入了多组预训练 weight profile，实战中选择对当前对手最准的一组。

### 2.3 K 与邻居选择

K 表示一次查询找多少个邻居。

- K 太小：容易被噪声误导。
- K 太大：会把不太相关的情况也纳入，瞄准变模糊。
- 高端实现通常不是固定 K，而是找一个相似度阈值内的所有邻居，
  再用 kernel 按相似度加权。

Ronin 使用 **three-way quickselect** 在 O(n) 时间内找出最近邻，避免对全部历史记录排序。

## 3. 从邻居到瞄准角

找到邻居后，要把它们的命中信息综合成一个瞄准方向。典型流程：

### 3.1 每个邻居携带一个命中 GF

历史记录里保存的是：

```text
{ 特征向量, 实际命中的 Guess Factor }
```

Guess Factor（GF）把敌人可能的最大逃逸范围归一化到 [-1, 1]。

### 3.2 按相似度加权

离得越近的邻居权重越高。常用高斯核：

```text
w = exp(−distance² / kernelWidth)
```

### 3.3 Kernel Density Estimation

把所有邻居的 GF 按权重堆成一个连续分布，然后找峰值：

```text
density(gf) = Σ w_i × kernel(gf − gf_i)
aimGF = argmax_gf density(gf)
```

这比简单投票更平滑，也更能处理 GF 连续变化的情况。

### 3.4 考虑车身宽度

更精确的做法（如 `DrussGT`）不是找一个点，而是考虑敌人的车身宽度：

- 每个邻居给出一个“命中区间”，而不是单一 GF。
- 找这些区间重叠最多的区域。
- 这样瞄准点更稳定，也更能覆盖敌人的实际碰撞箱。

### 3.5 转回炮管角度

得到 aimGF 后，根据当前敌我位置、方向、最大逃逸角，把它转回真实的炮管转角：

```text
aimAngle = headOnAngle + direction × aimGF × maxEscapeAngle
```

## 4. 为什么叫 Dynamic Clustering，不叫 k-means

这两个概念容易混淆：

| | k-means | Dynamic Clustering |
|--|---------|-------------------|
| 聚类时机 | 训练阶段预先分成 K 个簇 | 查询时临时从所有数据里找近邻 |
| 簇的含义 | 固定的几何区域 | 以当前查询点为中心的动态邻居集合 |
| 在 Robocode 中的用法 | 较少单独使用 | 几乎等同于 KNN + kernel density + 动态权重 |

所以 Robocode 社区说的 DC，本质上就是**在线 KNN 查询 + 动态加权**。

## 5. DC / KNN 与 VCS 的对比

| | VCS（Visit Count Stats） | DC / KNN |
|--|-------------------------|----------|
| 数据表示 | 把特征空间切成格子，计数 | 保存每条历史记录完整特征 |
| 内存结构 | 多维数组 | 列表 / KD-Tree |
| 特征数量 | 受维度爆炸限制 | 可以很多，靠距离函数处理 |
| 细分能力 | 格子大小决定精度 | 邻居密度自然决定精度 |
| 冷启动 | 有数据格子就有统计 | 数据少时近邻可能不准 |
| 对变化的响应 | 需要重新统计 | 滚动数据后可快速跟踪 |
| 代表实现 | 传统 GuessFactor gun | `DrussGT`、`Ronin` |

一句话：

> VCS 是“把情况分格子，看哪个格子命中多”；
> DC / KNN 是“找历史上最像这次的情况，看那时候命中在哪”。

## 6. Ronin 中的 DC / KNN

Ronin 的 DC gun 是主要瞄准方式。对应 `AGENTS.md` 中的描述：

> “DC gun（dynamic-clustering KNN targeting）作为 primary aim，
> 有 pretrained distance-weight profiles 嵌入在机器人代码里。”

具体实现要点：

1. **多组 weight profile**：预训练好几套特征权重组合，针对不同类型对手。
2. **在线评分**：根据实际命中/未命中反馈，给 profile 打分。
3. **切换延迟**：只有在观察到足够多反馈后才切换主 profile，避免噪声。
4. **O(n) 最近邻**：用 three-way quickselect 找 KNN，不排序全部历史记录。
5. **复用缓冲区**：`scoreBuf` / `histBuf` 数组跨扫描复用，减少分配。

```text
训练阶段（离线）：
  用大量对战数据训练多组 weight profile。

实战阶段（每发子弹）：
  当前特征 → 用各 profile 分别 KNN → 得到候选 GF
           → 根据历史反馈选最佳 profile → 开火
           → 记录命中结果 → 更新 profile 评分
```

## 7. 优缺点

### 7.1 优点

- **特征利用率高**：可以同时使用十几个甚至更多特征。
- **适应性强**：滚动平均后能快速跟踪对手变化。
- **数据越多越准**：不像 VCS 受格子数量硬限制。
- **扩展性好**：加入新特征不需要重写整个数据结构。

### 7.2 缺点

- **数据量大时搜索慢**：需要 KD-Tree、quickselect 或 ball tree 优化。
- **调参复杂**：特征选择、权重、kernel 宽度都需要大量实验。
- **冷启动弱**：开局几轮数据少，可能不如简单枪稳定。
- **容易过拟合**：如果权重对某个对手太特化，换对手后可能表现下降。

## 8. 什么时候用 DC / KNN

适合：

- 已经能稳定做 wave tracking 和 precise intersection。
- 有足够计算预算做 KNN 查询。
- 希望瞄准能针对对手自适应调整。
- 机器人框架支持多 profile 切换和在线反馈。

不太适合：

- 刚入门时想快速得到一个能打的枪（先学 VCS / GF）。
- 数据非常少、每局对手都完全不同的场景。
- 计算资源紧张，无法做复杂查询时。

## 9. 一句话总结

> **DC / KNN 瞄准 = 把当前战场情况数字化 → 在历史记录里找最像的 K 次 → 看它们当时打中哪里 → 朝最密集的方向开火。**
>
> Ronin 靠它实现对手特化的精准瞄准。

## 参考

- [RoboWiki / Dynamic Clustering](https://robowiki.net/wiki/Dynamic_Clustering)
- [RoboWiki / K-Nearest Neighbor](https://robowiki.net/wiki/K-Nearest_Neighbor)
- [RoboWiki / DrussGT/Understanding DrussGT](https://robowiki.net/wiki/DrussGT/Understanding_DrussGT)
- [RoboWiki / GuessFactor Targeting (traditional)](https://robowiki.net/wiki/GuessFactor_Targeting_(traditional))
- `docs/flattening-and-adaptive-gun.md` — adaptive gun、anti-surfer gun 与 DC/KNN 的关系
