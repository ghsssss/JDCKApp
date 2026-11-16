package com.yao.jdck;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebStorage;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private WebView webView;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private String currentCk = "";
    private volatile boolean pushing = false;

    private static final String LOGIN_URL = "https://plogin.m.jd.com/login/login";
    private static final String DEFAULT_QL_URL = "http://192.168.50.143:5700";
    private static final String[] COOKIE_URLS = {
            "https://www.jd.com", "https://passport.jd.com", "https://m.jd.com", "https://plogin.m.jd.com"
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        webView = findViewById(R.id.webview);
        status = findViewById(R.id.status);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                checkCookie();
            }
        });
        webView.loadUrl(LOGIN_URL);

        findViewById(R.id.btnCopy).setOnClickListener(v -> copyCk());
        findViewById(R.id.btnPush).setOnClickListener(v -> pushQinglong());
        findViewById(R.id.btnSetting).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnReload).setOnClickListener(v -> {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            currentCk = "";
            status.setText("已清除登录态，请在上方重新登录");
            webView.loadUrl(LOGIN_URL);
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private Map<String, String> allCookies() {
        CookieManager cm = CookieManager.getInstance();
        Map<String, String> map = new LinkedHashMap<>();
        for (String u : COOKIE_URLS) {
            String c = cm.getCookie(u);
            if (c == null) continue;
            for (String part : c.split(";")) {
                part = part.trim();
                int i = part.indexOf('=');
                if (i > 0) {
                    String k = part.substring(0, i);
                    if (!map.containsKey(k)) {
                        map.put(k, part.substring(i + 1));
                    }
                }
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String extractPin(String ck) {
        for (String part : ck.replace("&", ";").split(";")) {
            part = part.trim();
            if (part.startsWith("pt_pin=")) {
                return decode(part.substring(7));
            }
        }
        return null;
    }

    private void checkCookie() {
        Map<String, String> m = allCookies();
        String key = m.get("pt_key");
        String pin = m.get("pt_pin");
        if (key != null && !key.isEmpty() && pin != null && !pin.isEmpty()) {
            currentCk = "pt_key=" + key + ";pt_pin=" + decode(pin) + ";";
            status.setText("已获取 CK：" + decode(pin) + "\n可复制或推送青龙");
        } else {
            status.setText("未检测到登录态，请在上方完成登录（扫码/短信/密码）");
        }
    }

    private void copyCk() {
        if (currentCk.isEmpty()) {
            checkCookie();
        }
        if (currentCk.isEmpty()) {
            toast("还没有登录态，先登录京东");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ck", currentCk));
        toast("已复制到剪贴板");
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText etUrl = new EditText(this);
        etUrl.setHint("青龙地址（留空则用默认值）");
        etUrl.setText(sp.getString("ql_url", ""));
        box.addView(etUrl);

        EditText etId = new EditText(this);
        etId.setHint("Client ID");
        etId.setText(sp.getString("ql_cid", ""));
        box.addView(etId);

        EditText etSecret = new EditText(this);
        etSecret.setHint("Client Secret");
        etSecret.setText(sp.getString("ql_secret", ""));
        box.addView(etSecret);

        new AlertDialog.Builder(this)
                .setTitle("青龙面板配置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    sp.edit()
                            .putString("ql_url", etUrl.getText().toString().trim())
                            .putString("ql_cid", etId.getText().toString().trim())
                            .putString("ql_secret", etSecret.getText().toString().trim())
                            .apply();
                    toast("已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String normalizeQlUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return DEFAULT_QL_URL;
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://" + s;
        }
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void pushQinglong() {
        if (pushing) {
            return;
        }
        if (currentCk.isEmpty()) {
            checkCookie();
        }
        if (currentCk.isEmpty()) {
            toast("还没有登录态，先登录京东");
            return;
        }
        String raw = sp.getString("ql_url", "");
        String base = normalizeQlUrl(raw);
        String cid = sp.getString("ql_cid", "").trim();
        String secret = sp.getString("ql_secret", "").trim();
        if (cid.isEmpty() || secret.isEmpty()) {
            toast("请先在「设置」里配置青龙密钥");
            showSettings();
            return;
        }
        if (!base.equals(raw)) {
            sp.edit().putString("ql_url", base).apply();
        }
        pushing = true;
        status.setText("正在推送青龙…");
        String fBase = base, fCid = cid, fSecret = secret;
        new Thread(() -> {
            String result;
            try {
                result = push(fBase, fCid, fSecret);
            } catch (Exception e) {
                result = "推送失败：" + e.getMessage();
            }
            final String r = result;
            ui.post(() -> {
                pushing = false;
                status.setText(r);
                toast(r.startsWith("推送成功") ? "推送成功" : "推送失败，看下方详情");
            });
        }).start();
    }

    private long findEnvId(JSONArray data, String pin) throws Exception {
        if (data == null) return 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject e = data.getJSONObject(i);
            if (!"JD_COOKIE".equals(e.optString("name"))) continue;
            for (String line : e.optString("value", "").split("\n")) {
                String p = extractPin(line);
                if (p != null && p.equalsIgnoreCase(pin)) return e.optLong("id");
            }
        }
        return 0;
    }

    private String enableEnv(String base, String token, long envId) {
        if (envId <= 0) return "（⚠️ 未能定位变量，启用状态未确认）";
        try {
            JSONArray ids = new JSONArray().put(envId);
            JSONObject r = new JSONObject(http(base + "/open/envs/enable", "PUT", ids, token));
            return r.optInt("code") == 200 ? "（变量已启用）" : "（⚠️ 启用失败，请手动检查）";
        } catch (Exception e) {
            return "（⚠️ 启用失败，请手动检查）";
        }
    }

    private String push(String base, String cid, String secret) throws Exception {
        // 1. 认证拿 token（青龙 2.24+ 为 GET /open/auth/token）
        String qs = "client_id=" + URLEncoder.encode(cid, "UTF-8")
                + "&client_secret=" + URLEncoder.encode(secret, "UTF-8");
        JSONObject auth = new JSONObject(http(base + "/open/auth/token?" + qs, "GET", null, null));
        if (auth.optInt("code") != 200) {
            throw new Exception("青龙认证失败：" + auth);
        }
        String token = auth.getJSONObject("data").getString("token");

        // 2. 找现有的 JD_COOKIE 环境变量
        JSONObject list = new JSONObject(
                http(base + "/open/envs?searchValue=JD_COOKIE", "GET", null, token));
        if (list.optInt("code") != 200) {
            throw new Exception("读取环境变量失败：" + list);
        }
        JSONArray data = list.optJSONArray("data");
        String newPin = extractPin(currentCk);
        JSONObject dedicated = null;
        JSONObject mixed = null;
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject e = data.getJSONObject(i);
                if (!"JD_COOKIE".equals(e.optString("name"))) continue;
                String[] lines = e.optString("value", "").split("\n");
                int count = 0;
                boolean hit = false;
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    count++;
                    String p = extractPin(line);
                    if (p != null && p.equalsIgnoreCase(newPin)) hit = true;
                }
                if (!hit) continue;
                if (count == 1 && dedicated == null) dedicated = e;
                else if (count > 1 && mixed == null) mixed = e;
            }
        }

        long targetId;
        JSONObject done;
        String act;
        if (dedicated != null) {
            targetId = dedicated.optLong("id");
            JSONObject upd = new JSONObject()
                    .put("id", targetId)
                    .put("name", "JD_COOKIE")
                    .put("value", currentCk);
            done = new JSONObject(http(base + "/open/envs", "PUT", upd, token));
            act = "已更新专属变量";
        } else {
            if (mixed != null) {
                StringBuilder kept = new StringBuilder();
                for (String line : mixed.optString("value", "").split("\n")) {
                    if (line.trim().isEmpty()) continue;
                    String p = extractPin(line);
                    if (p != null && p.equalsIgnoreCase(newPin)) continue;
                    if (kept.length() > 0) kept.append('\n');
                    kept.append(line.trim());
                }
                JSONObject upd = new JSONObject()
                        .put("id", mixed.optLong("id"))
                        .put("name", "JD_COOKIE")
                        .put("value", kept.length() > 0 ? kept.toString() : " ");
                http(base + "/open/envs", "PUT", upd, token);
            }
            JSONArray arr = new JSONArray();
            arr.put(new JSONObject().put("name", "JD_COOKIE").put("value", currentCk));
            done = new JSONObject(http(base + "/open/envs", "POST", arr, token));
            JSONObject after = new JSONObject(http(base + "/open/envs?searchValue=JD_COOKIE", "GET", null, token));
            targetId = findEnvId(after.optJSONArray("data"), newPin);
            act = mixed != null ? "已拆出并新建专属变量" : "已新建专属变量";
        }
        if (done.optInt("code") != 200) {
            throw new Exception("写入环境变量失败：" + done);
        }
        return "推送成功：" + newPin + " " + act + "（每号一条）" + enableEnv(base, token, targetId);
    }

    private String http(String urlStr, String method, Object body, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
        }
        if (code >= 400) {
            throw new Exception("HTTP " + code + ": " + sb);
        }
        return sb.toString();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
