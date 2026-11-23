package com.clipme.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    WebView wv;
    ValueCallback<Uri[]> pending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        wv = new WebView(this);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.addJavascriptInterface(new Bridge(), "AndroidBridge");
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/*");
                startActivityForResult(i, 1);
                return true;
            }
        });
        wv.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && pending != null) {
            boolean ok = res == RESULT_OK && data != null && data.getData() != null;
            pending.onReceiveValue(ok ? new Uri[]{data.getData()} : null);
            pending = null;
        }
    }

    @Override
    public void onBackPressed() {
        wv.evaluateJavascript("(function(){if(S.scr!=='home'){S.scr='home';R();return 1}return 0})()",
            v -> { if ("0".equals(v)) finish(); });
    }

    void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    class Bridge {
        @JavascriptInterface
        public void save(String b64, String name) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "video/webm");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClipMe");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(data); }
                    toast("Saved to Downloads/ClipMe");
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    try (FileOutputStream o = new FileOutputStream(new File(dir, name))) { o.write(data); }
                    toast("Saved to " + dir.getAbsolutePath());
                }
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
        }
    }
}
