package rt.dmitry.zal;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.health.connect.client.PermissionController;
import androidx.webkit.WebViewAssetLoader;
import java.util.Set;
import org.json.JSONObject;

public class MainActivity extends ComponentActivity {
    private WebView webView;
    private boolean pageReady;
    private boolean healthPromptDone;
    private boolean exactAlarmAsked;
    private long lastHealthNote;
    private String pendingNotify;

    private final ActivityResultLauncher<Set<String>> healthPermission = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
        granted -> HealthReader.pull(this)
    );

    private final ActivityResultLauncher<String> notifyPermission = registerForActivityResult(
        new ActivityResultContracts.RequestPermission(),
        granted -> {
            if (Boolean.TRUE.equals(granted) && pendingNotify != null) {
                NotifyScheduler.apply(this, pendingNotify);
                askExactAlarm(pendingNotify);
            } else if (!Boolean.TRUE.equals(granted)) {
                NotifyScheduler.apply(this, "{\"activity\":{\"on\":false},\"body\":{\"on\":false}}");
                if (webView != null) webView.evaluateJavascript("window.zalNotifyDenied && window.zalNotifyDenied()", null);
            }
            pendingNotify = null;
        }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        webView.addJavascriptInterface(new HealthBridge(), "ZalHealth");
        webView.addJavascriptInterface(new NotifyBridge(), "ZalNotifications");

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
            }
        });
        webView.setBackgroundColor(Color.BLACK);
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) HealthReader.pull(this);
    }

    public boolean isHealthPromptDone() {
        return healthPromptDone;
    }

    public void markHealthPromptDone() {
        healthPromptDone = true;
    }

    public void launchHealthPermission(Set<String> permissions) {
        healthPermission.launch(permissions);
    }

    public void deliverHealth(String json) {
        runOnUiThread(() -> {
            if (webView == null || json == null) return;
            webView.evaluateJavascript("window.applyZalHealth && window.applyZalHealth(" + json + ")", null);
        });
    }

    public void showHealthNote(String message) {
        long now = SystemClock.uptimeMillis();
        if (now - lastHealthNote < 8000) return;
        lastHealthNote = now;
        runOnUiThread(() -> {
            if (webView == null) return;
            webView.evaluateJavascript(
                "window.zalHealthNote && window.zalHealthNote(" + JSONObject.quote(message) + ")",
                null
            );
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    public void saveNotifications(String json) {
        if (Build.VERSION.SDK_INT >= 33
            && NotifyScheduler.wantsOn(json)
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingNotify = json;
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        NotifyScheduler.apply(this, json);
        askExactAlarm(json);
    }

    private void askExactAlarm(String json) {
        if (exactAlarmAsked || !NotifyScheduler.wantsOn(json) || Build.VERSION.SDK_INT < 31) return;
        AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarms == null || alarms.canScheduleExactAlarms()) return;
        exactAlarmAsked = true;
        showHealthNote("Чтобы напоминание приходило вовремя, разрешите точные будильники");
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            // The reminder still uses a regular alarm.
        }
    }

    private final class HealthBridge {
        @JavascriptInterface
        public void pull() {
            runOnUiThread(() -> HealthReader.pull(MainActivity.this));
        }
    }

    private final class NotifyBridge {
        @JavascriptInterface
        public void save(String json) {
            runOnUiThread(() -> saveNotifications(json));
        }
    }
}
