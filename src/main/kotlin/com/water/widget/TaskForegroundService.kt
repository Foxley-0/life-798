package com.water.widget

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/**
 * 积分任务的唯一执行入口。
 *
 * Activity 仅负责发送启动请求；任务批次、限速等待和网络回调全部由此前台服务持有，
 * 因此旋转屏幕、离开页面或 Activity 被回收都不会中断当前批次。
 */
class TaskForegroundService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val taskRateLimiter = TaskExecutionCoordinator.tokenRateLimiter
    private var taskLease: TaskExecutionCoordinator.Lease? = null
    private var generation = 0
    private var running = false
    private var stoppingNormally = false
    private var completedLanes = 0
    private var hadFailures = false
    private var taskWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationScope.launch {
            TaskRunRepository.state.collect { state ->
                if (running && state.running) updateNotification(state)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_RUN_TASKS) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running || TaskRunRepository.state.value.running) return START_NOT_STICKY

        val accounts = AccountStore.list(this).filter { it.hasToken() || it.hasAppToken() }
        if (accounts.isEmpty()) {
            startForegroundCompat(buildNotification(TaskRunState(running = true)))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val lease = TaskExecutionCoordinator.tryAcquire(accounts)
        if (lease == null) {
            val message = "上一次任务请求仍在收尾，请约半分钟后重试。"
            TaskRunRepository.fail(message)
            val failedState = TaskRunRepository.state.value
            startForegroundCompat(buildNotification(failedState))
            stopForeground(STOP_FOREGROUND_REMOVE)
            updateNotification(failedState)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val plannedLanes = AccountExecutionPlanner.plan(accounts)
        val laneStates = plannedLanes.mapIndexed { index, lane ->
            TaskLaneState(
                laneId = index + 1,
                laneCount = plannedLanes.size,
                accountCount = lane.accounts.size
            )
        }
        taskLease = lease
        taskRateLimiter.clear()
        completedLanes = 0
        hadFailures = false
        running = true
        stoppingNormally = false
        val runGeneration = ++generation

        TaskRunRepository.start(laneStates)
        appendTaskLog("===== 首页任务中心：一键运行全部账号 =====")
        appendTaskLog(
            "共 ${accounts.size} 个账号，分为 ${plannedLanes.size} 个安全通道并发执行；" +
                "共享账号标识、uid 或 Token 的账号将串行执行。"
        )
        startForegroundCompat(buildNotification(TaskRunRepository.state.value))
        keepCpuAwake()

        plannedLanes.forEachIndexed { laneIndex, lane ->
            runAccountLane(
                accounts = lane.accounts,
                index = 0,
                laneId = laneIndex + 1,
                laneCount = plannedLanes.size,
                runGeneration = runGeneration
            ) {
                if (!isActive(runGeneration)) return@runAccountLane
                TaskRunRepository.completeLane(laneIndex + 1)
                completedLanes += 1
                if (completedLanes == plannedLanes.size) finishRun()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 对 dataSync 前台服务触发时限回调时停止继续调度。
     * 已发出的 HttpURLConnection 无法可靠强制取消，所以 lease 保留一个网络超时窗口。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        softCancel("任务达到系统运行时限，已安全停止，可稍后重新运行。")
    }

    override fun onDestroy() {
        releaseCpuWakeLock()
        mainHandler.removeCallbacksAndMessages(null)
        notificationScope.cancel()
        getSystemService(NotificationManager::class.java).cancel(AppNotifications.TASK_PROGRESS_ID)
        if (running && !stoppingNormally) {
            generation += 1
            running = false
            taskLease?.let(TaskExecutionCoordinator::releaseAfterInFlightNetworkGrace)
            taskLease = null
            TaskRunRepository.cancel("任务服务被系统停止，可重新运行。")
        }
        super.onDestroy()
    }

    private fun runAccountLane(
        accounts: List<Account>,
        index: Int,
        laneId: Int,
        laneCount: Int,
        runGeneration: Int,
        done: () -> Unit
    ) {
        if (!isActive(runGeneration)) return
        if (index >= accounts.size) {
            done()
            return
        }

        val account = accounts[index]
        val accountLabel = account.toString().ifBlank { "账号 ${index + 1}" }
        TaskRunRepository.updateLane(
            laneId = laneId,
            accountIndex = index + 1,
            accountLabel = accountLabel,
            currentTask = "正在加载任务",
            phase = TaskLanePhase.LOADING
        )
        appendTaskLog(
            "\n---------- [${account.phone}] 通道 $laneId/$laneCount，" +
                "账号 ${index + 1}/${accounts.size} ----------"
        )
        loadAndRunAccountTasks(account, laneId, runGeneration) { gained ->
            if (!isActive(runGeneration)) return@loadAndRunAccountTasks
            if (gained != null) appendTaskLog("  [${account.phone}] 本账号已核验增加 $gained 分")
            TaskRunRepository.updateLane(
                laneId = laneId,
                currentTask = if (index + 1 < accounts.size) "准备下一个账号" else "正在收尾",
                phase = TaskLanePhase.WAITING
            )
            postDelayed(ACCOUNT_GAP_MILLIS, runGeneration) {
                runAccountLane(accounts, index + 1, laneId, laneCount, runGeneration, done)
            }
        }
    }

    private fun loadAndRunAccountTasks(
        account: Account,
        laneId: Int,
        runGeneration: Int,
        done: (Int?) -> Unit
    ) {
        loadPlatformTaskData(account, false, runGeneration) { mainData ->
            loadPlatformTaskData(account, true, runGeneration) appLoaded@{ appData ->
                if (mainData == null && appData == null) {
                    TaskRunRepository.updateLane(laneId = laneId, currentTask = "任务列表获取失败", phase = TaskLanePhase.FAILED)
                    done(null)
                    return@appLoaded
                }
                // 仅保留本轮读取成功的平台，避免过期的小程序凭据阻断 App 签到和任务。
                val activeAccount = Account.fromJson(account.toJson()).apply {
                    if (mainData == null) token = ""
                    if (appData == null) appToken = ""
                }
                val data = mainData ?: requireNotNull(appData)
                val points = TaskPointsTracker(TaskMissionPlanner.progress(data).totalScore)
                val missions = TaskMissionPlanner.merge(mainData?.optJSONArray("missions"), appData?.optJSONArray("missions"))
                val mainCounts = mainData?.let(TaskMissionPlanner::progress)?.completed.orEmpty()
                val appCounts = appData?.let(TaskMissionPlanner::progress)?.completed.orEmpty()
                val counts = missions.associate { it.refId to
                    ((if (it.platform == TaskPlatform.MAIN) mainCounts else appCounts)[it.refId] ?: 0) }
                val work = TaskMissionPlanner.buildWork(missions, counts)
                TaskRunRepository.updateLane(laneId = laneId, currentTask = "检查每日签到", phase = TaskLanePhase.SIGNING_IN)
                runDailySignInIfNeeded(activeAccount, data, points, laneId, runGeneration) {
                    appendTaskLog("  待执行 ${work.size} 次任务（按服务端当天限额）")
                    runMissionItems(activeAccount, work, 0, points, laneId, runGeneration) {
                        verifyAccountIncrease(activeAccount, points, laneId, runGeneration, finalCheck = true, done = done)
                    }
                }
            }
        }
    }

    private fun loadPlatformTaskData(
        account: Account,
        useApp: Boolean,
        runGeneration: Int,
        done: (JSONObject?) -> Unit
    ) {
        val token = if (useApp) account.appToken else account.token
        if (token.isNullOrBlank()) {
            done(null)
            return
        }
        IlifeApi.taskDataWithToken(token, useApp) { json, err ->
            onMain {
                if (!isActive(runGeneration)) return@onMain
                val data = json?.takeIf { it.optInt("code", -999) == 0 }?.optJSONObject("data")
                var error = TokenValidity.expiredMessage(json, useApp) ?: err ?: json?.optString("msg")
                val valid = if (data == null) false else try {
                    requireNotNull(data.optJSONArray("missions")) { "任务列表格式错误" }
                    TaskMissionPlanner.progress(data)
                    true
                } catch (e: IllegalArgumentException) {
                    error = e.message
                    false
                }
                if (!valid) {
                    hadFailures = true
                    appendTaskLog("  [${account.phone}] "+(if (useApp) "App" else "支付宝")+"任务读取失败，跳过该平台: "+(error ?: "响应缺失"))
                }
                done(if (valid) data else null)
            }
        }
    }

    private fun verifyAccountIncrease(
        account: Account,
        points: TaskPointsTracker,
        laneId: Int,
        runGeneration: Int,
        finalCheck: Boolean = false,
        done: (Int?) -> Unit
    ) {
        TaskRunRepository.updateLane(laneId = laneId, currentTask = "核验累计积分变化", phase = TaskLanePhase.WAITING)
        val useApp = !account.hasToken()
        val token = if (useApp) account.appToken else account.token
        if (token.isNullOrBlank()) {
            hadFailures = true
            appendTaskLog("  [${account.phone}] 到账核验失败：无有效登录，保留此前已核验积分")
            done(null)
            return
        }
        IlifeApi.accountScoreWithToken(token, useApp) { json, _ ->
            onMain {
                if (!isActive(runGeneration)) return@onMain
                val after = json?.takeIf { it.optInt("code", -999) == 0 }?.optJSONObject("data")
                    ?.opt("totalScore")?.toString()?.toIntOrNull()?.takeIf { it >= 0 }
                val added = points.record(after)
                if (added == null) {
                    if (finalCheck) hadFailures = true
                    val reason = TokenValidity.expiredMessage(json, useApp)
                    if (reason != null) {
                        hadFailures = true
                        if (useApp) account.appToken = "" else account.token = ""
                    }
                    appendTaskLog("  [${account.phone}] 到账核验失败，保留此前已核验积分；" + (reason ?: "请稍后刷新积分明细"))
                    done(null)
                } else {
                    TaskRunRepository.addPoints(laneId, added)
                    appendTaskLog("  [${account.phone}] 已核验新增 $added 分，执行期间累计增加 ${points.total} 分（包含期间其他渠道入账）")
                    done(points.total)
                }
            }
        }
    }

    private fun runDailySignInIfNeeded(
        account: Account,
        data: JSONObject?,
        points: TaskPointsTracker,
        laneId: Int,
        runGeneration: Int,
        done: () -> Unit
    ) {
        if (!isActive(runGeneration)) return
        val dailyRsp = data?.optJSONObject("dailyRSP")
        val weekMask = data?.optJSONObject("accScoreRsp")
            ?.optJSONObject("daily")
            ?.optInt("week", 0) ?: 0
        val calendarDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val weekDay = if (calendarDay == Calendar.SUNDAY) 7 else calendarDay - 1
        val rules = dailyRsp?.optJSONArray("config")?.let { config ->
            buildList {
                for (index in 0 until config.length()) {
                    val rule = config.optJSONObject(index) ?: continue
                    add(
                        DailySignInRule(
                            weekMask = rule.optInt("rule", 0),
                            score = rule.optInt("score", 0),
                            description = rule.optString("msg", "")
                        )
                    )
                }
            }
        }.orEmpty()
        val plan = DailySignInPlanner.plan(
            weekMask = weekMask,
            weekDay = weekDay,
            adId = dailyRsp?.optString("adId", ""),
            score = dailyRsp?.optInt("score", 5) ?: 0,
            rules = rules
        )
        when {
            plan.adId.isBlank() -> {
                appendTaskLog("  [${account.phone}] 每日签到: 无签到数据，跳过")
                done()
            }
            plan.alreadySigned -> {
                appendTaskLog("  [${account.phone}] 每日签到: 今日已签到")
                done()
            }
            else -> {
                appendTaskLog("  [${account.phone}] 每日签到: 未签到，优先执行")
                sendDailySignIn(account, plan, points, laneId, runGeneration, retry = false, done = done)
            }
        }
    }

    private fun sendDailySignIn(
        account: Account,
        plan: DailySignInPlan,
        points: TaskPointsTracker,
        laneId: Int,
        runGeneration: Int,
        retry: Boolean,
        done: () -> Unit
    ) {
        if (!isActive(runGeneration)) return
        val token = if (account.hasToken()) account.token else account.appToken
        if (token.isNullOrBlank()) {
            hadFailures = true
            appendTaskLog("    [${account.phone}] 签到失败: 无可用 Token，继续任务")
            done()
            return
        }

        TaskRunRepository.updateLane(
            laneId = laneId,
            currentTask = if (retry) "重试每日签到" else "每日签到",
            phase = TaskLanePhase.SIGNING_IN
        )
        val delay = taskRateLimiter.reserveDelayMillis(token, System.currentTimeMillis())
        postDelayed(delay, runGeneration) {
            IlifeApi.scoreSendSignIn(token, account.uid, plan.weekDay, plan.adId, plan.baseScore, !account.hasToken()) { json, err ->
                onMain {
                    if (!isActive(runGeneration)) return@onMain
                    when {
                        json?.optInt("code", -999) == 0 -> {
                            appendTaskLog("    [${account.phone}] 签到请求成功，到账待核验" + if (retry) "（重试）" else "")
                            verifyAccountIncrease(account, points, laneId, runGeneration) { done() }
                        }
                        json?.optInt("code", -999) == -98 && !retry -> {
                            appendTaskLog("    [${account.phone}] 签到频率限制，60秒后重试一次")
                            TaskRunRepository.updateLane(
                                laneId = laneId,
                                currentTask = "签到受限，等待重试",
                                phase = TaskLanePhase.WAITING
                            )
                            postDelayed(RETRY_DELAY_MILLIS, runGeneration) {
                                sendDailySignIn(
                                    account,
                                    plan,
                                    points,
                                    laneId,
                                    runGeneration,
                                    retry = true,
                                    done = done
                                )
                            }
                        }
                        else -> {
                            hadFailures = true
                            val code = json?.optInt("code", -999)
                            val reason = TokenValidity.expiredMessage(json, !account.hasToken())
                                ?: err ?: json?.optString("msg", "code=$code") ?: "未知错误"
                            if (code == -99) {
                                if (account.hasToken()) account.token = "" else account.appToken = ""
                            }
                            appendTaskLog("    [${account.phone}] 签到失败: $reason，继续任务")
                            done()
                        }
                    }
                }
            }
        }
    }

    private fun runMissionItems(
        account: Account,
        work: List<MissionWorkItem>,
        index: Int,
        points: TaskPointsTracker,
        laneId: Int,
        runGeneration: Int,
        retry: Boolean = false,
        done: () -> Unit
    ) {
        if (!isActive(runGeneration)) return
        if (index >= work.size) {
            done()
            return
        }

        val item = work[index]
        val token = if (item.platform == TaskPlatform.APP) account.appToken else account.token
        if (token.isNullOrBlank()) {
            val nextIndex = (index + 1 until work.size).firstOrNull {
                work[it].platform != item.platform
            } ?: work.size
            runMissionItems(account, work, nextIndex, points, laneId, runGeneration, done = done)
            return
        }
        val round = if (item.total > 1) " (${item.round}/${item.total})" else ""
        val notificationRound = if (item.total > 1) "（${item.round}/${item.total}）" else ""
        val platformName = if (item.platform == TaskPlatform.APP) "[APP]" else "[支付宝]"
        TaskRunRepository.updateLane(
            laneId = laneId,
            currentTask = "$platformName ${item.name}$notificationRound",
            phase = TaskLanePhase.RUNNING_MISSION
        )
        appendTaskLog("  [${index + 1}/${work.size}] $platformName ${item.name} +${item.score}分$round")
        val delay = taskRateLimiter.reserveDelayMillis(token, System.currentTimeMillis())
        postDelayed(delay, runGeneration) {
            IlifeApi.scoreSendWithToken(token, account.uid, item.refId, item.score, item.platform == TaskPlatform.APP) { json, err ->
                onMain {
                    if (!isActive(runGeneration)) return@onMain
                    var nextIndex = index + 1
                    fun continueTasks() {
                        TaskRunRepository.updateLane(
                            laneId = laneId,
                            currentTask = if (nextIndex < work.size) "等待下一项任务" else "正在收尾",
                            phase = TaskLanePhase.WAITING
                        )
                        postDelayed(MISSION_GAP_MILLIS, runGeneration) {
                            runMissionItems(account, work, nextIndex, points, laneId, runGeneration, done = done)
                        }
                    }
                    when {
                        json == null -> {
                            hadFailures = true
                            appendTaskLog(
                                "    [${account.phone}] $platformName ${item.name}$round：" +
                                    "网络错误 ${err ?: "未知错误"}"
                            )
                        }
                        json.optInt("code", -999) == 0 -> {
                            appendTaskLog(
                                "    [${account.phone}] $platformName ${item.name}$round：" +
                                    "请求成功，到账待核验"
                            )
                            verifyAccountIncrease(account, points, laneId, runGeneration) { continueTasks() }
                            return@onMain
                        }
                        json.optInt("code", -999) == -98 && !retry -> {
                            appendTaskLog(
                                "    [${account.phone}] $platformName ${item.name}$round：" +
                                    "请求过于频繁，60秒后重试一次"
                            )
                            TaskRunRepository.updateLane(
                                laneId = laneId,
                                currentTask = "${item.name}受限，等待重试",
                                phase = TaskLanePhase.WAITING
                            )
                            postDelayed(RETRY_DELAY_MILLIS, runGeneration) {
                                runMissionItems(
                                    account,
                                    work,
                                    index,
                                    points,
                                    laneId,
                                    runGeneration,
                                    retry = true,
                                    done = done
                                )
                            }
                            return@onMain
                        }
                        json.optInt("code", -999) == -98 -> {
                            hadFailures = true
                            nextIndex = (index + 1 until work.size).firstOrNull {
                                work[it].platform != item.platform || work[it].refId != item.refId
                            } ?: work.size
                            appendTaskLog("    [${account.phone}] $platformName ${item.name}重试仍受限，跳过本任务剩余次数，继续其他任务")
                        }
                        json.optInt("code", -999) == -99 -> {
                            hadFailures = true
                            nextIndex = (index + 1 until work.size).firstOrNull {
                                work[it].platform != item.platform
                            } ?: work.size
                            if (item.platform == TaskPlatform.APP) account.appToken = "" else account.token = ""
                            appendTaskLog("    [${account.phone}] " + TokenValidity.expiredMessage(json, item.platform == TaskPlatform.APP) + "；跳过该平台剩余任务")
                        }
                        else -> {
                            hadFailures = true
                            appendTaskLog(
                                "    [${account.phone}] $platformName ${item.name}$round：" +
                                    "失败 code=${json.optInt("code", -999)} ${json.optString("msg", "")}"
                            )
                        }
                    }
                    continueTasks()
                }
            }
        }
    }

    private fun finishRun() {
        if (!running) return
        val gained = TaskRunRepository.state.value.totalGained
        appendTaskLog(
            if (hadFailures) {
                "===== 任务结束，部分未完成，已核验增加 $gained 分 ====="
            } else {
                "===== 全部完成，已核验增加 $gained 分 ====="
            }
        )
        releaseCpuWakeLock()
        mainHandler.removeCallbacksAndMessages(null)
        TaskRunRepository.finish(partial = hadFailures)
        taskLease?.let(TaskExecutionCoordinator::release)
        taskLease = null
        running = false
        stoppingNormally = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateNotification(TaskRunRepository.state.value)
        stopSelf()
    }

    private fun softCancel(message: String) {
        if (!running) return
        releaseCpuWakeLock()
        generation += 1
        running = false
        stoppingNormally = true
        mainHandler.removeCallbacksAndMessages(null)
        taskLease?.let(TaskExecutionCoordinator::releaseAfterInFlightNetworkGrace)
        taskLease = null
        TaskRunRepository.cancel(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateNotification(TaskRunRepository.state.value)
        stopSelf()
    }

    private fun keepCpuAwake() {
        taskWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "water:points-task").apply {
                setReferenceCounted(false)
                acquire(MAX_RUN_MILLIS)
            }
        mainHandler.postDelayed({
            softCancel("本次积分任务已运行一小时，为避免持续耗电已停止，可稍后继续运行。")
        }, MAX_RUN_MILLIS)
    }

    private fun releaseCpuWakeLock() {
        taskWakeLock?.let { if (it.isHeld) it.release() }
        taskWakeLock = null
    }

    private fun isActive(runGeneration: Int): Boolean =
        running && generation == runGeneration && TaskRunRepository.state.value.running

    private fun appendTaskLog(line: String) {
        TaskRunRepository.appendLog(line)
    }

    private fun postDelayed(delayMillis: Long, runGeneration: Int, action: () -> Unit) {
        if (!isActive(runGeneration)) return
        mainHandler.postDelayed(
            {
                if (isActive(runGeneration)) action()
            },
            delayMillis.coerceAtLeast(0L)
        )
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun createNotificationChannel() {
        AppNotifications.ensureChannels(this)
    }

    private fun startForegroundCompat(notification: Notification) {
        getSystemService(NotificationManager::class.java).cancel(AppNotifications.TASK_RESULT_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                AppNotifications.TASK_PROGRESS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AppNotifications.TASK_PROGRESS_ID, notification)
        }
    }

    private fun updateNotification(state: TaskRunState) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            // 结束结果使用独立通知，避免快速进度更新被系统丢弃后留下转圈的旧通知。
            val id = if (state.running) AppNotifications.TASK_PROGRESS_ID else AppNotifications.TASK_RESULT_ID
            if (!state.running) manager.cancel(AppNotifications.TASK_PROGRESS_ID)
            manager.notify(id, buildNotification(state))
        } catch (_: SecurityException) {
            // Android 13+ 用户可关闭普通通知；前台服务本身仍由系统任务管理器展示。
        }
    }

    private fun buildNotification(state: TaskRunState): Notification {
        val content = TaskNotificationTextFormatter.format(state)
        val builder = Notification.Builder(this, AppNotifications.CHANNEL_TASK_PROGRESS)
            .setSmallIcon(com.water.widget.R.drawable.ic_water_drop)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(Notification.BigTextStyle().bigText(content.text))
            .setContentIntent(AppNotifications.openApp(this, false, AppNotifications.TASK_PROGRESS_ID))
            .setCategory(if (state.running) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(state.running)
            .setAutoCancel(!state.running)
            .setProgress(0, 0, state.running)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    enum class StartResult {
        STARTED,
        ALREADY_RUNNING,
        RISK_NOT_CONFIRMED,
        NO_ACCOUNTS,
        FAILED
    }

    companion object {
        const val ACTION_RUN_TASKS = "com.water.widget.action.RUN_TASKS"
        private const val MAX_RUN_MILLIS = 60 * 60 * 1_000L
        private const val ACCOUNT_GAP_MILLIS = 1_200L
        private const val MISSION_GAP_MILLIS = 30_000L
        private const val RETRY_DELAY_MILLIS = 60_000L

        /** 进程被系统杀死时不保证执行 onDestroy；页面回前台时清理遗留进度通知。 */
        fun clearIdleProgressNotification(context: Context) {
            if (!TaskRunRepository.state.value.running) {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(AppNotifications.TASK_PROGRESS_ID)
            }
        }

        fun start(context: Context): StartResult {
            if (TaskRunRepository.state.value.running) return StartResult.ALREADY_RUNNING
            if (!TaskRiskConsent.isConfirmed(context)) return StartResult.RISK_NOT_CONFIRMED
            if (AccountStore.list(context).none { it.hasToken() || it.hasAppToken() }) return StartResult.NO_ACCOUNTS
            return try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, TaskForegroundService::class.java).setAction(ACTION_RUN_TASKS)
                )
                StartResult.STARTED
            } catch (_: RuntimeException) {
                StartResult.FAILED
            }
        }
    }
}
