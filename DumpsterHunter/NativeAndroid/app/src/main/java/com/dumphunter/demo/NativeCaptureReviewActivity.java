package com.dumphunter.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Native approval surface for photo, GPS and marked-map review. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
public class NativeCaptureReviewActivity extends Activity {
    private File rawPhoto;
    private File reportFolder;
    private File reportPhoto;
    private File reportMap;
    private Bitmap photoBitmap;
    private ImageView mapView;
    private TextView status;
    private Button approve;
    private double latitude;
    private double longitude;
    private float accuracy;
    private boolean hasLocation;
    private boolean approved;
    private volatile boolean abandoned;
    private String captureId;
    private String capturedUtc;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(5, 12, 18));
        if (!loadCapture()) {
            Toast.makeText(this, "The camera image was not available.", Toast.LENGTH_LONG).show();
            returnHome();
            return;
        }
        buildUi();
        preparePackage();
    }

    private boolean loadCapture() {
        SharedPreferences p = getSharedPreferences("DumpHunterNativeCamera", MODE_PRIVATE);
        String path = p.getString("capture_path", "");
        int rotation = p.getInt("capture_rotation", 0);
        long locationTime = p.getLong("capture_location_time", 0L);
        latitude = Double.longBitsToDouble(p.getLong("capture_lat_bits", 0L));
        longitude = Double.longBitsToDouble(p.getLong("capture_lon_bits", 0L));
        accuracy = p.getFloat("capture_accuracy", 0f);
        hasLocation = locationTime > 0L && latitude >= -90 && latitude <= 90 &&
            longitude >= -180 && longitude <= 180;
        rawPhoto = path.isEmpty() ? null : new File(path);
        if (rawPhoto == null || !rawPhoto.isFile()) return false;
        photoBitmap = BitmapFactory.decodeFile(rawPhoto.getAbsolutePath());
        if (photoBitmap == null) return false;
        rotation = ((rotation % 360) + 360) % 360;
        if (((rotation == 90 || rotation == 270) && photoBitmap.getWidth() > photoBitmap.getHeight()) ||
            rotation == 180) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(photoBitmap, 0, 0,
                photoBitmap.getWidth(), photoBitmap.getHeight(), matrix, true);
            if (rotated != photoBitmap) photoBitmap.recycle();
            photoBitmap = rotated;
        }
        // BitmapFactory may return an immutable bitmap when a landscape JPEG
        // needs no rotation. The evidence overlay always requires a writable
        // bitmap, just as the rotated portrait path already produces.
        if (!photoBitmap.isMutable()) {
            Bitmap writable = photoBitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (writable == null) return false;
            photoBitmap.recycle();
            photoBitmap = writable;
        }
        return true;
    }

    private void buildUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(6), dp(18), dp(8));
        page.setBackgroundColor(Color.rgb(7, 16, 24));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(18), dp(6) + insets.getSystemWindowInsetTop(),
                dp(18), dp(8) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = text("REVIEW CAPTURE", 24, true);
        page.addView(title, full(dp(44)));
        ImageView photo = new ImageView(this);
        photo.setImageBitmap(photoBitmap);
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photo.setContentDescription("Captured photograph - tap to enlarge");
        photo.setOnClickListener(v -> showLarge(photo));
        page.addView(photo, flexible(3f));

        mapView = new ImageView(this);
        mapView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mapView.setBackgroundColor(Color.rgb(31, 43, 51));
        mapView.setContentDescription("Marked location map - tap to enlarge");
        mapView.setOnClickListener(v -> showLarge(mapView));
        page.addView(mapView, flexible(2f));

        String coordinates = hasLocation
            ? String.format(Locale.US, "GPS %.7f, %.7f   ±%.0fm", latitude, longitude, accuracy)
            : "GPS unavailable — verify location before submission";
        page.addView(text(coordinates, 15, false), full(dp(48)));
        status = text(hasLocation ? "Preparing marked map…" : "Review the photograph", 15, false);
        page.addView(status, full(dp(42)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button cancel = button("CANCEL");
        cancel.setOnClickListener(v -> rejectToHome());
        Button reset = button("RESET");
        reset.setOnClickListener(v -> retake());
        approve = button("APPROVE");
        approve.setEnabled(!hasLocation);
        approve.setOnClickListener(v -> approveAndContinue());
        actions.addView(cancel, weighted());
        actions.addView(reset, weighted());
        actions.addView(approve, weighted());
        page.addView(actions, full(dp(78)));

        setContentView(page);
        page.requestApplyInsets();
    }

    private void showLarge(ImageView source) {
        if (source.getDrawable() == null) return;
        ImageView large = new ImageView(this);
        large.setImageDrawable(source.getDrawable());
        large.setAdjustViewBounds(true);
        large.setScaleType(ImageView.ScaleType.FIT_CENTER);
        large.setBackgroundColor(Color.BLACK);
        new AlertDialog.Builder(this).setView(large).setPositiveButton("CLOSE", null).show();
    }

    private void preparePackage() {
        new Thread(() -> {
            try {
                String id = utc("yyyyMMdd_HHmmss");
                captureId = id;
                capturedUtc = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
                File base = getExternalFilesDir(null);
                if (base == null) base = getFilesDir();
                reportFolder = new File(new File(base, "DumpReports"), id);
                reportFolder.mkdirs();
                if (abandoned) { discardPackage(); return; }
                reportPhoto = new File(reportFolder, "dump_" + id + ".jpg");
                drawEvidenceOverlay(photoBitmap);
                try (FileOutputStream out = new FileOutputStream(reportPhoto)) {
                    photoBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                }
                writeReport(false, id);
                boolean mapReady = false;
                if (hasLocation) {
                    try { downloadMarkedMap(id); mapReady = reportMap != null && reportMap.isFile(); }
                    catch (Exception ignored) { }
                }
                final boolean finalMapReady = mapReady;
                if (abandoned) { discardPackage(); return; }
                runOnUiThread(() -> {
                    approve.setEnabled(true);
                    status.setText(finalMapReady ? "Photo and map ready — approve or reset" :
                        (hasLocation ? "Photo ready — map preview unavailable" :
                        "Photo ready — GPS was unavailable"));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    status.setText("Could not prepare this capture");
                    approve.setEnabled(false);
                });
            }
        }, "DumpingReviewPackage").start();
    }

    private void drawEvidenceOverlay(Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        float scale = Math.max(1f, bitmap.getWidth() / 1080f);
        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
        red.setColor(Color.RED); red.setStyle(Paint.Style.STROKE); red.setStrokeWidth(6f * scale);
        float left = bitmap.getWidth() * .19f, top = bitmap.getHeight() * .29f;
        float right = bitmap.getWidth() * .81f, bottom = bitmap.getHeight() * .71f;
        canvas.drawRect(left, top, right, bottom, red);
        Paint shade = new Paint(); shade.setColor(0xaa000000);
        canvas.drawRect(0, bitmap.getHeight() - 52f * scale, bitmap.getWidth(), bitmap.getHeight(), shade);
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG); white.setColor(Color.WHITE);
        white.setTextSize(27f * scale); white.setFakeBoldText(true);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = hasLocation ? String.format(Locale.US, "%s   %.7f, %.7f", stamp, latitude, longitude) : stamp;
        canvas.drawText(line, 18f * scale, bitmap.getHeight() - 16f * scale, white);
    }

    private void downloadMarkedMap(String id) throws Exception {
        // Build a map centered on the exact GPS pixel, not the containing tile's center.
        int zoom = 16, n = 1 << zoom;
        double worldX = (longitude + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(latitude);
        double worldY = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
        // Match the neighborhood scale used by the common maps from the earlier app.
        final int sourceViewSize = 512;
        double leftPixel = worldX * 256.0 - sourceViewSize / 2.0;
        double topPixel = worldY * 256.0 - sourceViewSize / 2.0;
        int leftTile = (int)Math.floor(leftPixel / 256.0);
        int topTile = (int)Math.floor(topPixel / 256.0);
        int cropX = (int)Math.round(leftPixel - leftTile * 256.0);
        int cropY = (int)Math.round(topPixel - topTile * 256.0);
        cropX = Math.max(0, Math.min(256, cropX));
        cropY = Math.max(0, Math.min(256, cropY));

        Bitmap mosaic = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
        Canvas mosaicCanvas = new Canvas(mosaic);
        int downloadedTiles = 0;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                Bitmap tile = null;
                for (int attempt = 0; attempt < 2 && tile == null; attempt++) {
                    try { tile = downloadTile(zoom, leftTile + column, topTile + row, n); }
                    catch (Exception ignored) { }
                }
                if (tile != null) {
                    mosaicCanvas.drawBitmap(tile, column * 256f, row * 256f, null);
                    downloadedTiles++;
                    tile.recycle();
                }
            }
        }
        if (downloadedTiles == 0) { mosaic.recycle(); throw new java.io.IOException("No map tiles available"); }
        Bitmap crop = Bitmap.createBitmap(mosaic, cropX, cropY, sourceViewSize, sourceViewSize);
        Bitmap marked = Bitmap.createScaledBitmap(crop, 512, 512, true).copy(Bitmap.Config.ARGB_8888, true);
        crop.recycle();
        mosaic.recycle();
        Canvas canvas = new Canvas(marked);
        Paint red = new Paint(Paint.ANTI_ALIAS_FLAG); red.setColor(Color.RED); red.setStyle(Paint.Style.FILL);
        float cx = marked.getWidth() / 2f, cy = marked.getHeight() / 2f;
        Path arrow = new Path(); arrow.moveTo(cx, cy - 10); arrow.lineTo(cx - 40, cy - 58);
        arrow.lineTo(cx - 13, cy - 58); arrow.lineTo(cx - 13, cy - 126);
        arrow.lineTo(cx + 13, cy - 126); arrow.lineTo(cx + 13, cy - 58);
        arrow.lineTo(cx + 40, cy - 58); arrow.close(); canvas.drawPath(arrow, red);
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG); outline.setColor(Color.RED);
        outline.setStyle(Paint.Style.STROKE); outline.setStrokeWidth(3f);
        canvas.drawRect(cx - 9, cy - 9, cx + 9, cy + 9, outline);
        reportMap = new File(reportFolder, "map.png");
        try (FileOutputStream out = new FileOutputStream(reportMap)) {
            marked.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        runOnUiThread(() -> mapView.setImageBitmap(marked));
    }

    private Bitmap downloadTile(int zoom, int x, int y, int n) throws Exception {
        int wrappedX = ((x % n) + n) % n;
        int clampedY = Math.max(0, Math.min(n - 1, y));
        HttpURLConnection connection = (HttpURLConnection)new URL(
            "https://tile.openstreetmap.org/" + zoom + "/" + wrappedX + "/" + clampedY + ".png").openConnection();
        connection.setRequestProperty("User-Agent", "DumpsterHunter/0.3.0 (graffitihunter.net)");
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream()) {
            return BitmapFactory.decodeStream(in);
        } finally {
            connection.disconnect();
        }
    }

    private void writeReport(boolean finalApproval, String id) throws Exception {
        String captureTime = capturedUtc == null ? utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") : capturedUtc;
        String place = "Possible address not yet available";
        String description = String.format(Locale.US,
            "Illegal dumping (Bulky Items). Public right-of-way: Yes. Private property: Not Sure. %s. " +
            "GPS coordinates at capture: %.7f, %.7f. " +
            "Photo and marked location map are attached. " +
            "Submitted by Dumpster Hunter Build 0.3.0 (home made app), the Graffiti Hunter wingman. GraffitiHunter.net.",
            place, latitude, longitude);
        JSONObject report = new JSONObject();
        report.put("id", id); report.put("capturedUtc", captureTime);
        report.put("cameraLatitude", latitude); report.put("cameraLongitude", longitude);
        report.put("horizontalAccuracyMeters", accuracy); report.put("bearingDegrees", 0);
        report.put("estimatedDistanceMeters", 0); report.put("estimatedTargetLatitude", latitude);
        report.put("estimatedTargetLongitude", longitude); report.put("photoFile", reportPhoto.getName());
        report.put("mapUrl", String.format(Locale.US,
            "https://www.openstreetmap.org/?mlat=%.7f&mlon=%.7f#map=16/%.7f/%.7f",
            latitude, longitude, latitude, longitude));
        report.put("approvalStatus", finalApproval ? "pending_get_it_done" : "awaiting_approval");
        report.put("approvedUtc", finalApproval ? utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'") : "");
        report.put("getItDoneCategory", "Illegal Dumping"); report.put("locationType", "Bulky Items");
        report.put("dumpingIssue", "Bulky Items"); report.put("publicRightOfWay", "Yes");
        report.put("privateProperty", "Not Sure"); report.put("digitalZoom", 1.0);
        report.put("getItDoneFormUrl", "https://getitdone.sandiego.gov/TSWNewReport?type=Illegal%20Dumping");
        report.put("suggestedDescription", description);
        report.put("exactLocationDescription", String.format(Locale.US,
            "%s. Map pin: %.7f, %.7f. Look for the location marked by the red arrow.",
            place, latitude, longitude));
        report.put("streetAddress", "");
        report.put("note", hasLocation ?
            "Map location is the phone's GPS position at capture time; compass direction is not used." :
            "GPS was unavailable; coordinates are placeholders and must not be submitted.");
        byte[] bytes = report.toString(2).getBytes("UTF-8");
        writeBytes(new File(reportFolder, "report.json"), bytes);
        if (finalApproval) writeBytes(new File(reportFolder, "pending_get_it_done.json"), bytes);
    }

    private void approveAndContinue() {
        if (approved || reportFolder == null) return;
        approved = true;
        try {
            writeReport(true, reportFolder.getName());
            writeBytes(new File(reportFolder, "APPROVED.txt"), ("Approved " + utc("yyyy-MM-dd'T'HH:mm:ss'Z'")).getBytes());
            saveGallery(reportPhoto, "image/jpeg");
            if (reportMap != null && reportMap.isFile()) saveGallery(reportMap, "image/png");
            clearCapturePrefs(true);
            reopenCamera();
        } catch (Exception error) {
            approved = false;
            status.setText("Approval could not be saved");
        }
    }

    private void retake() { abandoned = true; discardPackage(); clearCapturePrefs(true); reopenCamera(); }
    private void rejectToHome() { abandoned = true; discardPackage(); clearCapturePrefs(true); returnHome(); }

    private void reopenCamera() {
        Intent camera = new Intent(this, NativeCameraCaptureActivity.class);
        camera.putExtra("launched_from_home", true);
        startActivity(camera);
        finish();
    }

    private void returnHome() {
        Intent home = new Intent(this, HunterHomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home); finish();
    }

    private void discardPackage() {
        if (reportFolder == null || !reportFolder.isDirectory()) return;
        File[] children = reportFolder.listFiles();
        if (children != null) for (File child : children) child.delete();
        reportFolder.delete();
    }

    private void clearCapturePrefs(boolean deleteRaw) {
        if (deleteRaw && rawPhoto != null) rawPhoto.delete();
        getSharedPreferences("DumpHunterNativeCamera", MODE_PRIVATE).edit()
            .remove("capture_path").remove("capture_rotation")
            .remove("capture_lat_bits").remove("capture_lon_bits")
            .remove("capture_accuracy").remove("capture_location_time").apply();
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { rejectToHome(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) &&
            event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            approveAndContinue(); return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void saveGallery(File source, String mime) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, source.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/DumpHunter");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return;
            try (InputStream in = new java.io.FileInputStream(source);
                 java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                byte[] buffer = new byte[16384]; int read;
                while (out != null && (read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
        } catch (Exception ignored) { }
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }

    private static String utc(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date());
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextColor(Color.WHITE);
        view.setTextSize(size); view.setGravity(Gravity.CENTER);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE);
        b.setTextSize(15); b.setAllCaps(false);
        boolean approveButton = "APPROVE".equals(value);
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            approveButton ? new int[] { 0xff087a54, 0xff16ad77 }
                : "RESET".equals(value) ? new int[] { 0xff9b6200, 0xffd99312 }
                : new int[] { 0xff303945, 0xff4c5968 });
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), approveButton ? 0xff72e4b8 : 0xff83d7e8);
        b.setBackground(background); b.setElevation(dp(3));
        return b;
    }

    private LinearLayout.LayoutParams full(int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height);
        p.setMargins(0, dp(5), 0, dp(5)); return p;
    }
    private LinearLayout.LayoutParams flexible(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, weight);
        p.setMargins(0, dp(3), 0, dp(3)); return p;
    }
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(68), 1f);
        p.setMargins(dp(3), dp(5), dp(3), dp(5)); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
