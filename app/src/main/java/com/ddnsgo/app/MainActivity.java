package com.ddnsgo.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SERVER_MAX_CHECKS = 30;
    private static final long SERVER_RETRY_DELAY_MS = 1000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestPermissions();

        startDdnsGoService();

        setupWebView();

        setContentView(webView);
        showStatusPage("Starting ddns-go", "Local page: " + DdnsGoService.getLocalWebUrl()
                + "<br>LAN page:<br>" + htmlBreaks(DdnsGoService.getLanWebUrl()));
        loadWhenServerReady(0);
        mainHandler.postDelayed(this::requestDisableBatteryOptimizations, 1200L);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void requestDisableBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("允许后台持续运行")
                    .setMessage("ddns-go 需要在后台保持服务监听。请在接下来的系统弹窗中选择允许，将 ddns-go 加入电池优化白名单。")
                    .setPositiveButton("去允许", (dialog, which) -> openBatteryOptimizationRequest())
                    .setNegativeButton("稍后", null)
                    .show();
        } catch (Exception ignored) {
            openBatteryOptimizationSettings();
        }
    }

    private void openBatteryOptimizationRequest() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            openBatteryOptimizationSettings();
        }
    }

    private void openBatteryOptimizationSettings() {
        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        startActivity(intent);
    }

    private void startDdnsGoService() {
        Intent serviceIntent = new Intent(this, DdnsGoService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void setupWebView() {
        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = interceptDdnsGoRequest(request);
                return response != null ? response : super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showStatusPage("Unable to load ddns-go",
                            "WebView error: " + error.getDescription()
                                    + "<br>Local page: " + DdnsGoService.getLocalWebUrl()
                                    + "<br>LAN page:<br>" + htmlBreaks(DdnsGoService.getLanWebUrl()));
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setTextZoom(100);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
    }

    private WebResourceResponse interceptDdnsGoRequest(WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        Uri uri = request.getUrl();
        if (!isDdnsGoUri(uri)) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(uri.toString());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setUseCaches(false);

            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
                if (header.getKey() != null && header.getValue() != null) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            CookieManager cookieManager = CookieManager.getInstance();
            String cookie = cookieManager.getCookie(uri.toString());
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }

            int statusCode = connection.getResponseCode();
            String reasonPhrase = connection.getResponseMessage();
            String contentType = connection.getContentType();
            boolean patchable = shouldPatchFrontend(uri, contentType);
            byte[] body = readResponseBody(connection, statusCode);

            Map<String, String> responseHeaders = flattenHeaders(connection.getHeaderFields(), patchable);
            syncCookies(uri.toString(), connection.getHeaderFields());

            String mimeType = parseMimeType(contentType, uri);
            String encoding = parseCharset(contentType);
            if (patchable) {
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                String patched = patchDdnsGoFrontend(new String(body, charset));
                body = patched.getBytes(StandardCharsets.UTF_8);
                encoding = "UTF-8";
            }

            if (reasonPhrase == null || reasonPhrase.isEmpty()) {
                reasonPhrase = "OK";
            }
            return new WebResourceResponse(
                    mimeType,
                    encoding,
                    statusCode,
                    reasonPhrase,
                    responseHeaders,
                    new ByteArrayInputStream(body)
            );
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isDdnsGoUri(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        return "http".equalsIgnoreCase(scheme)
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                && (port == -1 || port == 9876);
    }

    private boolean shouldPatchFrontend(Uri uri, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.US);
        return type.contains("text/html")
                || type.contains("javascript")
                || path.endsWith(".js")
                || path.isEmpty()
                || "/".equals(path);
    }

    private byte[] readResponseBody(HttpURLConnection connection, int statusCode) throws Exception {
        InputStream stream = statusCode >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                output.write(buffer, 0, len);
            }
            return output.toByteArray();
        }
    }

    private Map<String, String> flattenHeaders(Map<String, List<String>> headers, boolean patched) {
        Map<String, String> result = new HashMap<>();
        if (headers == null) {
            return result;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key == null || values == null || values.isEmpty()) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.US);
            if (patched && ("content-length".equals(lowerKey) || "content-encoding".equals(lowerKey))) {
                continue;
            }
            result.put(key, values.get(0));
        }
        return result;
    }

    private void syncCookies(String url, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        CookieManager cookieManager = CookieManager.getInstance();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null || !"set-cookie".equalsIgnoreCase(key)) {
                continue;
            }
            for (String cookie : entry.getValue()) {
                cookieManager.setCookie(url, cookie);
            }
        }
        cookieManager.flush();
    }

    private String parseMimeType(String contentType, Uri uri) {
        if (contentType != null && !contentType.isEmpty()) {
            int semicolon = contentType.indexOf(';');
            return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.US);
        if (path.endsWith(".js")) {
            return "application/javascript";
        }
        if (path.endsWith(".css")) {
            return "text/css";
        }
        return "text/html";
    }

    private String parseCharset(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.US).startsWith("charset=")) {
                return trimmed.substring("charset=".length()).trim();
            }
        }
        return null;
    }

    private String patchDdnsGoFrontend(String body) {
        String patched = body
                .replace("const delay =", "var delay =")
                .replace("const html2Element =", "var html2Element =")
                .replace("const showMessage =", "var showMessage =")
                .replace("const request =", "var request =")
                .replace("const I18N_MAP =", "var I18N_MAP =")
                .replace("const getCurrentLang =", "var getCurrentLang =")
                .replace("const LANG_SWITCH_OPTIONS =", "var LANG_SWITCH_OPTIONS =")
                .replace("const getLocalLang =", "var getLocalLang =")
                .replace("const i18n =", "var i18n =")
                .replace("const syncLangToggle =", "var syncLangToggle =")
                .replace("const convertDom =", "var convertDom =")
                .replace("const DNS_PROVIDERS =", "var DNS_PROVIDERS =")
                .replace("let configIndex = -1;", "var configIndex = -1;")
                .replace("let dnsConf = [];", "var dnsConf = [];")
                .replace("const globalConf =", "var globalConf =")
                .replace("const defaultDnsConf =", "var defaultDnsConf =")
                .replace("document.getElementById(\"NotAllowWanAccess\").checked", "__ddnsGoChecked(\"NotAllowWanAccess\")")
                .replace("document.getElementById(\"Username\").value", "__ddnsGoValue(\"Username\")")
                .replace("document.getElementById(\"Password\").value", "__ddnsGoValue(\"Password\")")
                .replace("document.getElementById(\"WebhookURL\").value", "__ddnsGoValue(\"WebhookURL\")")
                .replace("document.getElementById(\"WebhookRequestBody\").value", "__ddnsGoValue(\"WebhookRequestBody\")")
                .replace("document.getElementById(\"WebhookHeaders\").value", "__ddnsGoValue(\"WebhookHeaders\")");

        String marker = "</script>\n\n<!-- 全局变量 -->";
        if (!patched.contains(marker)) {
            return patched;
        }
        patched = patched
                .replace("Ipv4Enable: true,", "Ipv4Enable: false,")
                .replace("reloadConf(resp.dnsConf);", "__ddnsGoFillNetInterfaces();reloadConf(resp.dnsConf);__ddnsGoFillNetInterfaces();")
                .replace("reloadConf(\"{{.DnsConf}}\");", "__ddnsGoRestoreNetInterfaceConf();__ddnsGoFillNetInterfaces();reloadConf(\"{{.DnsConf}}\");__ddnsGoRestoreNetInterfaceConf();__ddnsGoFillNetInterfaces();")
                .replace("showConf(configIndex);", "__ddnsGoRestoreNetInterfaceConf();__ddnsGoFillNetInterfaces();showConf(configIndex);")
                .replace("DnsConf: dnsConf", "DnsConf: __ddnsGoPrepareDnsConfForSave(dnsConf)");
        String helpers = "</script>\n<script>"
                + "var __ddnsGoAndroidNetInterfaces=" + DdnsGoService.getAndroidNetInterfacesJson(getFilesDir()) + ";"
                + "function __ddnsGoValue(id){var el=document.getElementById(id);return el?el.value:'';}"
                + "function __ddnsGoChecked(id){var el=document.getElementById(id);return !!(el&&el.checked);}"
                + "function __ddnsGoHasOption(sel,value){for(var i=0;i<sel.options.length;i++){if(sel.options[i].value===value){return true;}}return false;}"
                + "function __ddnsGoFillNetSelect(id,items,helpId,helpKey){"
                + "var sel=document.getElementById(id);if(!sel||!items||!items.length){return;}"
                + "var selected=sel.value;var changed=false;"
                + "for(var i=0;i<items.length;i++){var item=items[i]||{};var name=item.name||'';if(!name||__ddnsGoHasOption(sel,name)){continue;}"
                + "var opt=document.createElement('option');opt.value=name;opt.textContent=name+(item.address?' '+item.address:'');sel.appendChild(opt);changed=true;}"
                + "if(!changed){return;}if(selected){sel.value=selected;}"
                + "var help=document.getElementById(helpId);if(help){help.setAttribute('data-i18n-html',helpKey);if(typeof convertDom==='function'){convertDom(help);}}"
                + "}"
                + "function __ddnsGoFillNetInterfaces(){var data=__ddnsGoAndroidNetInterfaces||{};"
                + "__ddnsGoFillNetSelect('HttpInterface',[].concat(data.ipv4||[],data.ipv6||[]),'HttpInterfaceHelp','HttpInterfaceHelp');"
                + "__ddnsGoFillNetSelect('Ipv4NetInterface',data.ipv4,'Ipv4NetInterfaceHelp','Ipv4NetInterfaceHelp');"
                + "__ddnsGoFillNetSelect('Ipv6NetInterface',data.ipv6,'Ipv6NetInterfaceHelp','Ipv6NetInterfaceHelp');"
                + "}"
                + "function __ddnsGoFindAndroidIface(version,nameOrCmd){var list=((__ddnsGoAndroidNetInterfaces||{})[version]||[]);for(var i=0;i<list.length;i++){var item=list[i]||{};if(item.name===nameOrCmd||item.cmd===nameOrCmd){return item;}}return null;}"
                + "function __ddnsGoHasText(value){return typeof value==='string'&&value.trim().length>0;}"
                + "function __ddnsGoDisableIpv4(conf){conf.Ipv4Enable=false;conf.Ipv4GetType='url';conf.Ipv4Url='';conf.Ipv4NetInterface='';conf.Ipv4Cmd='';conf.Ipv4Domains='';}"
                + "function __ddnsGoRestoreNetInterfaceConf(){if(!window.dnsConf||!Array.isArray(window.dnsConf)){return;}for(var i=0;i<window.dnsConf.length;i++){var conf=window.dnsConf[i];if(!conf){continue;}var v4=__ddnsGoFindAndroidIface('ipv4',conf.Ipv4Cmd);if(v4&&conf.Ipv4GetType==='cmd'){conf.Ipv4GetType='netInterface';conf.Ipv4NetInterface=v4.name;}var v6=__ddnsGoFindAndroidIface('ipv6',conf.Ipv6Cmd);if(v6&&conf.Ipv6GetType==='cmd'){conf.Ipv6GetType='netInterface';conf.Ipv6NetInterface=v6.name;}}}"
                + "function __ddnsGoPrepareDnsConfForSave(source){var list=JSON.parse(JSON.stringify(source||[]));for(var i=0;i<list.length;i++){var conf=list[i];if(!conf){continue;}if(!conf.Ipv4Enable||!__ddnsGoHasText(conf.Ipv4Domains)){__ddnsGoDisableIpv4(conf);}else{var v4=__ddnsGoFindAndroidIface('ipv4',conf.Ipv4NetInterface);if(conf.Ipv4GetType==='netInterface'&&v4&&v4.cmd){conf.Ipv4GetType='cmd';conf.Ipv4Cmd=v4.cmd;}}var v6=__ddnsGoFindAndroidIface('ipv6',conf.Ipv6NetInterface);if(conf.Ipv6GetType==='netInterface'&&v6&&v6.cmd){conf.Ipv6GetType='cmd';conf.Ipv6Cmd=v6.cmd;}}return list;}"
                + "__ddnsGoFillNetInterfaces();document.addEventListener('DOMContentLoaded',__ddnsGoFillNetInterfaces);"
                + "</script>\n\n<!-- 全局变量 -->";
        return patched.replace(marker, helpers);
    }

    private void loadWhenServerReady(int attempt) {
        new Thread(() -> {
            boolean ready = isServerReady();
            mainHandler.post(() -> {
                if (webView == null) {
                    return;
                }
                if (ready) {
                    webView.loadUrl(DdnsGoService.getLocalWebUrl());
                } else if (attempt < SERVER_MAX_CHECKS) {
                    mainHandler.postDelayed(
                            () -> loadWhenServerReady(attempt + 1),
                            SERVER_RETRY_DELAY_MS
                    );
                } else {
                    showStatusPage("ddns-go did not start",
                            "Check that the bundled ddns-go binary matches this device CPU/Android ABI."
                                    + "<br>Local page: " + DdnsGoService.getLocalWebUrl()
                                    + "<br>LAN page:<br>" + htmlBreaks(DdnsGoService.getLanWebUrl()));
                }
            });
        }, "ddns-go-web-check").start();
    }

    private boolean isServerReady() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(DdnsGoService.getLocalWebUrl()).openConnection();
            connection.setConnectTimeout(800);
            connection.setReadTimeout(800);
            connection.setUseCaches(false);
            return connection.getResponseCode() > 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void showStatusPage(String title, String body) {
        if (webView == null) {
            return;
        }
        String html = "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>"
                + "body{margin:0;font-family:sans-serif;background:#f5f7fb;color:#18202f;"
                + "display:flex;min-height:100vh;align-items:center;justify-content:center;}"
                + "main{width:min(680px,calc(100vw - 40px));}"
                + "h1{font-size:24px;margin:0 0 12px;}"
                + "p{font-size:15px;line-height:1.6;margin:0;color:#4c586d;word-break:break-word;}"
                + "</style></head><body><main><h1>"
                + title
                + "</h1><p>"
                + body
                + "</p></main></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String htmlBreaks(String text) {
        return text == null ? "" : text.replace("\n", "<br>");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
