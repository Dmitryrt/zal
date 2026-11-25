package rt.dmitry.zal;

import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.health.connect.client.PermissionController;
import androidx.webkit.WebViewAssetLoader;
import java.util.Set;
import org.json.JSONObject;

public class MainActivity extends ComponentActivity {
    private WebView webView;
    private boolean pageReady;
    private boolean healthPromptDone;
    private long lastHealthNote;

    private final ActivityResultLauncher<Set<String>> healthPermission = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
        granted -> HealthReader.pull(this)
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

    private final class HealthBridge {
        @JavascriptInterface
        public void pull() {
            runOnUiThread(() -> HealthReader.pull(MainActivity.this));
        }
    }
}
