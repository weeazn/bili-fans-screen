package cn.weeazn.bilifans;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String PAGE = "https://" + HOST + "/index.html";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> photoCallback;
    private android.window.OnBackInvokedCallback backCallback;
    private static final int PHOTO_REQUEST = 200;
    private FrameLayout container;
    private BiliApi api;
    private boolean visible;
    private long heartbeat;
    private ConnectivityManager connectivity;
    private boolean avoidCutout = true;
    private int extraSafeDp;
    private int accentColor = Color.rgb(251, 114, 153);
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            handler.postDelayed(() -> { if (visible && web != null) web.evaluateJavascript(
                "window.dispatchEvent(new Event('online'))", null); }, 1500);
        }
    };
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (visible && SystemClock.elapsedRealtime() - heartbeat > 90000) createWebView();
            handler.postDelayed(this, 15000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        container = new FrameLayout(this);
        container.setBackgroundColor(Color.BLACK);
        avoidCutout = getPreferences(0).getBoolean("avoidCutout", true);
        extraSafeDp = getPreferences(0).getInt("safeMargin", 0);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }
        // WebView safe-area values may be zero on older Android versions.
        container.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft(), top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight(), bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            }
            if (avoidCutout && Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                left = Math.max(left, cutout.getSafeInsetLeft()); top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight()); bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
            int extra = avoidCutout ? Math.round(extraSafeDp * getResources().getDisplayMetrics().density) : 0;
            view.setPadding(left + extra, top + extra, right + extra, bottom + extra);
            return insets.consumeSystemWindowInsets();
        });
        setContentView(container);
        api = new BiliApi(this);
        setKeepScreen(true);
        setBrightness(getPreferences(0).getInt("brightness", 25));
        createWebView();
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        applyDisplay(getPreferences(0).getString("orientation", "auto"), avoidCutout, extraSafeDp);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback);
        handler.postDelayed(watchdog, 15000);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void createWebView() {
        if (photoCallback != null) { photoCallback.onReceiveValue(null); photoCallback = null; }
        if (web != null) { container.removeView(web); web.destroy(); }
        heartbeat = SystemClock.elapsedRealtime();
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Read the image explicitly selected through the system picker.
        // Optional LAN proxy in the existing settings; main Bilibili traffic uses native HTTPS.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.addJavascriptInterface(new ScreenControls(), "BiliScreen");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (photoCallback != null) photoCallback.onReceiveValue(null);
                photoCallback = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivityForResult(pick, PHOTO_REQUEST); }
                catch (ActivityNotFoundException e) {
                    photoCallback.onReceiveValue(null); photoCallback = null;
                    Toast.makeText(MainActivity.this, "未找到相册或文件选择器", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.hasGesture() && "https://space.bilibili.com/67368617".equals(request.getUrl().toString())) openAuthor();
                return !HOST.equals(request.getUrl().getHost());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!HOST.equals(uri.getHost())) return null;
                try {
                    if ("/index.html".equals(uri.getPath()))
                        return new WebResourceResponse("text/html", "UTF-8", getAssets().open("index.html"));
                    if (uri.getPath().startsWith("/api/"))
                        return response(api.route(uri));
                } catch (Exception e) {
                    return response("{\"code\":-1,\"message\":" + org.json.JSONObject.quote(e.getMessage()) + "}");
                }
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                handler.post(MainActivity.this::createWebView);
                return true;
            }
        });
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(PAGE);
    }

    private WebResourceResponse response(String json) {
        return new WebResourceResponse("application/json", "UTF-8", 200, "OK",
            Collections.singletonMap("Cache-Control", "no-store"),
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    public class ScreenControls {
        @JavascriptInterface public void pulse() { handler.post(() -> heartbeat = SystemClock.elapsedRealtime()); }
        @JavascriptInterface public void keepScreen(boolean enabled) { handler.post(() -> setKeepScreen(enabled)); }
        @JavascriptInterface public void settings() { handler.post(MainActivity.this::showControls); }
        @JavascriptInterface public String palette() {
            if (Build.VERSION.SDK_INT < 31) return "{\"supported\":false}";
            return "{\"supported\":true,\"light\":\"" + hex(getColor(android.R.color.system_accent1_600))
                + "\",\"dark\":\"" + hex(getColor(android.R.color.system_accent1_200)) + "\"}";
        }
        @JavascriptInterface public void accent(String color) {
            if (color != null && color.matches("#[0-9a-fA-F]{6}")) handler.post(() -> accentColor = Color.parseColor(color));
        }
        @JavascriptInterface public void display(String orientation, boolean avoid, int margin) {
            handler.post(() -> applyDisplay(orientation, avoid, margin));
        }
        @JavascriptInterface public void author() { handler.post(MainActivity.this::openAuthor); }
        @JavascriptInterface public void systemSettings(String section) {
            handler.post(() -> {
                String action = "display".equals(section) ? Settings.ACTION_DISPLAY_SETTINGS
                    : "developer".equals(section) ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                    : Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
                Intent intent = new Intent(action);
                if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action)) intent.setData(Uri.parse("package:" + getPackageName()));
                launchSettings(intent);
            });
        }
    }

    private String hex(int color) { return String.format(java.util.Locale.US, "#%06x", color & 0xffffff); }
    private void launchSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "请在系统设置中手动找到对应选项", Toast.LENGTH_LONG).show();
        }
    }
    private void openAuthor() { launchSettings(new Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/67368617"))); }
    private void applyDisplay(String direction, boolean avoid, int margin) {
        String selected = "portrait".equals(direction) || "landscape".equals(direction) ? direction : "auto";
        avoidCutout = avoid; extraSafeDp = Math.max(0, Math.min(48, margin));
        getPreferences(0).edit().putString("orientation", selected).putBoolean("avoidCutout", avoidCutout)
            .putInt("safeMargin", extraSafeDp).apply();
        container.requestApplyInsets();
        int requested = "portrait".equals(selected) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            : "landscape".equals(selected) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        if (getRequestedOrientation() != requested) setRequestedOrientation(requested);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        container.requestApplyInsets();
        if (web != null) web.evaluateJavascript("window.dispatchEvent(new Event('native-display-change'))", null);
    }

    private void setKeepScreen(boolean enabled) {
        if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void setBrightness(int percent) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = percent / 100f;
        getWindow().setAttributes(params);
        getPreferences(0).edit().putInt("brightness", percent).apply();
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private void showControls() {
        AlertDialog controlsDialog = new AlertDialog.Builder(this).setTitle("安卓专用显示设置")
            .setItems(new String[]{"设为默认桌面（重启后恢复）", "亮度 10%", "亮度 25%", "亮度 50%",
                "系统应用设置 / 电池", "系统设置 / 恢复原桌面", "重载看板", "退出显示"}, (dialog, which) -> {
                if (which == 0) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        RoleManager roles = getSystemService(RoleManager.class);
                        if (roles.isRoleAvailable(RoleManager.ROLE_HOME))
                            startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1);
                    } else startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                } else if (which <= 3) setBrightness(new int[]{0,10,25,50}[which]);
                else if (which == 4) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
                else if (which == 5) startActivity(new Intent(Settings.ACTION_SETTINGS));
                else if (which == 6) createWebView();
                else finish();
            }).setNegativeButton("返回看板", null).create();
        controlsDialog.setOnShowListener(ignored -> {
            android.widget.Button button = controlsDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            button.setBackgroundTintList(ColorStateList.valueOf(accentColor));
            double luminance = 0;
            int[] components = {Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)};
            double[] weights = {.2126, .7152, .0722};
            for (int i = 0; i < 3; i++) {
                double value = components[i] / 255.0;
                luminance += weights[i] * (value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4));
            }
            button.setTextColor(luminance > .179 ? Color.BLACK : Color.WHITE);
        });
        controlsDialog.show();
    }

    @Override protected void onResume() {
        super.onResume(); visible = true; heartbeat = SystemClock.elapsedRealtime();
        web.onResume(); immersive();
        web.evaluateJavascript("window.dispatchEvent(new Event('online'))", null);
        web.evaluateJavascript("window.dispatchEvent(new Event('native-palette-change'))", null);
    }
    @Override protected void onPause() { visible = false; web.onPause(); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) immersive(); }
    private void handleBack() {
        if (web == null) return;
        web.evaluateJavascript("typeof window.handleAppBack === 'function' && window.handleAppBack()", result -> {
            if (!"true".equals(result) && !isFinishing()) showControls();
        });
    }
    @Override public void onBackPressed() { handleBack(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PHOTO_REQUEST || photoCallback == null) return;
        Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        if (uri != null && !"content".equals(uri.getScheme())) uri = null;
        photoCallback.onReceiveValue(uri == null ? null : new Uri[]{uri}); photoCallback = null;
    }
    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if (photoCallback != null) { photoCallback.onReceiveValue(null); photoCallback = null; }
        handler.removeCallbacksAndMessages(null);
        connectivity.unregisterNetworkCallback(networkCallback);
        web.destroy(); super.onDestroy();
    }
}
