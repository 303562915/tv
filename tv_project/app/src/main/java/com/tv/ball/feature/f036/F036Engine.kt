package com.tv.ball.feature.f036

import kotlin.random.Random

/**
 * F036「随机播放与权重调度」的能力层。
 *
 * 纯 Kotlin，不引用任何 Android API：随机源由构造参数 [ShuffleScheduler.seed] 决定，
 * 同一个种子跑出来的序列必然一致，所以界面能直接演示「固定种子 + 20 次抽取」，
 * 单测也可以对具体序列做断言。
 */
object F036Engine {

    /** 权重下限：任何曲目至少要有一点被抽中的机会。 */
    const val MIN_WEIGHT: Int = 1

    /** 权重上限：挡一下手滑，也避免加权抽样把某一首压成几乎必中。 */
    const val MAX_WEIGHT: Int = 9

    /** 队列里的一首曲目。weight 越大，被抽中的概率越高。 */
    data class Track(
        val id: Long,
        val title: String,
        val weight: Int
    ) {
        /** 真正参与抽样的权重，越界值在这里夹回 [MIN_WEIGHT]..[MAX_WEIGHT]。 */
        val effectiveWeight: Int get() = weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
    }

    /**
     * 一次抽取的结果。
     *
     * [roundIndex] 是所属轮次（从 1 起），[roundRestarted] 标记这次抽取是否正好触发了
     * 「本轮单曲全部播完、重新洗牌」，界面用它把轮次边界标出来。
     */
    data class Draw(
        val index: Int,
        val track: Track,
        val roundIndex: Int,
        val roundRestarted: Boolean
    )

    /**
     * 随机播放调度器。
     *
     * 规则三条：
     * 1. 权重抽样——候选曲目按 [Track.effectiveWeight] 加权，权重高的更容易被抽到；
     * 2. 同一轮内不重复——抽中的曲目从本轮牌堆里移走；
     * 3. 本轮用完才重置——牌堆空了才重新装填，而且重装后第一首仍然排除上一首，
     *    这样轮次交界处也不会出现「同一首连着放两遍」。
     */
    class ShuffleScheduler(tracks: List<Track>, val seed: Long) {

        /** 完整播放列表，决定一轮的容量。 */
        private val all: List<Track> = tracks.toList()

        /** 本轮还没播过的曲目，用掉一个删一个。 */
        private val pool: ArrayList<Track> = ArrayList(all)

        /** 注入固定种子的随机源，保证同一 seed 下可复现。 */
        private val random: Random = Random(seed)

        private var lastPicked: Track? = null
        private var roundIndex: Int = 1
        private var lastRestarted: Boolean = false

        /** 一轮多少首。 */
        val roundSize: Int get() = all.size

        /** 本轮已播几首。 */
        val playedInRound: Int get() = all.size - pool.size

        /** 本轮还剩几首。 */
        val remainingCount: Int get() = pool.size

        /** 当前轮次，从 1 起。 */
        val round: Int get() = roundIndex

        /** 上一次 [next] 是否刚好触发了本轮重置。 */
        val lastWasRoundRestart: Boolean get() = lastRestarted

        /** 抽下一首。列表为空属于调用方错误，直接抛。 */
        fun next(): Track {
            check(all.isNotEmpty()) { "播放列表为空，无法抽取" }

            lastRestarted = false
            if (pool.isEmpty()) {
                pool.addAll(all)
                roundIndex += 1
                lastRestarted = true
            }

            val previous = lastPicked
            // 同轮内牌堆里不可能有已播曲目，重复只能在「轮次交界」处冒出来：
            // 刚重装完牌堆时上一首还在池子里，这里把它剔掉即可。
            // 只有整个列表就一首时剔完为空，那时才退化成允许重复。
            val candidates: List<Track> = if (previous == null) {
                pool.toList()
            } else {
                pool.filter { it.id != previous.id }.ifEmpty { pool.toList() }
            }

            val picked = weightedPick(candidates)
            pool.remove(picked)
            lastPicked = picked
            return picked
        }

        /**
         * 连续抽 [count] 次，返回完整序列（含轮次信息）。
         * 界面拿它一次渲染出 20 行，测试也直接对序列做断言。
         */
        fun playAllSequence(count: Int): List<Draw> {
            if (count <= 0) return emptyList()
            val out = ArrayList<Draw>(count)
            for (i in 1..count) {
                val track = next()
                out.add(Draw(i, track, roundIndex, lastRestarted))
            }
            return out
        }

        /** 按权重掷点：先求和，再在 [0, total) 上取一个整数落点。 */
        private fun weightedPick(candidates: List<Track>): Track {
            var total = 0
            for (track in candidates) total += track.effectiveWeight

            var roll = random.nextInt(total)
            for (track in candidates) {
                roll -= track.effectiveWeight
                if (roll < 0) return track
            }
            // 理论上到不了这里；兜底给最后一个，避免静默返回错的类型。
            return candidates.last()
        }
    }

    /**
     * 找连续重复的位置，返回 1 起的位置下标（第 2 项与第 1 项相同就记 2）。
     * 位置按「抽取序号」报，界面能直接对上序列行。
     */
    fun duplicateIndexes(tracks: List<Track>): List<Int> {
        val hits = ArrayList<Int>()
        for (i in 1 until tracks.size) {
            if (tracks[i].id == tracks[i - 1].id) hits.add(i + 1)
        }
        return hits
    }

    /**
     * 统计每首曲目在 [draws] 里被抽中的次数，顺序沿用 [tracks]。
     * 权重调大后能一眼看出次数变化，比只显示概率直观。
     */
    fun distribution(draws: List<Draw>, tracks: List<Track>): List<Pair<Track, Int>> {
        val counts = HashMap<Long, Int>()
        for (draw in draws) {
            counts[draw.track.id] = (counts[draw.track.id] ?: 0) + 1
        }
        return tracks.map { it to (counts[it.id] ?: 0) }
    }
}