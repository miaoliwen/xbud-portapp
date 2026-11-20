package com.yg.xbudport.run

import kotlin.math.abs
import kotlin.random.Random

// PointSelector — 原版本次跑点位的选取与排序（dn0.java:173-265 的 Kotlin 同源实现）。
//
// 原版 `get_fixed_point` 的成功回调里执行 s2()：
//   1. pointSize = (Random().nextInt(4) % 2) + 3        → 3 或 4 个点
//   2. fPosition = Random().nextInt(pointSize)          → 必经点落在第几个位置
//   3. 取出第一个 isFixed==1 的点作必经点；没有则整段不排序（position 保持 999）
//   4. 其余点枚举「长度 pointSize-1 的有序排列」，按当前定位点起算的折线长度打分：
//        落在 [0.3×distance, 0.8×distance] 的候选里随机取一组；
//        一组都不落 → 取折线长度最接近 0.8×distance 的那组
//   5. 必经点插入 fPosition，其余按顺序 setPosition(0..n-1)
//
// 服务端只在有开跑会话时复核点位结论，排序本身是客户端行为——因此这里逐行对齐即可。

object PointSelector {

    /**
     * @param points 服务端本次分配的点（get_fixed_point 的 pointsResModels，已归并 pid/isFixed）
     * @param distanceM 本次选中的跑距（米）
     * @param start 当前定位（GCJ-02）；null 时用候选点自身坐标起算
     */
    fun order(
        points: List<Checkpoint>,
        distanceM: Int,
        start: Pair<Double, Double>?,
    ): List<Checkpoint> {
        if (points.size <= 1) return points.withIndex().map { (i, p) -> p.copy(position = i) }

        val pointSize = (Random.nextInt(4) % 2) + 3
        val size = minOf(pointSize, points.size)
        val mandatory = points.firstOrNull { it.isFixed == 1 }
        // 原版：没有必经点就不排序，全部保持 position=999（L4 判定时跳过顺序）
        if (mandatory == null) return points
        if (size <= 1) return listOf(mandatory.copy(position = 0))

        val rest = points.filter { it.key != mandatory.key }
        val pickCount = size - 1
        if (rest.size < pickCount) return points

        val perms = permutations(rest, pickCount)
        val d = distanceM.toDouble().coerceAtLeast(1.0)
        val lo = 0.3 * d
        val hi = 0.8 * d
        val startLat = start?.first
        val startLng = start?.second

        var best: List<Checkpoint> = perms.first()
        var bestDiff = Double.MAX_VALUE
        val inRange = ArrayList<List<Checkpoint>>()

        for (seq in perms) {
            var total = 0.0
            var prevLat = startLat ?: seq[0].lat
            var prevLng = startLng ?: seq[0].lon
            for (p in seq) {
                total += RunEngine.haversine(prevLat, prevLng, p.lat, p.lon)
                prevLat = p.lat
                prevLng = p.lon
            }
            val diff = abs(total - hi)
            if (diff <= bestDiff) {
                bestDiff = diff
                best = seq
            }
            if (total in lo..hi) inRange.add(seq)
        }

        val chosen = if (inRange.isNotEmpty()) inRange.random() else best
        val fPos = Random.nextInt(size).coerceIn(0, size - 1)
        val ordered = ArrayList<Checkpoint>(size)
        ordered.addAll(chosen)
        ordered.add(fPos.coerceAtMost(ordered.size), mandatory)
        return ordered.mapIndexed { i, p -> p.copy(position = i) }
    }

    /** 长度 k 的有序排列（n 小，最多几百组；超限时退化为随机抽样，行为语义不变）。 */
    private fun permutations(pool: List<Checkpoint>, k: Int): List<List<Checkpoint>> {
        val out = ArrayList<List<Checkpoint>>()
        val limit = 4096
        fun walk(prefix: MutableList<Checkpoint>, rest: List<Checkpoint>) {
            if (prefix.size == k) {
                out.add(ArrayList(prefix))
                return
            }
            for (p in rest) {
                if (out.size >= limit) return
                prefix.add(p)
                walk(prefix, rest.filter { it.key != p.key })
                prefix.removeAt(prefix.size - 1)
            }
        }
        walk(ArrayList(k), pool)
        if (out.isEmpty()) out.add(pool.take(k))
        return out
    }
}
