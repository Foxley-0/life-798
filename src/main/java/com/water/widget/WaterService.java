package com.water.widget;

import android.app.NotificationManager;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.water.widget.ui.UsageHistoryStore;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 启动饮水设备并监测本次消费流水。
 *
 * 设备需要通过实体按钮完成实际出水；服务端生成新的成功消费流水后，
 * 发送本次消费与预计水量通知。
 */
public class WaterService extends Service {
    private static final String ACTION_QUERY_AFTER_STOP = "com.water.widget.QUERY_AFTER_STOP";
    public static final String ACTION_STOP_MONITORING = "com.water.widget.STOP_WATER_MONITORING";
    public static final String EXTRA_RESERVATION_ID = "extra_reservation_id";
    public static final String EXTRA_DID = "extra_did";
    public static final String EXTRA_WIDGET_ID = "extra_widget_id";
    private static final String EXTRA_APP_TOKEN = "extra_app_token";
    private static final String EXTRA_SCORE_TOKEN = "extra_score_token";
    private static final String EXTRA_ACCOUNT_KEY = "extra_account_key";

    private static final long BASELINE_TIMEOUT_MILLIS = 4_000L;
    private static final long FIRST_POLL_DELAY_MILLIS = 8_000L;
    private static final long POLL_INTERVAL_MILLIS = 10_000L;
    private static final long MAX_MONITOR_MILLIS = 10 * 60_000L;
    private static final AtomicLong NEXT_RESERVATION_ID = new AtomicLong();
    private static final AtomicLong ACTIVE_RESERVATION_ID = new AtomicLong();
    private static final AtomicLong STARTING_RESERVATION_ID = new AtomicLong();

    public static boolean isStarting() {
        return STARTING_RESERVATION_ID.get() != 0L;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Session session;
    private long ownedReservationId;

    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        FAILED
    }

    /**
     * 同一进程同一时间只允许一个接水监测会话，避免连续点击后把 A 设备的流水归给 B。
     */
    public static StartResult start(Context context, String did, int widgetId) {
        if (did == null || did.trim().isEmpty()) return StartResult.FAILED;
        Account account = AccountStore.getCurrent(context);
        if (account == null || !account.hasAppToken()) return StartResult.FAILED;
        long reservationId = NEXT_RESERVATION_ID.incrementAndGet();
        if (!ACTIVE_RESERVATION_ID.compareAndSet(0L, reservationId)) {
            return StartResult.ALREADY_RUNNING;
        }
        STARTING_RESERVATION_ID.set(reservationId);
        String accountKey = account.phone != null && !account.phone.isEmpty()
                ? account.phone
                : account.uid;
        WaterControlState.begin(reservationId, accountKey == null ? "" : accountKey, did);
        String scoreToken = account.hasToken() ? account.token : account.appToken;
        Intent intent = new Intent(context, WaterService.class)
                .putExtra(EXTRA_DID, did)
                .putExtra(EXTRA_WIDGET_ID, widgetId)
                .putExtra(EXTRA_APP_TOKEN, account.appToken)
                .putExtra(EXTRA_SCORE_TOKEN, scoreToken)
                .putExtra(EXTRA_ACCOUNT_KEY, accountKey == null ? "" : accountKey)
                .putExtra(EXTRA_RESERVATION_ID, reservationId);
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, intent);
            return StartResult.STARTED;
        } catch (RuntimeException error) {
            releaseReservation(reservationId);
            return StartResult.FAILED;
        }
    }

    public static void queryAfterManualStop(Context context, long reservationId) {
        if (ACTIVE_RESERVATION_ID.get() != reservationId) return;
        context.startService(new Intent(context, WaterService.class)
                .setAction(ACTION_QUERY_AFTER_STOP)
                .putExtra(EXTRA_RESERVATION_ID, reservationId));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_QUERY_AFTER_STOP.equals(intent.getAction())) {
            Session current = session;
            if (current != null && intent.getLongExtra(EXTRA_RESERVATION_ID, 0L) == current.reservationId) {
                mainHandler.removeCallbacksAndMessages(null);
                WaterControlState.clear(current.reservationId);
                current.manualStopRequested = true;
                current.fastPollsRemaining = 5;
                mainHandler.postDelayed(() -> expireIfNeeded(current), Math.max(1L, current.deadlineElapsed - SystemClock.elapsedRealtime()));
                notifyWaterProgress("正在查询本次消费…");
                pollConsumption(current);
            } else if (current == null) {
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP_MONITORING.equals(intent.getAction())) {
            Session current = session;
            if (current != null && intent.getLongExtra(EXTRA_RESERVATION_ID, 0L) == current.reservationId) {
                session = null;
                mainHandler.removeCallbacksAndMessages(null);
                releaseReservation(current.reservationId);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            } else if (current == null) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (intent == null) {
            releaseReservation(ownedReservationId);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        long reservationId = intent.getLongExtra(EXTRA_RESERVATION_ID, 0L);
        if (
                reservationId <= 0L ||
                ACTIVE_RESERVATION_ID.get() != reservationId
        ) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        ownedReservationId = reservationId;
        if (session != null) {
            return START_NOT_STICKY;
        }

        final String did = intent.getStringExtra(EXTRA_DID);
        final int widgetId = intent.getIntExtra(
                EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
        );
        final String appToken = intent.getStringExtra(EXTRA_APP_TOKEN);
        final String scoreToken = intent.getStringExtra(EXTRA_SCORE_TOKEN);
        final String accountKey = intent.getStringExtra(EXTRA_ACCOUNT_KEY);
        AppNotifications.ensureChannels(this);

        if (
                did == null ||
                did.trim().isEmpty() ||
                appToken == null ||
                appToken.isEmpty()
        ) {
            finishImmediate(
                    "设备启动失败",
                    "请检查设备控制登录信息与当前设备",
                    true,
                    startId
            );
            return START_NOT_STICKY;
        }

        Session started = new Session(
                startId,
                widgetId,
                reservationId,
                did,
                appToken,
                scoreToken,
                accountKey == null ? "" : accountKey
        );
        session = started;
        startAsForeground("正在启动设备…");
        mainHandler.postDelayed(() -> expireIfNeeded(started), BASELINE_TIMEOUT_MILLIS + MAX_MONITOR_MILLIS);
        captureBaselines(started);
        return START_NOT_STICKY;
    }

    /**
     * 在真正启动设备前获取现有账单与积分流水 ID。即使旧订单稍后更新状态，
     * 也不会被误认为本次接水；网络较慢时最多等待四秒，不长期阻塞设备使用。
     */
    private void captureBaselines(Session current) {
        current.pendingBaselines = 0;
        if (!current.billToken.isEmpty()) current.pendingBaselines++;
        if (!current.scoreToken.isEmpty()) current.pendingBaselines++;

        if (current.pendingBaselines == 0) {
            beginDeviceStart(current);
            return;
        }

        mainHandler.postDelayed(
                () -> beginDeviceStart(current),
                BASELINE_TIMEOUT_MILLIS
        );

        if (!current.billToken.isEmpty()) {
            IlifeApi.billListWithToken(current.billToken, (json, err) ->
                    onMain(() -> {
                        if (!isActive(current) || current.baselineFrozen) return;
                        current.billBaseline = WaterBillParser.INSTANCE.recordKeys(json);
                        baselineFinished(current);
                    })
            );
        }
        if (!current.scoreToken.isEmpty()) {
            readScores(current, (json, err) ->
                    onMain(() -> {
                        if (!isActive(current) || current.baselineFrozen) return;
                        current.scoreBaseline = WaterConsumptionParser.INSTANCE.recordKeys(json);
                        baselineFinished(current);
                    })
            );
        }
    }

    private void baselineFinished(Session current) {
        current.pendingBaselines = Math.max(0, current.pendingBaselines - 1);
        if (current.pendingBaselines == 0) beginDeviceStart(current);
    }

    private void beginDeviceStart(Session current) {
        if (!isActive(current) || current.baselineFrozen || expireIfNeeded(current)) return;
        current.baselineFrozen = true;
        current.monitoringStartedAt = System.currentTimeMillis() / 1_000L * 1_000L;
        WaterApi.startWithToken(current.billToken, current.did, status ->
                onMain(() -> {
                    if (!isActive(current)) return;
                    STARTING_RESERVATION_ID.compareAndSet(current.reservationId, 0L);
                    updateWidget(current.widgetId, status);
                    if (!isSuccessStatus(status)) {
                        finishWithResult(
                                current,
                                "设备启动失败",
                                status == null ? "请返回应用检查设备与登录信息" : status,
                                true
                        );
                        return;
                    }

                    if (expireIfNeeded(current)) return;
                    WaterControlState.started(current.reservationId);
                    notifyWaterProgress("设备已启动，接水结束后将显示本次花费");
                    mainHandler.postDelayed(
                            () -> pollConsumption(current),
                            FIRST_POLL_DELAY_MILLIS
                    );
                })
        );
    }

    private void pollConsumption(Session current) {
        if (!isActive(current)) return;
        if (expireIfNeeded(current)) return;
        final int generation = ++current.pollGeneration;
        if (!current.billToken.isEmpty()) {
            IlifeApi.billListWithToken(current.billToken, (billJson, billErr) ->
                    onMain(() -> {
                        if (!isActive(current) || generation != current.pollGeneration) return;
                        List<String> billIds = WaterBillParser.INSTANCE.candidateIds(
                                billJson, current.monitoringStartedAt, current.billBaseline, current.did
                        );
                        pollBillDetails(current, generation, billIds, 0);
                    })
            );
        } else {
            pollScoreConsumption(current, generation);
        }
    }

    private void pollBillDetails(Session current, int generation, List<String> billIds, int index) {
        if (!isActive(current) || generation != current.pollGeneration || expireIfNeeded(current)) return;
        if (index >= billIds.size()) {
            pollScoreConsumption(current, generation);
            return;
        }
        String billId = billIds.get(index);
        IlifeApi.exchangeBillWithToken(current.billToken, billId, (detail, detailErr) -> onMain(() -> {
            if (!isActive(current) || generation != current.pollGeneration || expireIfNeeded(current)) return;
            WaterConsumption bill = WaterBillParser.INSTANCE.fromDetail(detail, billId, current.did);
            if (bill != null) {
                if (!current.accountKey.isEmpty()) UsageHistoryStore.INSTANCE.record(this, current.accountKey, bill);
                finishConsumption(current, bill);
            } else {
                pollBillDetails(current, generation, billIds, index + 1);
            }
        }));
    }

    private void readScores(Session current, IlifeApi.JsonCallback callback) {
        IlifeApi.scoreLstWithToken(current.scoreToken, (json, err) -> {
            if (json != null && json.optInt("code", 0) == -99 && !current.scoreToken.equals(current.billToken)) {
                IlifeApi.scoreLstWithToken(current.billToken, callback);
            } else {
                callback.onResult(json, err);
            }
        });
    }

    private void pollScoreConsumption(Session current, int generation) {
        if (!isActive(current) || generation != current.pollGeneration || expireIfNeeded(current)) return;
        if (current.manualStopRequested) {
            scheduleNextPoll(current);
            return;
        }
        readScores(current, (json, err) -> onMain(() -> {
            if (!isActive(current) || generation != current.pollGeneration || expireIfNeeded(current)) return;
            WaterConsumption consumption = WaterConsumptionParser.INSTANCE.latestSince(
                    json, current.monitoringStartedAt, current.did, current.scoreBaseline
            );
            if (consumption == null) {
                scheduleNextPoll(current);
                return;
            }
            if (!current.accountKey.isEmpty()) {
                UsageHistoryStore.INSTANCE.mergeAndRead(this, current.accountKey, json);
            }
            finishConsumption(current, consumption);
        }));
    }

    private boolean expireIfNeeded(Session current) {
        if (SystemClock.elapsedRealtime() < current.deadlineElapsed) return false;
        if (isActive(current)) {
            finishWithResult(current, "本次接水提醒已结束", "暂未获取到本次花费，请稍后查看消费记录", false);
        }
        return true;
    }

    private void scheduleNextPoll(Session current) {
        if (!isActive(current)) return;
        long delay = POLL_INTERVAL_MILLIS;
        if (current.fastPollsRemaining > 0) {
            current.fastPollsRemaining--;
            delay = 2_000L;
        }
        mainHandler.postDelayed(() -> pollConsumption(current), delay);
    }

    private void finishConsumption(Session current, WaterConsumption consumption) {
        UsageHistoryStore.INSTANCE.notifyWaterSettled(current.accountKey);
        finishWithResult(
                current,
                consumption.getCompletionTitle(),
                consumption.completionText(WaterUsageSettings.INSTANCE.read(this)),
                false
        );
    }

    private void startAsForeground(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    AppNotifications.WATER_SESSION_ID,
                    AppNotifications.waterProgress(this, text, session.reservationId, session.createdAt, Math.max(1L, session.deadlineElapsed - SystemClock.elapsedRealtime())),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(
                    AppNotifications.WATER_SESSION_ID,
                    AppNotifications.waterProgress(this, text, session.reservationId, session.createdAt, Math.max(1L, session.deadlineElapsed - SystemClock.elapsedRealtime()))
            );
        }
    }

    private void notifyWaterProgress(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            manager.notify(
                    AppNotifications.WATER_SESSION_ID,
                    AppNotifications.waterProgress(this, text, session.reservationId, session.createdAt, Math.max(1L, session.deadlineElapsed - SystemClock.elapsedRealtime()))
            );
        } catch (SecurityException ignored) {
            // 用户关闭通知时，前台服务仍可继续；最终结果会回退为 Toast。
        }
    }

    private void finishWithResult(
            Session current,
            String title,
            String text,
            boolean recovery
    ) {
        if (!isActive(current)) return;
        session = null;
        mainHandler.removeCallbacksAndMessages(null);
        releaseReservation(current.reservationId);
        stopForeground(STOP_FOREGROUND_REMOVE);
        showResult(title, text, recovery);
        stopSelf();
    }

    private void finishImmediate(
            String title,
            String text,
            boolean recovery,
            int startId
    ) {
        session = null;
        mainHandler.removeCallbacksAndMessages(null);
        releaseReservation(ownedReservationId);
        stopForeground(STOP_FOREGROUND_REMOVE);
        showResult(title, text, recovery);
        stopSelf(startId);
    }

    private void showResult(String title, String text, boolean recovery) {
        boolean posted = false;
        if (AppNotifications.canPost(this, AppNotifications.CHANNEL_WATER_RESULT)) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                try {
                    manager.notify(
                            AppNotifications.WATER_RESULT_ID,
                            AppNotifications.waterResult(this, title, text, recovery)
                    );
                    posted = true;
                } catch (SecurityException ignored) {
                    // Android 13+ 用户拒绝通知权限时使用 Toast 回退。
                }
            }
        }
        // 需要用户处理的失败同时弹 Toast，避免停留在主页时漏看通知。
        if (recovery || !posted) Toast.makeText(this, title + "：" + text, Toast.LENGTH_LONG).show();
    }

    private void updateWidget(int widgetId, String status) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        RemoteViews views = WaterWidgetProvider.buildViews(this, widgetId, status);
        AppWidgetManager.getInstance(this).updateAppWidget(widgetId, views);
    }

    private boolean isActive(Session expected) {
        return session == expected &&
                ACTIVE_RESERVATION_ID.get() == expected.reservationId;
    }

    private static void releaseReservation(long reservationId) {
        if (reservationId > 0L) {
            ACTIVE_RESERVATION_ID.compareAndSet(reservationId, 0L);
            STARTING_RESERVATION_ID.compareAndSet(reservationId, 0L);
            WaterControlState.clear(reservationId);
        }
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private static boolean isSuccessStatus(String status) {
        return status != null && status.contains("成功") && !status.contains("失败");
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        Session current = session;
        if (current != null) {
            finishWithResult(
                    current,
                    "本次接水提醒已结束",
                    "后台运行已结束，请稍后查看消费记录",
                    false
            );
        } else {
            releaseReservation(ownedReservationId);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    @Override
    public void onDestroy() {
        session = null;
        mainHandler.removeCallbacksAndMessages(null);
        releaseReservation(ownedReservationId);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class Session {
        final long createdAt = System.currentTimeMillis();
        final int startId;
        final int widgetId;
        final long reservationId;
        final String did;
        final String billToken;
        final String scoreToken;
        final String accountKey;
        Set<String> billBaseline = Collections.emptySet();
        Set<String> scoreBaseline = Collections.emptySet();
        int pollGeneration;
        int fastPollsRemaining;
        boolean manualStopRequested;
        int pendingBaselines;
        boolean baselineFrozen;
        long monitoringStartedAt;
        final long deadlineElapsed = SystemClock.elapsedRealtime() + BASELINE_TIMEOUT_MILLIS + MAX_MONITOR_MILLIS;

        Session(
                int startId,
                int widgetId,
                long reservationId,
                String did,
                String billToken,
                String scoreToken,
                String accountKey
        ) {
            this.startId = startId;
            this.widgetId = widgetId;
            this.reservationId = reservationId;
            this.did = did;
            this.billToken = billToken == null ? "" : billToken;
            this.scoreToken = scoreToken == null ? "" : scoreToken;
            this.accountKey = accountKey;
        }
    }
}
