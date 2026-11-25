package rt.dmitry.zal;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import org.json.JSONObject;

public final class NotifyScheduler {
    static final String ACTION = "rt.dmitry.zal.NOTIFY";
    static final String EXTRA_KIND = "kind";
    private static final String PREFS = "zal-notify";
    private static final String KEY = "settings";
    private static final String CHANNEL = "zal-reminders";
    private static final int CODE_ACTIVITY = 4101;
    private static final int CODE_BODY = 4102;

    private NotifyScheduler() {}

    static void apply(Context context, String json) {
        JSONObject safe = sanitize(json);
        prefs(context).edit().putString(KEY, safe.toString()).apply();
        schedule(context, safe, "activity");
        schedule(context, safe, "body");
    }

    static void restore(Context context) {
        String json = prefs(context).getString(KEY, null);
        if (json == null) return;
        apply(context, json);
    }

    static void show(Context context, String kind) {
        JSONObject all = read(context);
        JSONObject item = all.optJSONObject(kind);
        if (item == null || !item.optBoolean("on")) return;
        ensureChannel(context);
        String title = "activity".equals(kind) ? "Проверка активностей" : "Проверка тела";
        String text = item.optString("text");
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(
            context,
            code(kind) + 100,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zal)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            try {
                manager.notify(code(kind), notification);
            } catch (SecurityException ignored) {
                // The system notification permission was removed.
            }
        }
        schedule(context, all, kind);
    }

    static boolean wantsOn(String json) {
        JSONObject safe = sanitize(json);
        JSONObject activity = safe.optJSONObject("activity");
        JSONObject body = safe.optJSONObject("body");
        return (activity != null && activity.optBoolean("on")) || (body != null && body.optBoolean("on"));
    }

    private static void schedule(Context context, JSONObject all, String kind) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = pending(context, kind);
        if (alarms == null) return;
        alarms.cancel(pending);
        JSONObject item = all.optJSONObject(kind);
        if (item == null || !item.optBoolean("on")) return;
        long when = "body".equals(kind) ? nextBody(item, System.currentTimeMillis()) : nextActivity(item, System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending);
        }
    }

    private static long nextActivity(JSONObject item, long now) {
        String[] parts = item.optString("time", "21:00").split(":");
        int hour = 21;
        int minute = 0;
        if (parts.length == 2) {
            try {
                hour = Integer.parseInt(parts[0]);
                minute = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                hour = 21;
                minute = 0;
            }
        }
        hour = Math.max(0, Math.min(23, hour));
        minute = Math.max(0, Math.min(59, minute));
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        if (calendar.getTimeInMillis() <= now) calendar.add(Calendar.DATE, 1);
        return calendar.getTimeInMillis();
    }

    private static long nextBody(JSONObject item, long now) {
        int day = item.optInt("day", 1);
        if (day < 1) day = 1;
        if (day > 31) day = 31;
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.HOUR_OF_DAY, 10);
        calendar.set(Calendar.MINUTE, 0);
        int max = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
        calendar.set(Calendar.DAY_OF_MONTH, Math.min(day, max));
        if (calendar.getTimeInMillis() <= now) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.add(Calendar.MONTH, 1);
            max = calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
            calendar.set(Calendar.DAY_OF_MONTH, Math.min(day, max));
        }
        return calendar.getTimeInMillis();
    }

    private static PendingIntent pending(Context context, String kind) {
        Intent intent = new Intent(context, NotifyReceiver.class);
        intent.setAction(ACTION);
        intent.putExtra(EXTRA_KIND, kind);
        return PendingIntent.getBroadcast(
            context,
            code(kind),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static int code(String kind) {
        return "body".equals(kind) ? CODE_BODY : CODE_ACTIVITY;
    }

    private static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
    }

    private static JSONObject read(Context context) {
        return sanitize(prefs(context).getString(KEY, null));
    }

    private static JSONObject sanitize(String json) {
        JSONObject raw = new JSONObject();
        if (json != null) {
            try {
                raw = new JSONObject(json);
            } catch (Exception ignored) {
                raw = new JSONObject();
            }
        }
        JSONObject activityIn = raw.optJSONObject("activity");
        JSONObject bodyIn = raw.optJSONObject("body");
        JSONObject activity = new JSONObject();
        JSONObject body = new JSONObject();
        try {
            activity.put("on", activityIn != null && activityIn.optBoolean("on"));
            activity.put("time", clock(activityIn == null ? "" : activityIn.optString("time")));
            activity.put("text", text(activityIn == null ? "" : activityIn.optString("text"), "Проверь, всё ли записано за сегодня"));
            body.put("on", bodyIn != null && bodyIn.optBoolean("on"));
            int day = bodyIn == null ? 1 : bodyIn.optInt("day", 1);
            if (day < 1 || day > 31) day = 1;
            body.put("day", day);
            body.put("text", text(bodyIn == null ? "" : bodyIn.optString("text"), "Пора записать вес и замеры"));
            JSONObject out = new JSONObject();
            out.put("activity", activity);
            out.put("body", body);
            return out;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static String clock(String value) {
        String[] parts = String.valueOf(value).split(":");
        if (parts.length != 2) return "21:00";
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return "21:00";
            return String.format("%02d:%02d", hour, minute);
        } catch (NumberFormatException ignored) {
            return "21:00";
        }
    }

    private static String text(String value, String fallback) {
        String clean = String.valueOf(value == null ? "" : value).replaceAll("\\s+", " ").trim();
        if (clean.length() > 120) clean = clean.substring(0, 120).trim();
        return clean.isEmpty() ? fallback : clean;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
