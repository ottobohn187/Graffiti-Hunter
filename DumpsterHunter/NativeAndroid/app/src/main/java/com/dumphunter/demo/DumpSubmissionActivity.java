package com.dumphunter.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.WebChromeClient;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.View;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;

public class DumpSubmissionActivity extends Activity {
    private static final String FORM_URL =
        "https://getitdone.sandiego.gov/TSWNewReport?type=Illegal%20Dumping";
    private WebView webView;
    private boolean prepared;
    private boolean accountMode;
    private boolean loginModalRequested;
    private boolean authenticatedThisSession;
    private boolean returningHome;
    private boolean submissionComplete;
    private int prepareAttempts;

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
                // Opening the report form must not depend on the City's changing
                // login/header text. A persisted login cookie is still shared by
                // this WebView, while the form transition is handled separately.
                if (!accountMode && !prepared) {
                    view.postDelayed(() -> prepareForm(), 700L);
                }
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
                if (accountMode) {
                    webView.postDelayed(() -> finish(), 650L);
                } else if (!prepared) {
                    prepareForm();
                }
            } else if (!authenticatedThisSession && accountMode) {
                getSharedPreferences("HunterAccount", MODE_PRIVATE).edit()
                    .putBoolean("signed_in", false).apply();
                if (!loginModalRequested) {
                    loginModalRequested = true;
                    openLoginModal();
                    webView.postDelayed(() -> openLoginModal(), 1100L);
                }
                // The City's modal signs in without always navigating to a new page.
                // Poll the same authenticated WebView session, then prefill once.
                webView.postDelayed(() -> detectLoginState(), 2400L);
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
        if (prepared || isFinishing()) return;
        prepareAttempts++;
        String probe = "(function(){" +
            "function visible(e){if(!e)return false;var r=e.getBoundingClientRect();var s=getComputedStyle(e);" +
            "return e.getClientRects().length>0&&r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}" +
            "var address=document.getElementById('pac-inputt');if(visible(address))return 'ready';" +
            "var list=[].slice.call(document.querySelectorAll('button,input[type=button],input[type=submit],a,[role=button]'));" +
            "var start=list.find(function(e){var t=(e.textContent||e.value||'').trim().toUpperCase();" +
            "return visible(e)&&(t==='START'||t.indexOf('START REPORT')>=0||t.indexOf('GET STARTED')>=0);});" +
            "if(start){start.click();return 'clicked';}return 'waiting';})()";
        webView.evaluateJavascript(probe, result -> {
            if (result != null && result.toLowerCase(java.util.Locale.US).contains("ready")) {
                fillFormFields();
            } else if (!prepared && prepareAttempts < 30 && !isFinishing()) {
                webView.postDelayed(() -> prepareForm(), 900L);
            } else if (!prepared && !isFinishing()) {
                prepareAttempts = 0;
                Toast.makeText(this, "Still opening the Get It Done form...", Toast.LENGTH_SHORT).show();
                webView.postDelayed(() -> prepareForm(), 1500L);
            }
        });
    }

    private void fillFormFields() {
        if (prepared || isFinishing()) return;
        prepared = true;
        String lat = extra("latitude");
        String lon = extra("longitude");
        String issue = extra("dumpingIssue");
        String rightOfWay = extra("publicRightOfWay");
        String privateProperty = extra("privateProperty");
        String description = withProjectLink(extra("description"));
        String locationDescription = extra("locationDescription");
        String photoBase64 = encodeFile(extra("photoPath"));
        String mapBase64 = encodeFile(extra("mapPath"));
        String photoName = new File(extra("photoPath")).getName();
        String mapName = new File(extra("mapPath")).getName();

        String script =
            "(function(){" +
            "function fill(){" +
            "function setv(id,v){var e=document.getElementById(id);if(!e)return;e.value=v;" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "function enter(e){if(!e)return;e.focus();['keydown','keypress','keyup'].forEach(function(t){" +
            "var k=new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true});" +
            "try{Object.defineProperty(k,'keyCode',{get:function(){return 13;}});" +
            "Object.defineProperty(k,'which',{get:function(){return 13;}});}catch(x){}e.dispatchEvent(k);});" +
            "try{if(window.google&&google.maps&&google.maps.event)google.maps.event.trigger(e,'keydown',{keyCode:13,which:13});}catch(x){}e.blur();}" +
            "function sets(id,v,answerKey){var e=document.getElementById(id);if(!e)return false;" +
            "for(var i=0;i<e.options.length;i++){if(e.options[i].text.trim()===v||e.options[i].value===v){" +
            "e.selectedIndex=i;e.options[i].selected=true;break;}}" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));" +
            "try{if(answerKey&&window.SelectedAns)window.SelectedAns(answerKey);}catch(x){}return e.value===v;}" +
            // Coordinates are more precise than a reverse-geocoded mailing address
            // and let Get It Done place its map pin at the actual captured location.
            "setv('pac-inputt'," + q(lat + ", " + lon) + ");" +
            "setTimeout(function(){enter(document.getElementById('pac-inputt'));},350);" +
            "setv('pgid:fmId:LatitudeId'," + q(lat) + ");" +
            "setv('pgid:fmId:LongitudeId'," + q(lon) + ");" +
            "sets('IllegalIssue'," + q(issue) + ",'IllegalIssue');" +
            "sets('IllegalROW'," + q(rightOfWay) + ",'IllegalROW');" +
            "if(" + q(rightOfWay) + "==='No')sets('privatePropertyPicklist'," + q(privateProperty) + ",'privatePropertyPicklist');" +
            "setTimeout(function(){sets('IllegalIssue'," + q(issue) + ",'IllegalIssue');" +
            "sets('IllegalROW'," + q(rightOfWay) + ",'IllegalROW');" +
            "if(" + q(rightOfWay) + "==='No')sets('privatePropertyPicklist'," + q(privateProperty) + ",'privatePropertyPicklist');},650);" +
            "setv('pgid:fmId:FormDescriptonId'," + q(description) + ");" +
            "setv('pgid:fmId:FormLocDescriptonId'," + q(locationDescription) + ");" +
            "var input=document.getElementById('fileupload');if(input&&window.DataTransfer&&!window.__dumpingHunterFilesAttached){" +
            "window.__dumpingHunterFilesAttached=true;" +
            "var dt=new DataTransfer();" +
            fileScript(photoBase64, photoName, "image/jpeg") +
            fileScript(mapBase64, mapName, "image/png") +
            "input.files=dt.files;input.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "window.scrollTo(0,0);" +
            "}" +
            "fill();setTimeout(fill,650);setTimeout(fill,1600);" +
            "})();";
        webView.evaluateJavascript(script, null);
        webView.postDelayed(() -> watchForSubmissionSuccess(), 2500L);
    }

    private void watchForSubmissionSuccess() {
        if (accountMode || submissionComplete || isFinishing()) return;
        String script = "(function(){function visible(e){if(!e)return false;var r=e.getBoundingClientRect();" +
            "var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}" +
            "var heading=[].slice.call(document.querySelectorAll('h1')).find(function(e){return visible(e)&&" +
            "e.textContent.toUpperCase().indexOf('YOUR REPORT HAS BEEN SUCCESSFULLY CAPTURED')>=0;});" +
            "var number=[].slice.call(document.querySelectorAll('p.subheading')).find(function(e){" +
            "var t=e.textContent.trim();return visible(e)&&/^REPORT NUMBER:\\s*(?!0{6}\\b)[A-Z0-9-]+/i.test(t);});" +
            "return !!heading&&!!number;})()";
        webView.evaluateJavascript(script, result -> {
            if ("true".equalsIgnoreCase(result)) {
                submissionComplete = true;
                Toast.makeText(this, "Get It Done report submitted successfully.", Toast.LENGTH_LONG).show();
                webView.postDelayed(() -> finish(), 900L);
            } else if (!isFinishing()) {
                webView.postDelayed(() -> watchForSubmissionSuccess(), 1800L);
            }
        });
    }

    private String extra(String name) {
        String value = getIntent().getStringExtra(name);
        return value == null ? "" : value;
    }

    private static String withProjectLink(String description) {
        if (description.toLowerCase(java.util.Locale.US).contains("graffitihunter.net")) return description;
        String separator = description.trim().isEmpty() ? "" : "\n\n";
        return description + separator + "Submitted by Dumpster Hunter Build 0.3.0 (home made app), the Graffiti Hunter wingman. GraffitiHunter.net.";
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

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        finish();
    }

    @Override public void finish() {
        if (!returningHome) {
            returningHome = true;
            Class<?> destination = accountMode
                ? HunterHomeActivity.class
                : NativeQueueActivity.class;
            Intent back = new Intent(getApplicationContext(), destination);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            getApplicationContext().startActivity(back);
        }
        super.finish();
    }
}
