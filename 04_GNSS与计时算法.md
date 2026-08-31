# 04 GNSS 与计时算法

## 1. 定位来源

OPPO Find X8 Ultra 支持北斗 B1i/B1c/B2a、GPS L1/L5，以及 GLONASS、Galileo、QZSS。App 通过 Android LocationManager 请求 GNSS 位置，系统自行融合可用星座。

App 不做：

- 强制只用 GPS 或只用北斗。
- 自行从原始载波数据解算经纬度。
- 把高德道路纠偏结果用于计时判定。

App 可通过 GnssStatus 统计当前可见/参与定位的 GPS、北斗等卫星，用于诊断和质量展示。

## 2. LocationFix 规范化

每个输入点至少包含：

- latitude / longitude（WGS84）
- elapsedRealtimeNanos（计时主时钟）
- wallTimeMillis（仅展示日期）
- horizontalAccuracyMeters
- speedMetersPerSecond（如可用）
- bearingDegrees 与 bearingAccuracy（如可用）
- provider、mock 标志
- GNSS 星座统计快照（可低频关联）

所有分段时间基于 `elapsedRealtimeNanos`。该时钟单调递增、锁屏和深度睡眠时仍继续，不能跨设备重启比较。

## 3. 采样策略

- 准备和计时状态目标更新周期：约 1 秒；系统不保证精确周期，算法必须接受不均匀采样。
- 基准记录同样使用高精度。
- 完成/取消后立即停止高频定位。
- GNSS 尚未稳定时保持 ARMED 前置等待，不要用陈旧 last-known location 触发。

## 4. 基础过滤

点进入 TimingEngine 前执行：

1. 拒绝缺少经纬度、精度或单调时间的点。
2. 拒绝时间倒退和重复时间戳。
3. 标记 mock location；测试构建允许，正式成绩默认低可信。
4. 精度 ≤25 m：良好；25～50 m：降级；>50 m：保存但暂不用于确认节点。阈值由实测调整。
5. 检查不可能跳跃：依据相邻距离、时间和合理车辆速度；异常点不触发节点但保留诊断。
6. 不对用于计时的点做会引入明显延迟的重平滑；显示轨迹可单独平滑。

## 5. 计时点数据

每个 Checkpoint 包含：

- centerLat/Lon
- order：0=A、1=S1、2=S2、3=B
- radiusMeters，默认候选 35 m
- preferredBearing（从基准局部切线估计，可为空）
- bearingTolerance（默认宽松）
- gateHalfWidth（虚拟横线半宽）
- checkpointVersion

## 6. 混合触发算法

仅用圆形“进入半径”会把时间提前；仅用一条横线又可能无法适应绕行。因此采用混合方式。

### 6.1 首选：虚拟横线交点

1. 根据基准经过该点的局部切线生成垂直计时线。
2. 相邻两个有效 GNSS 点构成运动线段。
3. 若运动线段与计时线在允许宽度内相交，计算线性插值比例：

```
t_cross = t_prev + ratio * (t_curr - t_prev)
```

4. 检查交点距离中心、状态顺序及大致行驶方向。
5. 得到低延迟、可立即显示的通过时间。

### 6.2 回退：最近通过点

如果改道导致接近方向不同、没有可靠横线交点：

1. 进入 checkpoint radius 后建立 Candidate。
2. 持续保存距离最小的点及其前后点。
3. 当车辆明显离开或连续距离增加时确认已经通过。
4. 用最近点或局部二次/线性插值估计最接近中心的时刻。
5. 标注 triggerMode=CLOSEST_APPROACH，质量略低于 LINE_CROSSING。

### 6.3 防误触规则

- 只处理当前状态期待的下一个计时点。
- 同一 checkpointVersion 每次 session 只能确认一次。
- A 若打开 App 时已在范围内：要求先驶离再重新进入。
- 低速堵车不能简单拒绝；速度仅作为辅助，不作为唯一门槛。
- 明显反向通过时拒绝；方向不确定时不因 bearing 缺失直接丢弃。
- Candidate 超时或离开但最近距离过大：取消候选，继续等待。

## 7. 分段时间

- S1 duration = t(S1) - t(A)
- S2 duration = t(S2) - t(S1)
- S3 duration = t(B) - t(S2)
- Total = t(B) - t(A)

累计差和单段差分开：

- Sector delta：本段与基准同段之差。
- Cumulative delta：到当前 checkpoint 的累计时间与基准累计时间之差。

UI 必须标清当前展示哪一种，默认大号差值使用累计差，Sector 行使用单段差。

实时差值在每个合格 GNSS 点（目标约 1 Hz）上，将当前位置投影到月/周参考轨迹的合理前进窗口内。无法匹配时保留最近确认值，不使用无约束最近点。主计时数字由单调时钟独立刷新到 0.01 秒，与 GNSS 更新频率无关。

## 8. 纪录判定与不确定度

GNSS 位置不是赛车转发器。即使内部时间为纳秒，也不代表通过点达到毫秒级真实精度。

建议：

- 内部保存纳秒。
- UI 实时显示到 0.1 秒；结果可显示 0.001 秒但同时提供“GNSS 估计精度”，最终由产品确认。
- 纪录判定使用容差，而不是任何 1 ms 改善都算纪录。
- 初始容差建议至少 0.5 秒，并结合 `accuracy / max(speed, floorSpeed)` 估计空间误差造成的时间误差。
- 改善小于容差时显示“基本持平”而不是紫色。

颜色算法（同路线、同计时点版本，只使用本次出发前的有效记录）：

```
if invalid or confidenceLow -> NEUTRAL
else if duration < monthlyBest - tolerance -> PURPLE
else if duration < clippedWeeklyBest - tolerance -> GREEN
else if duration > clippedWeeklyBest + tolerance -> YELLOW
else -> NEUTRAL_TIE
```

月按自然月统计；周从周一开始，但不得早于当月 1 日。总程与 S1/S2/S3 分别取各自最快。

纪录比较必须使用“本次开始前的 previousBest”，避免本次先写数据库后再与自身比较。

## 9. 路线记录与抽象图

- 所有原始点按序保留，带 sessionId。
- 计时有效性不依赖与基准轨迹的距离。
- 显示前转局部平面坐标，执行简化与轻度平滑。
- 基准轨迹和本次轨迹采用同一投影框，才能正确显示分叉。
- 简化算法必须锁定四个 checkpoint 邻域和轨迹首尾。
- 原始轨迹、计时轨迹、显示轨迹是三个概念，禁止互相覆盖。

## 10. 质量评分

每次行程保存：

- goodFixRatio
- maxGapSeconds
- averageAccuracyMeters / p95AccuracyMeters
- triggerMode per checkpoint
- estimatedTimingUncertainty per checkpoint
- constellation counts summary
- interruption count

建议等级：

- A：四点均可靠交线，gap 小，精度稳定。
- B：存在最近点回退或短 gap，但结论可用。
- C：可查看但不参与纪录。

阈值必须通过 OPPO Find X8 Ultra 实车数据校准，不在编码前假装精确。
