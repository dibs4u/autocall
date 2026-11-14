package com.autocaller.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.util.Log;

public class App extends Application {

    /** Shown on the home screen; keep in step with versionName in app/build.gradle. */
    static final String VERSION = "1.1";

    static final String CH_RUN = "campaign";
    static final String CH_CALLS = "calls";
    static final String CH_DONE = "done";

    @Override
    public void onCreate() {
        super.onCreate();
        Logx.init(this);

        // Write crashes into the activity log so they can be looked at afterwards.
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    Logx.log("CRASH: " + Log.getStackTraceString(e));
                } catch (Throwable ignored) {
                }
                if (old != null) old.uncaughtException(t, e);
            }
        });

        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel run = new NotificationChannel(CH_RUN, "Calling progress",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationChannel calls = new NotificationChannel(CH_CALLS, "Phone calls",
                    NotificationManager.IMPORTANCE_HIGH);
            calls.setSound(null, null);
            NotificationChannel done = new NotificationChannel(CH_DONE, "Calling finished",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(run);
            nm.createNotificationChannel(calls);
            nm.createNotificationChannel(done);
        } catch (Exception e) {
            Logx.err("channels", e);
        }
    }
}
