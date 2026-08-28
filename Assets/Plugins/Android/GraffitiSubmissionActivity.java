package com.graffitihunter.demo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.WebChromeClient;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

public class GraffitiSubmissionActivity extends Activity {
    private static final String FORM_URL =
        "https://getitdone.sandiego.gov/TSWNewReport?type=Graffiti";
    private WebView webView;
    private boolean prepared;
    private boolean accountMode;
    private boolean loginModalRequested;
    private boolean authenticatedThisSession;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSaveFormData(true);
        settings.setAllowFileAccess(true);
        webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        accountMode = getIntent().getBooleanExtra("accountMode", false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                view.postDelayed(() -> detectLoginState(), 900L);
                if (accountMode && !loginModalRequested) {
                    loginModalRequested = true;
                    view.postDelayed(() -> openLoginModal(), 700L);
                    view.postDelayed(() -> openLoginModal(), 1800L);
                    view.postDelayed(() -> openLoginModal(), 3500L);
                }
                if (!accountMode && !prepared) prepareForm();
            }
        });
        String requestedUrl = extra("startUrl");
        webView.loadUrl(requestedUrl.length() > 0 ? requestedUrl : FORM_URL);
    }

    private void detectLoginState() {
        String script = "(function(){var t=(document.body?document.body.innerText:'').toUpperCase();" +
            "return t.indexOf('LOGIN/REGISTER')<0 && (t.indexOf('LOG OUT')>=0||t.indexOf('LOGOUT')>=0||t.indexOf('MY REPORT')>=0);})()";
        webView.evaluateJavascript(script, result -> {
            boolean signedIn = "true".equalsIgnoreCase(result);
            if (signedIn) {
                authenticatedThisSession = true;
                getSharedPreferences("HunterAccount", MODE_PRIVATE).edit()
                    .putBoolean("signed_in", true).apply();
            } else if (!authenticatedThisSession && !accountMode) {
                getSharedPreferences("HunterAccount", MODE_PRIVATE).edit()
                    .putBoolean("signed_in", false).apply();
            }
            if (authenticatedThisSession && accountMode) {
                webView.postDelayed(() -> finish(), 650L);
            }
        });
    }

    private void openLoginModal() {
        String script = "(function(){" +
            "if(document.querySelector('input[type=password]'))return 'open';" +
            "try{if(window.jQuery&&jQuery('#login-modal').length){jQuery('#login-modal').modal('show');return 'jquery';}}catch(x){}" +
            "var direct=document.querySelector('a[href=\"#login-modal\"],a[href$=\"#login-modal\"],[data-target=\"#login-modal\"],[data-bs-target=\"#login-modal\"]');" +
            "if(direct){direct.click();return 'direct';}" +
            "function visible(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);" +
            "return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}" +
            "function exact(text){return [].slice.call(document.querySelectorAll('a,button,[role=button],li,span,div'))" +
            ".find(function(e){var t=e.textContent.trim().toUpperCase();return visible(e)&&(t===text||t.indexOf(text)>=0);});}" +
            "var login=exact('LOGIN/REGISTER');if(login){login.click();return 'login';}" +
            "var menu=exact('MENU');if(menu){menu.click();setTimeout(function(){var l=exact('LOGIN/REGISTER');if(l)l.click();},450);return 'menu';}" +
            "return 'waiting';})()";
        webView.evaluateJavascript(script, null);
    }

    @Override protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    private void prepareForm() {
        prepared = true;
        String lat = extra("latitude");
        String lon = extra("longitude");
        String category = extra("locationType");
        String offensive = extra("offensive");
        String description = extra("description");
        String locationDescription = extra("locationDescription");
        String photoBase64 = encodeFile(extra("photoPath"));
        String mapBase64 = encodeFile(extra("mapPath"));
        String photoName = new File(extra("photoPath")).getName();
        String mapName = new File(extra("mapPath")).getName();

        String script =
            "(function(){" +
            "var start=[].slice.call(document.querySelectorAll('button')).find(function(b){return b.textContent.trim()==='Start';});" +
            "if(start)start.click();" +
            "setTimeout(function(){" +
            "function setv(id,v){var e=document.getElementById(id);if(!e)return;e.value=v;" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "function sets(id,v,answerKey){var e=document.getElementById(id);if(!e)return false;" +
            "for(var i=0;i<e.options.length;i++){if(e.options[i].text.trim()===v||e.options[i].value===v){" +
            "e.selectedIndex=i;e.options[i].selected=true;break;}}" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));" +
            "try{if(window.SelectedAns)window.SelectedAns(answerKey);}catch(x){}return e.value===v;}" +
            // Coordinates are more precise than a reverse-geocoded mailing address
            // and let Get It Done place its map pin at the actual captured location.
            "setv('pac-inputt'," + q(lat + ", " + lon) + ");" +
            "setv('pgid:fmId:LatitudeId'," + q(lat) + ");" +
            "setv('pgid:fmId:LongitudeId'," + q(lon) + ");" +
            "sets('1Ans'," + q(offensive) + ",'1P');" +
            "sets('2Ans'," + q(category) + ",'2P');" +
            "setTimeout(function(){sets('1Ans'," + q(offensive) + ",'1P');sets('2Ans'," + q(category) + ",'2P');},650);" +
            "setv('pgid:fmId:FormDescriptonId'," + q(description) + ");" +
            "setv('pgid:fmId:FormLocDescriptonId'," + q(locationDescription) + ");" +
            "var input=document.getElementById('fileupload');if(input&&window.DataTransfer){" +
            "var dt=new DataTransfer();" +
            fileScript(photoBase64, photoName, "image/jpeg") +
            fileScript(mapBase64, mapName, "image/png") +
            "input.files=dt.files;input.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "window.scrollTo(0,0);" +
            "},1800);" +
            "})();";
        webView.evaluateJavascript(script, null);
    }

    private String extra(String name) {
        String value = getIntent().getStringExtra(name);
        return value == null ? "" : value;
    }

    private static String q(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\r", "\\r").replace("\n", "\\n") + "'";
    }

    private static String fileScript(String base64, String name, String mime) {
        if (base64.length() == 0) return "";
        return "try{var b=atob(" + q(base64) + ");var a=new Uint8Array(b.length);" +
            "for(var i=0;i<b.length;i++)a[i]=b.charCodeAt(i);" +
            "dt.items.add(new File([a]," + q(name) + ",{type:" + q(mime) + "}));}catch(e){}";
    }

    private static String encodeFile(String path) {
        if (path == null || path.length() == 0) return "";
        try {
            FileInputStream input = new FileInputStream(path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            input.close();
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
