package com.graffitihunter.demo;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Keeps the native home alive when the Unity-only review surface exits. */
public class HunterUnityActivity extends com.unity3d.player.UnityPlayerGameActivity {
    private boolean homeRecoveryStarted;

    @Override protected void onCreate(Bundle state) {
        String action = getSharedPreferences("GraffitiHunterNavigation", MODE_PRIVATE)
            .getString("launch_action", "");
        super.onCreate(state);
        if ("queue".equals(action)) showQueueLoadingCover();
    }

    private void showQueueLoadingCover() {
        FrameLayout cover = new FrameLayout(this);
        cover.setBackgroundColor(Color.rgb(12, 28, 42));
        cover.setClickable(true);
        TextView message = new TextView(this);
        message.setText("GRAFFITI HUNTER\n\nLoading review queue...");
        message.setTextColor(Color.WHITE);
        message.setTextSize(25);
        message.setGravity(Gravity.CENTER);
        cover.addView(message, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addContentView(cover, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        Handler handler = new Handler(Looper.getMainLooper());
        // The native home and Unity review UI run in separate processes.  Send
        // the command again after Unity has created its scene so the queue opens
        // directly instead of briefly exposing the obsolete capture menu.
        handler.postDelayed(this::openUnityQueue, 900L);
        handler.postDelayed(this::openUnityQueue, 1700L);
        handler.postDelayed(this::openUnityQueue, 2500L);
        handler.postDelayed(() -> {
            cover.setVisibility(View.GONE);
            if (cover.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) cover.getParent()).removeView(cover);
        }, 3400L);
    }

    private void openUnityQueue() {
        com.unity3d.player.UnityPlayer.UnitySendMessage(
            "Graffiti Hunter", "SubmitQueuedReports", "");
    }

    @Override public void finish() {
        if (!homeRecoveryStarted) {
            homeRecoveryStarted = true;
            Intent home = new Intent(getApplicationContext(), HunterHomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            getApplicationContext().startActivity(home);
        }
        // This activity runs in the disposable :capture_review process, so Unity
        // can shut down cleanly without terminating the native home process.
        super.finish();
    }
}
