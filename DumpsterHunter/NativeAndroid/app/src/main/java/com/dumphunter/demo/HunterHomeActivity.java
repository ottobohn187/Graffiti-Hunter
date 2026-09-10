package com.dumphunter.demo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

@android.annotation.SuppressLint("UnsafeOptInUsageError")
public class HunterHomeActivity extends Activity {
    private TextView accountStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(5, 12, 18));
        drawHome();
    }

    @Override protected void onResume() {
        super.onResume();
        // Remove obsolete Unity-era handoff state left by an upgraded install.
        getSharedPreferences("DumpHunterNavigation", MODE_PRIVATE).edit()
            .remove("launch_action").apply();
        getSharedPreferences("DumpHunterNativeCamera", MODE_PRIVATE).edit()
            .remove("direct_review_handoff").apply();
        refreshAccount();
    }

    private void drawHome() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(16), dp(28), dp(24));
        page.setBackgroundColor(Color.rgb(7, 16, 24));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(getApplicationInfo().icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(108), dp(108)));

        TextView title = label("DUMPSTER HUNTER", 24, true);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, dp(108), 1f);
        titleParams.setMargins(dp(10), 0, 0, 0);
        brand.addView(title, titleParams);
        page.addView(brand, full(dp(118)));

        TextView build = label("BUILD 0.3.0", 13, true);
        build.setTextColor(Color.rgb(172, 184, 194));
        page.addView(build, full(dp(25)));
        TextView tagline = label("CAPTURE  •  MAP  •  REPORT", 12, true);
        tagline.setTextColor(Color.rgb(30, 205, 231));
        page.addView(tagline, full(dp(30)));
        accountStatus = label("GET IT DONE: CHECKING LOGIN...", 15, false);
        page.addView(accountStatus, full(dp(48)));

        Button phoneCapture = button("PHONE CAMERA   ›",
            Color.rgb(221, 16, 35), Color.rgb(255, 62, 52), Color.WHITE);
        phoneCapture.setOnClickListener(v -> openCaptureFlow("phone"));
        page.addView(phoneCapture, full(dp(68)));

        Button glassesCapture = button("GLASSES CAMERA   ›",
            Color.rgb(0, 112, 154), Color.rgb(20, 194, 220), Color.rgb(115, 231, 247));
        glassesCapture.setOnClickListener(v -> openCaptureFlow("glasses"));
        page.addView(glassesCapture, full(dp(68)));

        Button queue = button("REVIEW QUEUE   ›",
            Color.rgb(34, 45, 58), Color.rgb(62, 78, 96), Color.rgb(116, 210, 230));
        queue.setOnClickListener(v -> openQueueReview());
        page.addView(queue, full(dp(68)));

        Button account = button("LOGIN / REGISTER   ›",
            Color.rgb(139, 82, 0), Color.rgb(221, 148, 23), Color.rgb(255, 211, 107));
        account.setOnClickListener(v -> {
            Intent i = new Intent(this, DumpSubmissionActivity.class);
            i.putExtra("accountMode", true);
            i.putExtra("startUrl", "https://getitdone.sandiego.gov/TSWViewReportByList#login-modal");
            startActivity(i);
        });
        page.addView(account, full(dp(68)));

        Button projectSite = button("GRAFFITIHUNTER.NET   ↗",
            Color.rgb(25, 31, 40), Color.rgb(45, 57, 70), Color.rgb(105, 207, 228));
        projectSite.setContentDescription("Open the Dumpster Hunter companion page");
        projectSite.setOnClickListener(v -> {
            Intent site = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://graffitihunter.net/#dumpster-hunter"));
            startActivity(site);
        });
        page.addView(projectSite, full(dp(68)));

        TextView note = label("Dumpster Hunter is the illegal-dumping wingman to Graffiti Hunter. Visit graffitihunter.net for project information.\n\nYour Get It Done session is kept in the app. Android Autofill or Samsung Pass can securely remember your email and password.", 14, false);
        note.setPadding(dp(8), dp(18), dp(8), 0);
        page.addView(note, full(dp(132)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private void refreshAccount() {
        if (accountStatus == null) return;
        boolean signedIn = getSharedPreferences("HunterAccount", MODE_PRIVATE).getBoolean("signed_in", false);
        accountStatus.setText(signedIn ? "GET IT DONE: SIGNED IN" : "GET IT DONE: LOGIN NEEDED");
        accountStatus.setTextColor(signedIn ? Color.rgb(91, 220, 145) : Color.rgb(255, 196, 80));
    }

    private void openQueueReview() {
        int queued = countQueuedReports();
        if (queued == 0) {
            Toast.makeText(this, "No captures in queue.", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, NativeQueueActivity.class));
    }

    private int countQueuedReports() {
        File appFiles = getExternalFilesDir(null);
        if (appFiles == null) appFiles = getFilesDir();
        File root = new File(appFiles, "DumpReports");
        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null) return 0;
        int count = 0;
        for (File folder : folders) {
            if (new File(folder, "pending_get_it_done.json").isFile()) count++;
        }
        return count;
    }

    private void openCaptureFlow(String source) {
        getSharedPreferences("DumpHunterNavigation", MODE_PRIVATE).edit()
            .remove("launch_action").commit();
        getSharedPreferences("DumpHunterNativeCamera", MODE_PRIVATE).edit()
            .remove("direct_review_handoff")
            .remove("capture_path").remove("capture_rotation")
            .remove("capture_lat_bits").remove("capture_lon_bits")
            .remove("capture_accuracy").remove("capture_location_time")
            .putString("capture_source", source).commit();
        // Launch Camera2 directly with no intermediate activity.
        Intent camera = new Intent(this, NativeCameraCaptureActivity.class);
        camera.putExtra("launched_from_home", true);
        startActivity(camera);
    }

    private TextView label(String text, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String text, int startColor, int endColor, int strokeColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setLetterSpacing(0.035f);
        b.setAllCaps(false);
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] { startColor, endColor });
        background.setCornerRadius(dp(18));
        background.setStroke(dp(2), strokeColor);
        b.setBackground(background);
        b.setElevation(dp(7));
        LinearLayout.LayoutParams p = full(dp(68));
        p.setMargins(0, dp(7), 0, dp(7));
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout.LayoutParams full(int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
        p.setMargins(0, dp(7), 0, dp(7));
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
