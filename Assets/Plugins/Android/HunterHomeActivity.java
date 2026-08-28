package com.graffitihunter.demo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

public class HunterHomeActivity extends Activity {
    private TextView accountStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 20, 31));
        drawHome();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean photoHandoff = getSharedPreferences(
            "GraffitiHunterNativeCamera", MODE_PRIVATE)
            .getBoolean("direct_review_handoff", false);
        if (!photoHandoff) {
            getSharedPreferences("GraffitiHunterNavigation", MODE_PRIVATE).edit()
                .remove("launch_action").apply();
        }
        refreshAccount();
    }

    private void drawHome() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(30), dp(28), dp(24));
        page.setBackgroundColor(Color.rgb(12, 28, 42));

        ImageView logo = new ImageView(this);
        logo.setImageResource(getApplicationInfo().icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        page.addView(logo, new LinearLayout.LayoutParams(dp(150), dp(150)));

        TextView title = label("GRAFFITI HUNTER  v0.5.6", 25, true);
        page.addView(title, full(dp(58)));
        accountStatus = label("GET IT DONE: CHECKING LOGIN...", 15, false);
        page.addView(accountStatus, full(dp(48)));

        Button phoneCapture = button("PHONE CAMERA");
        phoneCapture.setOnClickListener(v -> openCaptureFlow("phone"));
        page.addView(phoneCapture, full(dp(68)));

        Button glassesCapture = button("GLASSES CAMERA");
        glassesCapture.setOnClickListener(v -> openCaptureFlow("glasses"));
        page.addView(glassesCapture, full(dp(68)));

        Button queue = button("REVIEW QUEUE");
        queue.setOnClickListener(v -> openQueueReview());
        page.addView(queue, full(dp(68)));

        Button account = button("LOGIN / REGISTER - ACCOUNT");
        account.setOnClickListener(v -> {
            Intent i = new Intent(this, GraffitiSubmissionActivity.class);
            i.putExtra("accountMode", true);
            i.putExtra("startUrl", "https://getitdone.sandiego.gov/TSWViewReportByList#login-modal");
            startActivity(i);
        });
        page.addView(account, full(dp(68)));

        TextView note = label("Your Get It Done session is kept in the app. Android Autofill or Samsung Pass can securely remember your email and password.", 14, false);
        note.setPadding(dp(8), dp(18), dp(8), 0);
        page.addView(note, full(dp(88)));
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
        launchUnity("queue");
    }

    private int countQueuedReports() {
        File appFiles = getExternalFilesDir(null);
        if (appFiles == null) appFiles = getFilesDir();
        File root = new File(appFiles, "GraffitiReports");
        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null) return 0;
        int count = 0;
        for (File folder : folders) {
            if (new File(folder, "pending_get_it_done.json").isFile()) count++;
        }
        return count;
    }

    private void openCaptureFlow(String source) {
        getSharedPreferences("GraffitiHunterNavigation", MODE_PRIVATE).edit()
            .remove("launch_action").commit();
        getSharedPreferences("GraffitiHunterNativeCamera", MODE_PRIVATE).edit()
            .remove("direct_review_handoff")
            .remove("capture_path").remove("capture_rotation")
            .remove("capture_lat_bits").remove("capture_lon_bits")
            .remove("capture_accuracy").remove("capture_location_time")
            .putString("capture_source", source).commit();
        launchUnity("capture");
    }

    private void launchUnity(String action) {
        // A persisted one-shot action works on both cold and warm Unity starts and
        // eliminates the timing retries that could reveal the retired Unity menu.
        getSharedPreferences("GraffitiHunterNavigation", MODE_PRIVATE).edit()
            .putString("launch_action", action).commit();
        Intent unity = new Intent(this, HunterUnityActivity.class);
        unity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(unity);
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

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(17);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(13, 132, 181));
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
