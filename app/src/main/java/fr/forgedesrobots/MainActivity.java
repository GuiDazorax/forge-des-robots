package fr.forgedesrobots;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

public class MainActivity extends Activity {
    private WebView web;
    private BtBridge bt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(0xFF0D0F1F);

        bt = new BtBridge(this, web);
        web.addJavascriptInterface(bt, "AndroidBT");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        hideSystemBars();
    }

    private void hideSystemBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BtBridge.REQ_PERMS) {
            boolean all = grantResults.length > 0;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) all = false;
            bt.onPermissions(all);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        bt.onActivityResult(requestCode, resultCode);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){var q=document.getElementById('quitBtn');var c=document.getElementById('combat');if(c&&!c.hidden&&q){q.click();return 1;}return 0;})()",
                v -> { if (!"1".equals(v)) super.onBackPressed(); });
    }

    @Override
    protected void onDestroy() {
        bt.stop();
        super.onDestroy();
    }
}
