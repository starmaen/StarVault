package com.star.vault;

import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import java.util.HashSet;
import java.util.Set;

public class AppLockService extends Service {
    private boolean isRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            startWatcher();
        }
        return START_STICKY;
    }

    private void startWatcher() {
        new Thread(() -> {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            SharedPreferences prefs = getSharedPreferences("StarPrefs", Context.MODE_PRIVATE);

            while (isRunning) {
                try {
                    Thread.sleep(700);
                    Set<String> lockedApps = prefs.getStringSet("locked_packages", new HashSet<>());
                    if (lockedApps.isEmpty()) continue;

                    long time = System.currentTimeMillis();
                    UsageEvents events = usm.queryEvents(time - 1500, time);
                    UsageEvents.Event event = new UsageEvents.Event();

                    while (events.hasNextEvent()) {
                        events.getNextEvent(event);
                        if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                            String activePkg = event.getPackageName();
                            if (lockedApps.contains(activePkg)) {
                                Intent lockIntent = new Intent(this, MainActivity.class);
                                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                lockIntent.putExtra("TRIGGER_LOCK", true);
                                startActivity(lockIntent);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }
}
