package com.graffitihunter.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Native package editor and submission handoff. */
public class NativeQueueActivity extends Activity {
    private static final String[] TYPES = {
        "[SELECT GRAFFITI LOCATION]",
        "Private Residence", "Commercial Property", "USPS Mail Box", "Bus Shelter",
        "Street or Sidewalk", "City Sign", "Utility Box", "Street Lights",
        "Park", "Utility Pole", "Dumpster", "Other"
    };
    private final List<File> folders = new ArrayList<>();
    private int index;
    private JSONObject report;
    private Spinner category;
    private Button offensive;
    private TextView count;
    private TextView details;
    private TextView description;
    private ImageView photo;
    private ImageView map;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(5, 12, 18));
        loadFolders();
        if (folders.isEmpty()) {
            Toast.makeText(this, "No approved captures are queued.", Toast.LENGTH_LONG).show();
            returnHome(); return;
        }
        buildUi(); loadCurrent();
    }

    private void loadFolders() {
        File base = getExternalFilesDir(null); if (base == null) base = getFilesDir();
        File root = new File(base, "GraffitiReports");
        File[] items = root.listFiles(File::isDirectory);
        if (items == null) return;
        Arrays.sort(items, (a, b) -> a.getName().compareTo(b.getName()));
        for (File item : items)
            if (new File(item, "pending_get_it_done.json").isFile()) folders.add(item);
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(8), dp(16), dp(10)); page.setBackgroundColor(Color.rgb(7, 16, 24));
        page.addView(text("REVIEW QUEUE", 22, true), full(dp(38)));
        count = text("", 14, false); page.addView(count, full(dp(26)));

        LinearLayout media = new LinearLayout(this); media.setGravity(Gravity.CENTER);
        photo = image(); map = image();
        photo.setOnClickListener(v -> showLarge(photo)); map.setOnClickListener(v -> showLarge(map));
        media.addView(photo, weighted(dp(158))); media.addView(map, weighted(dp(158)));
        page.addView(media, full(dp(168)));

        details = text("", 13, false); page.addView(details, full(dp(56)));
        page.addView(text("WHERE IS THE GRAFFITI?", 15, true), full(dp(30)));
        category = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, TYPES) {
            private View style(View view) {
                TextView text = (TextView)view;
                text.setTextColor(Color.rgb(20, 25, 30));
                text.setBackgroundColor(Color.WHITE);
                text.setTextSize(16);
                text.setPadding(dp(16), 0, dp(16), 0);
                return text;
            }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return style(super.getView(position, convertView, parent));
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return style(super.getDropDownView(position, convertView, parent));
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(adapter);
        category.setBackgroundColor(Color.WHITE);
        category.setPopupBackgroundDrawable(new ColorDrawable(Color.WHITE));
        page.addView(category, full(dp(50)));

        LinearLayout edit = new LinearLayout(this);
        offensive = button("OFFENSIVE: NO"); offensive.setOnClickListener(v -> toggleOffensive());
        Button update = button("UPDATE PACKAGE"); update.setOnClickListener(v -> saveCurrent());
        edit.addView(offensive, weighted(dp(52))); edit.addView(update, weighted(dp(52)));
        page.addView(edit, full(dp(58)));

        description = text("", 13, false); description.setGravity(Gravity.START);
        page.addView(description, full(dp(76)));
        Button prepare = button("PREPARE GET IT DONE"); prepare.setOnClickListener(v -> prepareOfficialForm());
        page.addView(prepare, full(dp(58)));

        LinearLayout manage = new LinearLayout(this);
        Button processed = button("MARK PROCESSED"); processed.setOnClickListener(v -> markProcessed());
        Button delete = button("DELETE / REJECT"); delete.setOnClickListener(v -> confirmDelete());
        manage.addView(processed, weighted(dp(52))); manage.addView(delete, weighted(dp(52)));
        page.addView(manage, full(dp(58)));

        LinearLayout nav = new LinearLayout(this);
        Button previous = button("< PREV"); previous.setOnClickListener(v -> move(-1));
        Button home = button("HOME"); home.setOnClickListener(v -> returnHome());
        Button next = button("NEXT >"); next.setOnClickListener(v -> move(1));
        nav.addView(previous, weighted(dp(52))); nav.addView(home, weighted(dp(52))); nav.addView(next, weighted(dp(52)));
        page.addView(nav, full(dp(58)));

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        setContentView(scroll);
    }

    private void loadCurrent() {
        try {
            File folder = folders.get(index);
            report = new JSONObject(read(new File(folder, "pending_get_it_done.json")));
            count.setText((index + 1) + " of " + folders.size() + "   " + report.optString("id", folder.getName()));
            double lat = report.optDouble("estimatedTargetLatitude", report.optDouble("cameraLatitude", 0));
            double lon = report.optDouble("estimatedTargetLongitude", report.optDouble("cameraLongitude", 0));
            details.setText(String.format(Locale.US, "LAT %.7f   LON %.7f\nCaptured %s\nGPS accuracy ±%.0fm",
                lat, lon, report.optString("capturedUtc", ""), report.optDouble("horizontalAccuracyMeters", 0)));
            int selected = Arrays.asList(TYPES).indexOf(report.optString("locationType", ""));
            category.setSelection(selected < 0 ? 0 : selected);
            offensive.setText("OFFENSIVE: " + report.optString("offensive", "No").toUpperCase(Locale.US));
            description.setText(report.optString("suggestedDescription", ""));
            File photoFile = new File(folder, report.optString("photoFile", ""));
            photo.setImageBitmap(BitmapFactory.decodeFile(photoFile.getAbsolutePath()));
            File mapFile = new File(folder, "map.png");
            map.setImageBitmap(mapFile.isFile() ? BitmapFactory.decodeFile(mapFile.getAbsolutePath()) : null);
        } catch (Exception error) {
            Toast.makeText(this, "Could not read this package.", Toast.LENGTH_LONG).show();
        }
    }

    private void toggleOffensive() {
        if (report == null) return;
        try {
            String next = "Yes".equalsIgnoreCase(report.optString("offensive")) ? "No" : "Yes";
            report.put("offensive", next); offensive.setText("OFFENSIVE: " + next.toUpperCase(Locale.US));
        } catch (Exception ignored) { }
    }

    private boolean saveCurrent() {
        if (report == null) return false;
        try {
            String type = String.valueOf(category.getSelectedItem());
            if (type.startsWith("[SELECT")) {
                Toast.makeText(this, "Select where the graffiti is located.", Toast.LENGTH_LONG).show();
                return false;
            }
            report.put("locationType", type);
            double lat = report.optDouble("estimatedTargetLatitude", report.optDouble("cameraLatitude", 0));
            double lon = report.optDouble("estimatedTargetLongitude", report.optDouble("cameraLongitude", 0));
            String address = report.optString("streetAddress", "");
            String place = address.trim().isEmpty() ? "Possible address not yet available" : "Possible address: " + address;
            String desc = String.format(Locale.US,
                "Graffiti on/at a %s. %s. GPS coordinates at capture: %.7f, %.7f. " +
                "Photo and marked location map are attached. " +
                "Submitted by Graffiti Hunter Build 0.5.7 GraffitiHunter.net.",
                type, place, lat, lon);
            report.put("suggestedDescription", desc);
            report.put("exactLocationDescription", String.format(Locale.US,
                "%s. Map pin: %.7f, %.7f. Look for the location marked by the red arrow.", place, lat, lon));
            byte[] json = report.toString(2).getBytes(StandardCharsets.UTF_8);
            write(new File(folders.get(index), "report.json"), json);
            write(new File(folders.get(index), "pending_get_it_done.json"), json);
            description.setText(desc);
            Toast.makeText(this, "Submission package updated.", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception error) {
            Toast.makeText(this, "Package update failed.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void prepareOfficialForm() {
        if (!saveCurrent() || report == null) return;
        if (report.optString("streetAddress", "").trim().isEmpty()) {
            Toast.makeText(this, "Resolving the nearby street address...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String resolved = resolveStreetAddress();
                runOnUiThread(() -> {
                    try { if (!resolved.isEmpty()) report.put("streetAddress", resolved); }
                    catch (Exception ignored) { }
                    saveCurrent();
                    launchOfficialForm();
                });
            }, "GraffitiAddressLookup").start();
            return;
        }
        launchOfficialForm();
    }

    private void launchOfficialForm() {
        File folder = folders.get(index);
        Intent form = new Intent(this, GraffitiSubmissionActivity.class);
        form.putExtra("latitude", decimal("estimatedTargetLatitude"));
        form.putExtra("longitude", decimal("estimatedTargetLongitude"));
        form.putExtra("streetAddress", report.optString("streetAddress", ""));
        form.putExtra("locationType", report.optString("locationType", "Other"));
        form.putExtra("offensive", report.optString("offensive", "No"));
        form.putExtra("description", report.optString("suggestedDescription", ""));
        form.putExtra("locationDescription", report.optString("exactLocationDescription", ""));
        form.putExtra("photoPath", new File(folder, report.optString("photoFile", "")).getAbsolutePath());
        form.putExtra("mapPath", new File(folder, "map.png").getAbsolutePath());
        startActivity(form);
    }

    private String resolveStreetAddress() {
        try {
            double lat = report.optDouble("estimatedTargetLatitude", report.optDouble("cameraLatitude", 0));
            double lon = report.optDouble("estimatedTargetLongitude", report.optDouble("cameraLongitude", 0));
            if (!Geocoder.isPresent()) return "";
            List<Address> found = new Geocoder(this, Locale.US).getFromLocation(lat, lon, 1);
            if (found == null || found.isEmpty()) return "";
            String line = found.get(0).getAddressLine(0);
            return line == null ? "" : line.trim();
        } catch (Exception ignored) { return ""; }
    }

    private String decimal(String key) { return String.format(Locale.US, "%.7f", report.optDouble(key, 0)); }

    private void markProcessed() {
        if (!saveCurrent()) return;
        File folder = folders.get(index); new File(folder, "pending_get_it_done.json").delete();
        try { write(new File(folder, "PROCESSED.txt"), ("Marked processed " + utc()).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { }
        removeCurrent();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("Delete this package?")
            .setMessage("This permanently removes its queued photo, map and metadata package.")
            .setNegativeButton("KEEP", null)
            .setPositiveButton("DELETE", (dialog, which) -> { deleteTree(folders.get(index)); removeCurrent(); })
            .show();
    }

    private void removeCurrent() {
        folders.remove(index);
        if (folders.isEmpty()) { Toast.makeText(this, "Queue is empty.", Toast.LENGTH_SHORT).show(); returnHome(); return; }
        if (index >= folders.size()) index = folders.size() - 1; loadCurrent();
    }

    private void move(int amount) {
        if (folders.isEmpty()) return;
        index = (index + amount + folders.size()) % folders.size(); loadCurrent();
    }

    private void showLarge(ImageView source) {
        if (source.getDrawable() == null) return;
        ImageView large = new ImageView(this); large.setImageDrawable(source.getDrawable());
        large.setAdjustViewBounds(true); large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        new AlertDialog.Builder(this).setView(large).setPositiveButton("CLOSE", null).show();
    }

    private void returnHome() {
        Intent home = new Intent(this, HunterHomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home); finish();
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { returnHome(); }

    private static String read(File file) throws Exception {
        byte[] data = new byte[(int)file.length()];
        try (FileInputStream in = new FileInputStream(file)) { int offset = 0, n; while (offset < data.length && (n = in.read(data, offset, data.length - offset)) > 0) offset += n; }
        return new String(data, StandardCharsets.UTF_8);
    }
    private static void write(File file, byte[] bytes) throws Exception { try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); } }
    private static void deleteTree(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); file.delete(); }
    private static String utc() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date()); }

    private ImageView image() { ImageView v = new ImageView(this); v.setScaleType(ImageView.ScaleType.CENTER_CROP); v.setBackgroundColor(Color.rgb(31, 43, 51)); return v; }
    private TextView text(String value, int size, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextColor(Color.WHITE); v.setTextSize(size); v.setGravity(Gravity.CENTER); if (bold) v.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); return v; }
    private Button button(String value) {
        Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE);
        b.setTextSize(14); b.setAllCaps(false);
        boolean prepare = value.startsWith("PREPARE");
        boolean danger = value.startsWith("DELETE");
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            prepare ? new int[] { 0xff087a54, 0xff16ad77 }
                : danger ? new int[] { 0xffa20d1c, 0xffdf2333 }
                : new int[] { 0xff253746, 0xff40566a });
        background.setCornerRadius(dp(11));
        background.setStroke(dp(1), prepare ? 0xff72e4b8 : 0xff69cce2);
        b.setBackground(background); b.setElevation(dp(3)); return b;
    }
    private LinearLayout.LayoutParams full(int height) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height); p.setMargins(0, dp(2), 0, dp(2)); return p; }
    private LinearLayout.LayoutParams weighted(int height) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, height, 1f); p.setMargins(dp(2), dp(2), dp(2), dp(2)); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
