package com.satriaappportal.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.View;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.widget.ProgressBar;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.widget.Toast;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;
import android.provider.Settings;
import android.os.PowerManager;
import android.widget.Toast;
import android.net.Uri;
import android.content.ComponentName;
import org.json.JSONObject;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.webkit.ValueCallback;
import android.provider.MediaStore;
import android.os.Environment;
import android.content.ContentValues;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.view.GestureDetector;
import android.view.MotionEvent;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private String fcmTokenForWebView = "";
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private ProgressBar progressBarCircular;
    private static final String WEBSITE_URL = "https://www.satriadyit.eu.org/";
    private SwipeRefreshLayout swipeRefresh;
    private ValueCallback<Uri[]> fileUploadCallback;
    private Uri cameraImageUri;
    private long backPressedTime = 0;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Set system bar colors
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int systemBarColor = Color.parseColor("#19BFC2");
            getWindow().setStatusBarColor(systemBarColor);
            getWindow().setNavigationBarColor(systemBarColor);
        }
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        progressBarCircular = findViewById(R.id.progressBarCircular);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeColors(Color.parseColor("#F70808"));
        swipeRefresh.setOnRefreshListener(() -> {
            webView.reload();
        });
        
        setupWebView();
        
        // Enable Service Worker support (API 24+)
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController swController = ServiceWorkerController.getInstance();
            swController.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return null;
                }
            });
        }

        // Request POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        // Explicitly fetch and send FCM token at app startup
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        final String fcmToken = task.getResult();
                        fcmTokenForWebView = fcmToken;
                        android.util.Log.d("FCM_TOKEN", "Token received: " + fcmToken);

                        // Show token status via JavaScript in WebView (debug)
                        runOnUiThread(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript(
                                    "window.FCM_TOKEN = '" + fcmToken + "'; window.dispatchEvent(new Event('fcm_token_ready')); console.log('FCM Token set');", null);
                            }
                        });

                        new Thread(() -> {
                            try {
                                // Endpoint is generated by PHP based on Website URL:
                                // User app  => /fcm_token.php
                                // Admin app => /admin/fcm_token.php
                                String endpoint = "https://www.satriadyit.eu.org/fcm_token.php";
                                android.util.Log.d("FCM_TOKEN", "Sending token to: " + endpoint);

                                java.net.URL tokenUrl = new java.net.URL(endpoint);
                                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) tokenUrl.openConnection();
                                conn.setRequestMethod("POST");
                                conn.setRequestProperty("Content-Type", "application/json");
                                conn.setRequestProperty("User-Agent", "BPWalletApp/1.0");
                                conn.setDoOutput(true);
                                conn.setConnectTimeout(15000);
                                conn.setReadTimeout(15000);
                                String body = "{\"token\":\"" + fcmToken + "\"}";
                                try (java.io.OutputStream os = conn.getOutputStream()) {
                                    os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                }
                                int status = conn.getResponseCode();
                                android.util.Log.d("FCM_TOKEN", "POST response: HTTP " + status);
                                conn.disconnect();

                                // Fallback: GET request if POST fails
                                if (status < 200 || status >= 300) {
                                    android.util.Log.d("FCM_TOKEN", "POST failed, trying GET fallback...");
                                    String t = java.net.URLEncoder.encode(fcmToken, "UTF-8");
                                    java.net.URL fallbackUrl = new java.net.URL(endpoint + "?token=" + t);
                                    java.net.HttpURLConnection fallbackConn = (java.net.HttpURLConnection) fallbackUrl.openConnection();
                                    fallbackConn.setRequestMethod("GET");
                                    fallbackConn.setRequestProperty("User-Agent", "BPWalletApp/1.0");
                                    fallbackConn.setConnectTimeout(15000);
                                    fallbackConn.setReadTimeout(15000);
                                    int fbStatus = fallbackConn.getResponseCode();
                                    android.util.Log.d("FCM_TOKEN", "GET fallback response: HTTP " + fbStatus);
                                    fallbackConn.disconnect();
                                }
                            } catch (Exception e) {
                                android.util.Log.e("FCM_TOKEN", "Error sending token: " + e.getMessage(), e);
                            }
                        }).start();
                    } else {
                        android.util.Log.e("FCM_TOKEN", "Failed to get token: " + (task.getException() != null ? task.getException().getMessage() : "unknown error"));
                        // Show error as Toast so user can see
                        runOnUiThread(() -> {
                            String errMsg = task.getException() != null ? task.getException().getMessage() : "Unknown FCM error";
                            Toast.makeText(MainActivity.this, "FCM Error: " + errMsg, Toast.LENGTH_LONG).show();
                        });
                    }
                });
        } catch (Exception e) {
            android.util.Log.e("FCM_TOKEN", "Firebase init error: " + e.getMessage(), e);
            Toast.makeText(this, "Firebase Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // Schedule background notification polling every 15 minutes
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
            NotificationWorker.class, 15, TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "notification_check",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest);
        
        // Request runtime permissions
        java.util.List<String> permissionsNeeded = new java.util.ArrayList<>();
        String[] requiredPerms = new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_PHONE_STATE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.USE_BIOMETRIC, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.BODY_SENSORS, Manifest.permission.READ_EXTERNAL_STORAGE};
        for (String perm : requiredPerms) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(perm);
            }
        }
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), 2001);
        }
        
        // Workforce native background tracking bridge exposed to the web app.
        WFTrackingBridge wfTrackingBridge = new WFTrackingBridge();
        webView.addJavascriptInterface(wfTrackingBridge, "AndroidLocationService");
        webView.addJavascriptInterface(wfTrackingBridge, "WFAndroidTracking");
        webView.addJavascriptInterface(wfTrackingBridge, "AndroidTracking");
        if (getSharedPreferences("wf_tracking", MODE_PRIVATE).getBoolean("enabled", false)) {
            startWorkforceTrackingService();
        }
        // Add JavaScript Bridge
        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");
        // Swipe gesture detector for back/forward
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 100;
            private static final int SWIPE_VELOCITY = 100;
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                float dX = e2.getX() - e1.getX();
                if (Math.abs(dX) > Math.abs(e2.getY() - e1.getY()) && Math.abs(dX) > SWIPE_THRESHOLD && Math.abs(vX) > SWIPE_VELOCITY) {
                    if (dX > 0 && webView.canGoBack()) { webView.goBack(); return true; }
                    else if (dX < 0 && webView.canGoForward()) { webView.goForward(); return true; }
                }
                return false;
            }
        });
        webView.setOnTouchListener((v, event) -> { gestureDetector.onTouchEvent(event); return false; });
        checkForUpdate();
        
        // Handle deep link intent
        handleIntent(getIntent());
        
        // Load directly; ConnectivityManager can be unreliable on some devices/VPNs.
        // WebView will show its own error page if the connection is actually unavailable.
        webView.loadUrl(WEBSITE_URL);
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setSupportZoom(true);
        webSettings.setDefaultTextEncodingName("utf-8");
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setDatabaseEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        webSettings.setGeolocationEnabled(true);
        webSettings.setUserAgentString("SAT_PORTAL_AMAN");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
                if (progressBarCircular != null) progressBarCircular.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (progressBarCircular != null) progressBarCircular.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                // Save cookies for background notification worker
                String cookies = CookieManager.getInstance().getCookie(WEBSITE_URL);
                if (cookies != null && !cookies.isEmpty()) {
                    getSharedPreferences("bp_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("session_cookies", cookies)
                        .putString("website_url", WEBSITE_URL)
                        .apply();
                }
                // Re-inject FCM token after every page load so website JS can POST it with user_id/session.
                if (fcmTokenForWebView != null && !fcmTokenForWebView.isEmpty()) {
                    view.evaluateJavascript("window.FCM_TOKEN = '" + fcmTokenForWebView + "'; window.dispatchEvent(new Event('fcm_token_ready'));", null);
                }
                // Custom JavaScript injection
                view.evaluateJavascript("// Memaksa semua link agar tetap di dalam aplikasi (tidak new tab) var links = document.getElementsByTagName(\'a\'); for(var i = 0; i < links.length; i++) {     if(links[i].getAttribute(\'target\') === \'_blank\') {         links[i].removeAttribute(\'target\');     } }  // Override alert bawaan browser menjadi Modal Kustom yang rapi window.alert = function(message) {     var overlay = document.createElement(\'div\');     overlay.style.cssText = \'position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.5);z-index:999999;display:flex;align-items:center;justify-content:center;\';          var modal = document.createElement(\'div\');     modal.style.cssText = \'background:#fff;padding:24px;border-radius:12px;width:85%;max-width:320px;text-align:center;box-shadow:0 15px 35px rgba(0,0,0,0.2);font-family:sans-serif;\';          var title = document.createElement(\'h3\');     title.innerText = \'Pemberitahuan\';     title.style.cssText = \'margin:0 0 10px 0;font-size:18px;color:#dc3545;\';          var text = document.createElement(\'p\');     text.innerText = message;     text.style.cssText = \'margin:0 0 20px 0;color:#333;font-size:14px;\';          var btn = document.createElement(\'button\');     btn.innerText = \'Mengerti\';     btn.style.cssText = \'background:#f1f3f5;color:#333;border:none;padding:10px 20px;border-radius:6px;width:100%;font-weight:600;cursor:pointer;\';          btn.onclick = function() { document.body.removeChild(overlay); };          modal.appendChild(title);     modal.appendChild(text);     modal.appendChild(btn);     overlay.appendChild(modal);     document.body.appendChild(overlay); };", null);
                // Custom CSS injection via Base64 to avoid quote escaping issues
                String cssStr = "/* Mencegah user bisa memblok atau menyalin (copy) teks di dalam portal */ body {     -webkit-touch-callout: none;     -webkit-user-select: none;     user-select: none; }  /* Menghilangkan efek kotak biru (highlight) saat tombol diketuk di layar sentuh */ * {     -webkit-tap-highlight-color: transparent; }";
                String b64Css = android.util.Base64.encodeToString(cssStr.getBytes(), android.util.Base64.NO_WRAP);
                view.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=atob('" + b64Css + "');document.head.appendChild(s);})()", null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleWebViewUrl(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && request.getUrl() != null) {
                    return handleWebViewUrl(view, request.getUrl().toString());
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) progressBar.setProgress(newProgress);
            }
            
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 2002);
                }
            }
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                // Camera intent
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.TITLE, "camera_photo");
                cameraImageUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);

                // File chooser intent
                Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
                fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
                fileIntent.setType("*/*");

                // Combine into chooser
                Intent chooserIntent = Intent.createChooser(fileIntent, "Select file");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});

                fileUploadLauncher.launch(chooserIntent);
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file...");
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType));
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType));
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);
                Toast.makeText(getApplicationContext(), "Downloading File", Toast.LENGTH_LONG).show();
            }
        });
    }


    private boolean handleWebViewUrl(WebView view, String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String lower = url.toLowerCase();
        if (lower.startsWith("tel:") || lower.startsWith("mailto:") || lower.startsWith("sms:") || lower.startsWith("smsto:") || lower.startsWith("whatsapp:") || lower.startsWith("market:") || lower.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
                return true;
            } catch (Exception ignored) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    return true;
                } catch (Exception ignoredAgain) {
                    return true;
                }
            }
        }
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return true;
        }
        view.loadUrl(url);
        return true;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            String deepUrl = intent.getData().toString();
            if (deepUrl.startsWith("http")) {
                webView.loadUrl(deepUrl);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private final ActivityResultLauncher<Intent> fileUploadLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (fileUploadCallback == null) return;
            if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                fileUploadCallback.onReceiveValue(new Uri[]{result.getData().getData()});
            } else if (result.getResultCode() == RESULT_OK && cameraImageUri != null) {
                fileUploadCallback.onReceiveValue(new Uri[]{cameraImageUri});
            } else {
                fileUploadCallback.onReceiveValue(null);
            }
            fileUploadCallback = null;
        });

    private void checkForUpdate() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://www.satriadyit.eu.org/version.json");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();
                
                JSONObject json = new JSONObject(sb.toString());
                String latestVersion = json.optString("latest_version", "");
                final String updateUrl = json.optString("update_url", "");
                
                if (!latestVersion.isEmpty() && !latestVersion.equals("2026.13.06") && !updateUrl.isEmpty()) {
                    runOnUiThread(() -> {
                        new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Update Available")
                            .setMessage("A new version (" + latestVersion + ") is available. Would you like to update?")
                            .setPositiveButton("Update", (d, w) -> {
                                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(updateUrl)));
                            })
                            .setNegativeButton("Later", null)
                            .show();
                    });
                }
            } catch (Exception e) { /* silent fail */ }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            } else {
                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    finish();
                } else {
                    android.widget.Toast.makeText(this, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show();
                    backPressedTime = System.currentTimeMillis();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startWorkforceTrackingService() {
        try {
            Intent serviceIntent = new Intent(this, WFTrackingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
            else startService(serviceIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Tracking service start failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private class WFTrackingBridge {
        @android.webkit.JavascriptInterface
        public void configure(String json) {
            getSharedPreferences("wf_tracking", MODE_PRIVATE).edit()
                .putString("config", json == null ? "{}" : json)
                .putBoolean("enabled", true).apply();
        }
        @android.webkit.JavascriptInterface public void start() { startWorkforceTrackingService(); }
        @android.webkit.JavascriptInterface public void start(String json) { configure(json); startWorkforceTrackingService(); }
        @android.webkit.JavascriptInterface public void startService(String json) { configure(json); startWorkforceTrackingService(); }
        @android.webkit.JavascriptInterface public void startForegroundService(String json) { configure(json); startWorkforceTrackingService(); }
        @android.webkit.JavascriptInterface public void enableBackgroundTracking(String json) { configure(json); startWorkforceTrackingService(); }
        @android.webkit.JavascriptInterface public void startTracking(String json) { configure(json); startWorkforceTrackingService(); }
        @android.webkit.JavascriptInterface public void stop() {
            getSharedPreferences("wf_tracking", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            try { stopService(new Intent(MainActivity.this, WFTrackingService.class)); } catch (Exception ignored) {}
        }
        @android.webkit.JavascriptInterface public void requestLocationPermission(String json) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 3101);
                }
            });
        }
        @android.webkit.JavascriptInterface public void requestForegroundLocationPermission(String json) { requestLocationPermission(json); }
        @android.webkit.JavascriptInterface public void requestFineLocationPermission(String json) { requestLocationPermission(json); }
        @android.webkit.JavascriptInterface public void requestBackgroundLocationPermission(String json) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(MainActivity.this, "Open Location permission and select Allow all the time", Toast.LENGTH_LONG).show();
                    openAppDetailsSettings();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 3102);
                } else requestLocationPermission(json);
            });
        }
        @android.webkit.JavascriptInterface public void requestAlwaysLocationPermission(String json) { requestBackgroundLocationPermission(json); }
        @android.webkit.JavascriptInterface public void requestBackgroundPermission(String json) { requestBackgroundLocationPermission(json); }
        @android.webkit.JavascriptInterface public void requestIgnoreBatteryOptimizations(String json) {
            runOnUiThread(() -> {
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } else Toast.makeText(MainActivity.this, "Battery optimization already unrestricted", Toast.LENGTH_SHORT).show();
                } catch (Exception e) { openBatterySettings(); }
            });
        }
        @android.webkit.JavascriptInterface public void requestBatteryOptimizationExemption(String json) { requestIgnoreBatteryOptimizations(json); }
        @android.webkit.JavascriptInterface public void disableBatteryOptimization(String json) { requestIgnoreBatteryOptimizations(json); }
        @android.webkit.JavascriptInterface public void openBatteryOptimizationSettings(String json) { requestIgnoreBatteryOptimizations(json); }
        @android.webkit.JavascriptInterface public void requestAutoStartPermission(String json) { runOnUiThread(() -> { if (!openManufacturerAutoStartSettings()) { Toast.makeText(MainActivity.this, "Enable Autostart / Background running for this app", Toast.LENGTH_LONG).show(); openAppDetailsSettings(); } }); }
        @android.webkit.JavascriptInterface public void openAutostartSettings(String json) { requestAutoStartPermission(json); }
        @android.webkit.JavascriptInterface public void openAutoStartSettings(String json) { requestAutoStartPermission(json); }
        @android.webkit.JavascriptInterface public void requestBackgroundRunningPermission(String json) { requestAutoStartPermission(json); }
        @android.webkit.JavascriptInterface public void requestBootCompletedPermission(String json) { Toast.makeText(MainActivity.this, "Restart-on-boot is included. Allow autostart/background running if your phone asks.", Toast.LENGTH_LONG).show(); }
        @android.webkit.JavascriptInterface public void enableRestartOnBoot(String json) { requestBootCompletedPermission(json); }
        @android.webkit.JavascriptInterface public void requestNotificationPermission(String json) { runOnUiThread(() -> { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3103); }); }
        @android.webkit.JavascriptInterface public void requestPostNotificationsPermission(String json) { requestNotificationPermission(json); }
        @android.webkit.JavascriptInterface public void askNotificationPermission(String json) { requestNotificationPermission(json); }
        @android.webkit.JavascriptInterface public String getPermissionStatus() {
            try {
                JSONObject perms = new JSONObject();
                boolean fine = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean bg = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                boolean batteryOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
                boolean running = getSharedPreferences("wf_tracking", MODE_PRIVATE).getBoolean("service_running", false);
                perms.put("location", (fine || coarse) ? "granted" : "missing");
                perms.put("background_location", bg ? "granted" : "missing");
                perms.put("battery_optimization", batteryOk ? "unrestricted" : "optimized");
                perms.put("autostart", "open_settings_required");
                perms.put("notification", notify ? "granted" : "missing");
                perms.put("foreground_service", running ? "running" : "stopped");
                perms.put("boot_receiver", "enabled");
                JSONObject status = new JSONObject(); status.put("permissions", perms); status.put("service_state", running ? "foreground_service_running" : "foreground_service_stopped"); return status.toString();
            } catch (Exception e) { return "{\"permissions\":{},\"service_state\":\"unknown\"}"; }
        }
    }

    private void openAppDetailsSettings() {
        try { Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS); intent.setData(Uri.parse("package:" + getPackageName())); startActivity(intent); } catch (Exception ignored) {}
    }
    private void openBatterySettings() { try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception e) { openAppDetailsSettings(); } }
    private boolean openManufacturerAutoStartSettings() {
        Intent[] intents = new Intent[]{
            new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            new Intent().setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
            new Intent().setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            new Intent().setComponent(new ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            new Intent().setComponent(new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            new Intent().setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            new Intent().setComponent(new ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))
        };
        for (Intent intent : intents) { try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (intent.resolveActivity(getPackageManager()) != null) { startActivity(intent); return true; } } catch (Exception ignored) {} }
        return false;
    }

    // JavaScript Bridge - access via window.AndroidBridge in your web JS
    private class NativeBridge {
        @android.webkit.JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @android.webkit.JavascriptInterface
        public void shareText(String text) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(shareIntent, "Share"));
        }

        @android.webkit.JavascriptInterface
        public void openInBrowser(String url) {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        }

        @android.webkit.JavascriptInterface
        public void copyToClipboard(String text) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Copied!", Toast.LENGTH_SHORT).show());
        }

        @android.webkit.JavascriptInterface
        public void vibrate(int ms) {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        }

        @android.webkit.JavascriptInterface
        public String getAppVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) { return "unknown"; }
        }

        @android.webkit.JavascriptInterface
        public void dial(String phone) {
            startActivity(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + phone)));
        }

        @android.webkit.JavascriptInterface
        public void sendEmail(String to, String subject, String body) {
            Intent intent = new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + to));
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            intent.putExtra(Intent.EXTRA_TEXT, body);
            startActivity(intent);
        }

        @android.webkit.JavascriptInterface
        public void closeApp() {
            finish();
        }

        @android.webkit.JavascriptInterface
        public String getDeviceInfo() {
            return "{\"brand\":\"" + Build.BRAND + "\",\"model\":\"" + Build.MODEL + "\",\"sdk\":" + Build.VERSION.SDK_INT + ",\"android\":\"" + Build.VERSION.RELEASE + "\"}";
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 2002) {
            boolean granted = false;
            if (grantResults != null) {
                for (int result : grantResults) {
                    if (result == PackageManager.PERMISSION_GRANTED) {
                        granted = true;
                        break;
                    }
                }
            }
            if (pendingGeoCallback != null) {
                pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}