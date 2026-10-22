package com.water.widget

import org.json.JSONArray
import org.json.JSONObject

enum class TaskPlatform { MAIN, APP }

data class PlannedMission(
    val refId: String,
    val name: String,
    val score: Int,
    val limit: Int,
    val platform: TaskPlatform
)

data class MissionWorkItem(
    val refId: String,
    val name: String,
    val score: Int,
    val round: Int,
    val total: Int,
    val platform: TaskPlatform
)

data class TaskScoreProgress(val completed: Map<String, Int>, val totalScore: Int?)

class TaskPointsTracker(private val before: Int?) {
    var total: Int = 0
        private set

    fun record(after: Int?): Int? {
        val increase = TaskMissionPlanner.verifiedIncrease(before, after) ?: return null
        if (increase < total) return null
        val added = increase - total
        total = increase
        return added
    }
}

object TaskMissionPlanner {
    fun progress(data: JSONObject): TaskScoreProgress {
        val score = requireNotNull(data.optJSONObject("accScoreRsp")) { "缺少账户积分进度" }
        val limits = if (!score.has("limits") || score.isNull("limits")) JSONArray()
            else requireNotNull(score.optJSONArray("limits")) { "任务次数格式错误" }
        val completed = buildMap {
            for (index in 0 until limits.length()) {
                val limit = requireNotNull(limits.optJSONObject(index)) { "任务次数条目格式错误" }
                val refId = limit.optString("refId")
                require(refId.isNotBlank()) { "任务次数缺少 refId" }
                val count = requireNotNull(limit.opt("limit")?.toString()?.toIntOrNull()) { "任务次数缺失" }
                require(count >= -1) { "任务次数无效" }
                put(refId, if (count == -1) Int.MAX_VALUE else count)
            }
        }
        return TaskScoreProgress(completed, score.opt("totalScore")?.toString()?.toIntOrNull()?.takeIf { it >= 0 })
    }

    fun verifiedIncrease(before: Int?, after: Int?): Int? =
        if (before == null || after == null || after < before) null else after - before

    fun merge(mainMissions: JSONArray?, appMissions: JSONArray?): List<PlannedMission> {
        val out = mutableListOf<PlannedMission>()
        val seen = mutableSetOf<String>()

        fun addAll(arr: JSONArray?, platform: TaskPlatform) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val refId = obj.optString("refId", "")
                if (refId.isBlank() || !seen.add(refId)) continue
                out += PlannedMission(
                    refId = refId,
                    name = obj.optString("name", obj.optString("title", "任务")),
                    score = obj.optInt("score", 0),
                    limit = obj.optInt("limit", 0),
                    platform = platform
                )
            }
        }

        addAll(mainMissions, TaskPlatform.MAIN)
        addAll(appMissions, TaskPlatform.APP)
        return out
    }

    fun buildWork(missions: List<PlannedMission>, doneCount: Map<String, Int>, maxItems: Int = 30): List<MissionWorkItem> {
        val out = mutableListOf<MissionWorkItem>()
        for (mission in missions) {
            if (shouldSkip(mission.name)) continue
            if (mission.refId.isBlank() || mission.score <= 0 || mission.limit <= 0) continue
            val remaining = (mission.limit - (doneCount[mission.refId] ?: 0)).coerceAtLeast(0)
            if (remaining <= 0) continue
            for (round in 1..remaining) {
                out += MissionWorkItem(
                    refId = mission.refId,
                    name = mission.name,
                    score = mission.score,
                    round = round,
                    total = remaining,
                    platform = mission.platform
                )
                if (out.size >= maxItems) return out
            }
        }
        return out
    }

    private fun shouldSkip(name: String): Boolean = listOf("免费权益", "借贷", "贷款").any { name.contains(it) }
}
