package com.graffitihunter.demo;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.Image;
import android.media.ImageReader;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

/**
 * Seamless in-app Camera2 capture for the S25 Ultra. Uses camera ID 0's logical
 * multi-camera device and CONTROL_ZOOM_RATIO, so Samsung's camera HAL chooses
 * the appropriate physical lens. No texture crop is used for the saved
 * JPEG.
 */
@androidx.media3.common.util.UnstableApi
public class NativeCameraCaptureActivity extends Activity {
    private TextureView preview;
    private TextView status;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewRequest;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraCharacteristics characteristics;
    private String cameraId = "0";
    private Size previewSize;
    private Size jpegSize;
    private float zoomRatio = 1f;
    private LocationManager locationManager;
    private Location latestLocation;
    private final LocationListener locationListener = this::acceptLocation;
    private float minZoom = 1f;
    private float maxZoom = 1f;
    private boolean manualInfinity;
    private boolean captureInProgress;
    private boolean glassesMode;
    private androidx.media3.exoplayer.ExoPlayer glassesPlayer;
    private tv.danmaku.ijk.media.player.IjkMediaPlayer glassesIjkPlayer;
    private Surface glassesStreamSurface;
    private final Handler glassesRetryHandler = new Handler(Looper.getMainLooper());
    private final Handler glassesHeartbeatHandler = new Handler(Looper.getMainLooper());
    private boolean glassesActivityActive;
    private boolean glassesInitializing;
    private int glassesReconnectAttempts;
    private int glassesConnectionGeneration;
    private android.net.Network glassesWifiNetwork;
    private boolean captureResultSent;
    private boolean launchedFromHome;
    private static android.net.ConnectivityManager.NetworkCallback cellularHoldCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        glassesMode = "glasses".equals(getSharedPreferences(
            "GraffitiHunterNativeCamera", MODE_PRIVATE)
            .getString("capture_source", "phone"));
        launchedFromHome = getIntent().getBooleanExtra("launched_from_home", false);
        buildUi();
        requestNeededPermissions();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ||
            event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                captureJpeg();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        View aimingBox = new View(this) {
            private final Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                red.setColor(Color.RED);
                red.setStyle(Paint.Style.STROKE);
                red.setStrokeWidth(7f * getResources().getDisplayMetrics().density);
            }
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float left = getWidth() * 0.19f;
                float top = getHeight() * 0.29f;
                float right = getWidth() * 0.81f;
                float bottom = getHeight() * 0.71f;
                canvas.drawRect(left, top, right, bottom, red);
            }
        };
        aimingBox.setContentDescription("Red capture target");
        root.addView(aimingBox, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER);
        top.setPadding(10, 18, 10, 10);
        top.setBackgroundColor(0x99000000);
        String[] labels = glassesMode ? new String[] { "OHO GLASSES LIVE" }
            : new String[] { "0.6×", "1×", "2×", "5×", "10×", "AF", "FAR" };
        for (String label : labels) {
            Button button = cameraButton(label);
            if (glassesMode) button.setEnabled(false);
            else if ("AF".equals(label)) button.setOnClickListener(v -> enableContinuousFocus());
            else if ("FAR".equals(label)) button.setOnClickListener(v -> focusInfinity());
            else {
                final float value = Float.parseFloat(label.replace("×", ""));
                button.setOnClickListener(v -> setHardwareZoom(value));
            }
            top.addView(button);
        }
        LinearLayout upperControls = new LinearLayout(this);
        upperControls.setOrientation(LinearLayout.VERTICAL);
        upperControls.setBackgroundColor(0x99000000);
        upperControls.addView(top, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout captureRow = new LinearLayout(this);
        captureRow.setGravity(Gravity.CENTER);
        captureRow.setPadding(16, 2, 16, 12);
        Button cancel = cameraButton("CANCEL");
        cancel.setTextSize(22);
        cancel.setOnClickListener(v -> returnToNewHome());
        Button capture = cameraButton("CAPTURE");
        capture.setTextSize(28);
        capture.setOnClickListener(v -> captureJpeg());
        captureRow.addView(cancel, new LinearLayout.LayoutParams(0, 164, 1f));
        captureRow.addView(capture, new LinearLayout.LayoutParams(0, 164, 2f));
        upperControls.addView(captureRow, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(upperControls, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP));

        status = new TextView(this);
        status.setText(glassesMode ? "Connecting to OHO glasses..."
            : "REAL CAMERA • tap subject to focus");
        status.setTextColor(Color.WHITE);
        status.setTextSize(17);
        status.setGravity(Gravity.CENTER);
        status.setPadding(12, 12, 12, 12);
        status.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM);
        statusParams.bottomMargin = 112;
        root.addView(status, statusParams);

        setContentView(root);

        preview.setSurfaceTextureListener(surfaceListener);
        preview.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                if (!glassesMode) focusAt(event.getX() / Math.max(1f, preview.getWidth()),
                    event.getY() / Math.max(1f, preview.getHeight()));
            }
            return true;
        });
    }

    private Button cameraButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        boolean primary = "CAPTURE".equals(text);
        boolean cancel = "CANCEL".equals(text);
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            primary ? new int[] { 0xffd90f23, 0xffff3e34 }
                : cancel ? new int[] { 0xff303945, 0xff4c5968 }
                : new int[] { 0xff162633, 0xff233c4d });
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), primary ? Color.WHITE : 0xff50cfe8);
        b.setBackground(background);
        b.setElevation(dp(3));
        b.setPadding(5, 0, 5, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 76, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(params);
        return b;
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        returnToNewHome();
    }

    private void returnToNewHome() {
        // CLEAR_TOP guarantees that Cancel returns to the single native home.
        getSharedPreferences("GraffitiHunterNativeCamera", MODE_PRIVATE).edit()
            .remove("capture_path").remove("capture_rotation")
            .remove("capture_lat_bits").remove("capture_lon_bits")
            .remove("capture_accuracy").remove("capture_location_time").apply();
        Intent home = new Intent(this, HunterHomeActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        glassesActivityActive = true;
        startLocationUpdates();
        if (glassesMode) {
            if (preview.isAvailable()) startGlassesStream();
        } else {
            startCameraThread();
            if (preview.isAvailable()) openCamera();
        }
    }

    @Override protected void onPause() {
        glassesActivityActive = false;
        glassesRetryHandler.removeCallbacksAndMessages(null);
        glassesHeartbeatHandler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
        if (glassesMode) stopGlassesStream();
        else {
            closeCamera();
            stopCameraThread();
        }
        super.onPause();
    }

    private void startLocationUpdates() {
        try {
            locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            latestLocation = null;
            acceptLocation(gps);
            acceptLocation(network);
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500L, 0f, locationListener);
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 500L, 0f, locationListener);
        } catch (SecurityException ignored) { }
    }

    private void acceptLocation(Location candidate) {
        if (candidate == null) return;
        if (latestLocation == null) {
            latestLocation = candidate;
            return;
        }
        long ageDelta = candidate.getTime() - latestLocation.getTime();
        if (ageDelta > 10000L) {
            latestLocation = candidate;
            return;
        }
        if (ageDelta < -10000L) return;
        float accuracyDelta = candidate.getAccuracy() - latestLocation.getAccuracy();
        boolean moreAccurate = accuracyDelta < 0f;
        boolean newerAndComparable = ageDelta > 0L && accuracyDelta <= 25f;
        if (moreAccurate || newerAndComparable) latestLocation = candidate;
    }

    private void requestNeededPermissions() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        if (!glassesMode && checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            needed.add(android.Manifest.permission.CAMERA);
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 501);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 501) return;
        startLocationUpdates();
        if (!glassesMode && checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED && preview.isAvailable()) openCamera();
    }

    private void stopLocationUpdates() {
        try {
            if (locationManager != null) locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) { }
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        configureTransform(preview.getWidth(), preview.getHeight());
    }

    private final TextureView.SurfaceTextureListener surfaceListener =
        new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                if (glassesMode) startGlassesStream(); else openCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
                configureTransform(w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        };

    private void startCameraThread() {
        cameraThread = new HandlerThread("GraffitiHunterCamera2");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) return;
        cameraThread.quitSafely();
        try { cameraThread.join(); } catch (InterruptedException ignored) { }
        cameraThread = null;
        cameraHandler = null;
    }

    private void openCamera() {
        if (camera != null || cameraHandler == null) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.setText("Camera permission is required");
            return;
        }
        try {
            CameraManager manager = (CameraManager)getSystemService(CAMERA_SERVICE);
            cameraId = chooseBackLogicalCamera(manager);
            characteristics = manager.getCameraCharacteristics(cameraId);
            Range<Float> zoomRange = Build.VERSION.SDK_INT >= 30
                ? characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
            if (zoomRange != null) {
                minZoom = zoomRange.getLower();
                maxZoom = zoomRange.getUpper();
            }
            android.hardware.camera2.params.StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class));
            jpegSize = chooseJpegSize(map.getOutputSizes(android.graphics.ImageFormat.JPEG));
            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(),
                android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::saveCapturedImage, cameraHandler);
            configureTransform(preview.getWidth(), preview.getHeight());
            manager.openCamera(cameraId, cameraState, cameraHandler);
        } catch (Exception error) {
            status.setText("Camera2 unavailable: " + error.getMessage());
        }
    }

    private String chooseBackLogicalCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
            if (fallback == null) fallback = id;
            int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null)
                for (int capability : capabilities)
                    if (capability == CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) return id;
        }
        return fallback == null ? "0" : fallback;
    }

    private final CameraDevice.StateCallback cameraState = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice value) {
            camera = value;
            createPreviewSession();
        }
        @Override public void onDisconnected(CameraDevice value) {
            value.close();
            camera = null;
        }
        @Override public void onError(CameraDevice value, int error) {
            value.close();
            camera = null;
            runOnUiThread(() -> status.setText("Camera error " + error));
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(surface);
            previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyZoom(previewRequest);
            camera.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession value) {
                        if (camera == null) return;
                        session = value;
                        updatePreview();
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession value) {
                        runOnUiThread(() -> status.setText("Camera configuration failed"));
                    }
                }, cameraHandler);
        } catch (CameraAccessException error) {
            status.setText("Preview failed");
        }
    }

    private void updatePreview() {
        if (session == null || previewRequest == null) return;
        try {
            session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
            runOnUiThread(() -> status.setText(String.format(
                "REAL %.1f× • tap subject to focus", zoomRatio)));
        } catch (CameraAccessException ignored) { }
    }

    private void setHardwareZoom(float requested) {
        zoomRatio = Math.max(minZoom, Math.min(maxZoom, requested));
        manualInfinity = false;
        previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        applyZoom(previewRequest);
        updatePreview();
    }

    private void applyZoom(CaptureRequest.Builder request) {
        Range<Float> range = characteristics == null || Build.VERSION.SDK_INT < 30 ? null :
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (range != null && Build.VERSION.SDK_INT >= 30)
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio);
    }

    private void focusAt(float nx, float ny) {
        if (session == null || previewRequest == null || characteristics == null) return;
        Rect sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer maxRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        if (sensor == null || maxRegions == null || maxRegions < 1) {
            enableContinuousFocus();
            return;
        }
        int cx = sensor.left + (int)(nx * sensor.width());
        int cy = sensor.top + (int)(ny * sensor.height());
        int half = Math.max(80, Math.min(sensor.width(), sensor.height()) / 16);
        Rect region = new Rect(Math.max(sensor.left, cx - half),
            Math.max(sensor.top, cy - half), Math.min(sensor.right, cx + half),
            Math.min(sensor.bottom, cy + half));
        try {
            manualInfinity = false;
            previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewRequest.set(CaptureRequest.CONTROL_AF_REGIONS,
                new MeteringRectangle[] { new MeteringRectangle(region, 1000) });
            previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(previewRequest.build(), null, cameraHandler);
            previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            updatePreview();
            status.setText("Focusing on selected distance...");
        } catch (CameraAccessException ignored) { }
    }

    private void enableContinuousFocus() {
        if (previewRequest == null) return;
        manualInfinity = false;
        previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
            CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        updatePreview();
    }

    private void focusInfinity() {
        if (previewRequest == null) return;
        manualInfinity = true;
        previewRequest.set(CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_OFF);
        previewRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
        updatePreview();
        status.setText("FAR focus locked at infinity");
    }

    private void captureJpeg() {
        if (glassesMode) {
            captureGlassesFrame();
            return;
        }
        if (camera == null || session == null || captureInProgress) return;
        captureInProgress = true;
        try {
            CaptureRequest.Builder still =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(imageReader.getSurface());
            still.set(CaptureRequest.CONTROL_AF_MODE, manualInfinity
                ? CaptureRequest.CONTROL_AF_MODE_OFF
                : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            if (manualInfinity) still.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
            applyZoom(still);
            still.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            status.setText("Capturing real camera image...");
            session.capture(still.build(), null, cameraHandler);
        } catch (CameraAccessException error) {
            captureInProgress = false;
            status.setText("Capture failed");
        }
    }

    private void startGlassesStream() {
        if (!glassesActivityActive || isFinishing() || glassesPlayer != null ||
            glassesIjkPlayer != null || glassesInitializing ||
            !preview.isAvailable()) return;
        long closedAt = getSharedPreferences("GraffitiHunterNativeCamera", MODE_PRIVATE)
            .getLong("glasses_session_closed_at", 0L);
        long cooldownMs = 1800L - (System.currentTimeMillis() - closedAt);
        if (cooldownMs > 0L) {
            status.setText("Reengaging OHO glasses feed...");
            glassesRetryHandler.removeCallbacksAndMessages(null);
            glassesRetryHandler.postDelayed(this::startGlassesStream, cooldownMs);
            return;
        }
        try {
            android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network wifi = null;
            for (android.net.Network network : connectivity.getAllNetworks()) {
                android.net.NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                    android.net.LinkProperties links = connectivity.getLinkProperties(network);
                    if (links != null) {
                        for (android.net.LinkAddress address : links.getLinkAddresses()) {
                            String host = address.getAddress().getHostAddress();
                            if (host != null && host.startsWith("192.168.1.")) {
                                wifi = network;
                                break;
                            }
                        }
                    }
                    if (wifi != null) break;
                }
            }
            if (wifi == null) {
                status.setText("Connect phone Wi-Fi to the OHO glasses");
                scheduleGlassesReconnect();
                return;
            }
            // Map generation deliberately binds this process to cellular.
            // Put the entire process back on the glasses LAN before opening the
            // next RTSP session; the RTSP stack does not route every socket
            // consistently through its optional SocketFactory on all devices.
            connectivity.bindProcessToNetwork(wifi);
            glassesWifiNetwork = wifi;
            if (initializeGlassesStream(wifi)) return;
            androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory factory =
                new androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .setForceUseRtpTcp(true);
            factory.setSocketFactory(wifi.getSocketFactory());
            androidx.media3.exoplayer.source.MediaSource source = factory.createMediaSource(
                androidx.media3.common.MediaItem.fromUri(
                    "rtsp://192.168.1.254/xxxx.mov"));
            glassesPlayer = new androidx.media3.exoplayer.ExoPlayer.Builder(this).build();
            glassesPlayer.setVideoTextureView(preview);
            glassesPlayer.addListener(new androidx.media3.common.Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == androidx.media3.common.Player.STATE_READY) {
                        glassesReconnectAttempts = 0;
                        status.setText("OHO GLASSES LIVE • press CAPTURE or D18 ▲");
                    } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                        scheduleGlassesReconnect();
                    }
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                    status.setText("Reconnecting OHO glasses feed...");
                    scheduleGlassesReconnect();
                }
            });
            glassesPlayer.setMediaSource(source);
            glassesPlayer.setPlayWhenReady(true);
            glassesPlayer.prepare();
        } catch (Exception error) {
            status.setText("Reconnecting OHO glasses feed...");
            scheduleGlassesReconnect();
        }
    }

    private void scheduleGlassesReconnect() {
        if (!glassesMode || !glassesActivityActive || isFinishing()) return;
        stopGlassesStream();
        long delayMs = Math.min(5000L, 1200L + (glassesReconnectAttempts * 700L));
        glassesReconnectAttempts++;
        glassesRetryHandler.removeCallbacksAndMessages(null);
        glassesRetryHandler.postDelayed(() -> {
            if (glassesActivityActive && !isFinishing()) startGlassesStream();
        }, delayMs);
    }

    private boolean initializeGlassesStream(android.net.Network wifi) {
        glassesInitializing = true;
        final int generation = ++glassesConnectionGeneration;
        status.setText("Initializing OHO glasses...");
        new Thread(() -> {
            boolean initialized = sendGlassesCommand(wifi, 3001, "1") &&
                sendGlassesCommand(wifi, 3016, null);
            runOnUiThread(() -> {
                if (generation != glassesConnectionGeneration || !glassesActivityActive ||
                    isFinishing()) return;
                glassesInitializing = false;
                if (!initialized) {
                    status.setText("Reconnecting OHO glasses feed...");
                    scheduleGlassesReconnect();
                    return;
                }
                openInitializedGlassesPlayer(wifi);
            });
        }, "OhoGlassesInit").start();
        return true;
    }

    private void openInitializedGlassesPlayer(android.net.Network wifi) {
        if (!glassesActivityActive || isFinishing() || glassesIjkPlayer != null) return;
        try {
            tv.danmaku.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(null);
            glassesStreamSurface = new Surface(preview.getSurfaceTexture());
            glassesIjkPlayer = new tv.danmaku.ijk.media.player.IjkMediaPlayer();
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "packet-buffering", 0L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "infbuf", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "an", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "framedrop", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "live-optimize", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "max-read-error", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "max-cached-duration", 1000L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "flush_packets", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "reconnect_streamed", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "reconnect", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "reconnect_delay_max", 100L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "analyzeduration", 1000000L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC,
                "tune", "zerolatency");
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_CODEC,
                "preset", "ultrafast");
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "mediacodec", 1L);
            glassesIjkPlayer.setOption(tv.danmaku.ijk.media.player.IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "mediacodec-auto-rotate", 1L);
            glassesIjkPlayer.setSurface(glassesStreamSurface);
            glassesIjkPlayer.setOnPreparedListener(player -> {
                player.start();
                glassesReconnectAttempts = 0;
                status.setText("OHO GLASSES LIVE - press CAPTURE or D18 UP");
                scheduleGlassesHeartbeat();
            });
            glassesIjkPlayer.setOnCompletionListener(player -> scheduleGlassesReconnect());
            glassesIjkPlayer.setOnErrorListener((player, what, extra) -> {
                android.util.Log.e("GraffitiHunterGlasses",
                    "IJK stream error what=" + what + " extra=" + extra);
                status.setText("Reconnecting OHO glasses feed...");
                scheduleGlassesReconnect();
                return true;
            });
            glassesIjkPlayer.setDataSource("rtsp://192.168.1.254/xxxx.mov");
            glassesIjkPlayer.prepareAsync();
        } catch (Exception error) {
            android.util.Log.e("GraffitiHunterGlasses", "Could not open IJK stream", error);
            status.setText("Reconnecting OHO glasses feed...");
            scheduleGlassesReconnect();
        }
    }

    private boolean sendGlassesCommand(android.net.Network wifi, int command, String parameter) {
        java.net.HttpURLConnection connection = null;
        try {
            String address = "http://192.168.1.254//?custom=1&cmd=" + command +
                (parameter == null ? "" : "&par=" + parameter);
            connection = (java.net.HttpURLConnection)wifi.openConnection(new java.net.URL(address));
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setUseCaches(false);
            int response = connection.getResponseCode();
            java.io.InputStream input = response >= 400
                ? connection.getErrorStream() : connection.getInputStream();
            java.io.ByteArrayOutputStream responseBody = new java.io.ByteArrayOutputStream();
            if (input != null) {
                byte[] buffer = new byte[256];
                int count;
                while ((count = input.read(buffer)) >= 0) responseBody.write(buffer, 0, count);
                input.close();
            }
            String body = responseBody.toString("UTF-8");
            android.util.Log.i("GraffitiHunterGlasses", "cmd=" + command +
                " HTTP=" + response + " body=" + body.replace('\n', ' '));
            return response >= 200 && response < 400 && body.contains("<Status>0</Status>");
        } catch (Exception error) {
            android.util.Log.e("GraffitiHunterGlasses", "cmd=" + command + " failed", error);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void scheduleGlassesHeartbeat() {
        glassesHeartbeatHandler.removeCallbacksAndMessages(null);
        glassesHeartbeatHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!glassesActivityActive ||
                    (glassesPlayer == null && glassesIjkPlayer == null) || glassesWifiNetwork == null)
                    return;
                android.net.Network wifi = glassesWifiNetwork;
                new Thread(() -> sendGlassesCommand(wifi, 3016, null),
                    "OhoGlassesHeartbeat").start();
                glassesHeartbeatHandler.postDelayed(this, 4000L);
            }
        }, 3500L);
    }

    private void stopGlassesStream() {
        glassesHeartbeatHandler.removeCallbacksAndMessages(null);
        glassesConnectionGeneration++;
        glassesInitializing = false;
        androidx.media3.exoplayer.ExoPlayer player = glassesPlayer;
        glassesPlayer = null;
        if (player != null) {
            try { player.clearVideoTextureView(preview); } catch (Exception ignored) { }
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
        }
        tv.danmaku.ijk.media.player.IjkMediaPlayer ijkPlayer = glassesIjkPlayer;
        glassesIjkPlayer = null;
        if (ijkPlayer != null) {
            try { ijkPlayer.setSurface(null); } catch (Exception ignored) { }
            try { ijkPlayer.stop(); } catch (Exception ignored) { }
            try { ijkPlayer.release(); } catch (Exception ignored) { }
        }
        Surface streamSurface = glassesStreamSurface;
        glassesStreamSurface = null;
        if (streamSurface != null) streamSurface.release();
    }

    private boolean isGlassesStreamPlaying() {
        try {
            return glassesIjkPlayer != null && glassesIjkPlayer.isPlaying();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void captureGlassesFrame() {
        if (captureInProgress || !isGlassesStreamPlaying()) return;
        captureInProgress = true;
        Bitmap bitmap = preview.getBitmap();
        if (bitmap == null || bitmap.getWidth() < 2) {
            captureInProgress = false;
            status.setText("No glasses frame available yet");
            return;
        }
        try {
            File destination = new File(getFilesDir(),
                "glasses_capture_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output);
            }
            saveCaptureMetadata(destination, 0);
            // Close the RTSP session while its Wi-Fi route is still intact. The
            // glasses accept only one live session and need this teardown before
            // rapid capture reopens the activity.
            stopGlassesStream();
            getSharedPreferences("GraffitiHunterNativeCamera", MODE_PRIVATE).edit()
                .putLong("glasses_session_closed_at", System.currentTimeMillis()).apply();
            bindCellularForMapDownloadsAndFinish();
        } catch (Exception error) {
            captureInProgress = false;
            status.setText("Could not save glasses image");
        } finally {
            bitmap.recycle();
        }
    }

    private void bindCellularForMapDownloadsAndFinish() {
        try {
            android.net.ConnectivityManager connectivity =
                (android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if (cellularHoldCallback != null) {
                try { connectivity.unregisterNetworkCallback(cellularHoldCallback); }
                catch (Exception ignored) { }
            }
            status.setText("Preparing cellular connection for map...");
            android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
            cellularHoldCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    connectivity.bindProcessToNetwork(network);
                    runOnUiThread(() -> finishSuccessfulCapture());
                }
                @Override public void onUnavailable() {
                    runOnUiThread(() -> finishSuccessfulCapture());
                }
            };
            connectivity.requestNetwork(request, cellularHoldCallback, 6000);
        } catch (Exception error) {
            finishSuccessfulCapture();
        }
    }

    private void finishSuccessfulCapture() {
        if (captureResultSent || isFinishing()) return;
        captureResultSent = true;
        if (launchedFromHome) {
            Intent review = new Intent(this, NativeCaptureReviewActivity.class);
            startActivity(review);
            finish();
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private void saveCaptureMetadata(File destination, int rotation) {
        Location captureLocation = latestLocation;
        if (captureLocation == null || System.currentTimeMillis() - captureLocation.getTime() > 30000L) {
            try {
                Location gps = locationManager == null ? null :
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location network = locationManager == null ? null :
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (gps != null && (captureLocation == null || gps.getTime() > captureLocation.getTime()))
                    captureLocation = gps;
                if (network != null && (captureLocation == null ||
                    (network.getTime() > captureLocation.getTime() &&
                    network.getAccuracy() <= captureLocation.getAccuracy() + 25f)))
                    captureLocation = network;
            } catch (SecurityException ignored) { }
        }
        android.content.SharedPreferences.Editor saved =
            getSharedPreferences("GraffitiHunterNativeCamera", MODE_PRIVATE).edit()
            .putString("capture_path", destination.getAbsolutePath())
            .putInt("capture_rotation", rotation);
        if (captureLocation != null &&
            System.currentTimeMillis() - captureLocation.getTime() <= 300000L) {
            saved.putLong("capture_lat_bits",
                Double.doubleToRawLongBits(captureLocation.getLatitude()));
            saved.putLong("capture_lon_bits",
                Double.doubleToRawLongBits(captureLocation.getLongitude()));
            saved.putFloat("capture_accuracy", captureLocation.getAccuracy());
            saved.putLong("capture_location_time", captureLocation.getTime());
        }
        saved.apply();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void saveCapturedImage(ImageReader reader) {
        try (Image image = reader.acquireNextImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            File destination = new File(getFilesDir(),
                "native_capture_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                output.write(bytes);
            }
            saveCaptureMetadata(destination, jpegOrientation());
            runOnUiThread(this::finishSuccessfulCapture);
        } catch (Exception error) {
            captureInProgress = false;
            runOnUiThread(() -> status.setText("Could not save camera image"));
        }
    }

    private int jpegOrientation() {
        Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int device;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: device = 90; break;
            case Surface.ROTATION_180: device = 180; break;
            case Surface.ROTATION_270: device = 270; break;
            default: device = 0;
        }
        return ((sensor == null ? 90 : sensor) - device + 360) % 360;
    }

    private Size choosePreviewSize(Size[] sizes) {
        return Collections.max(Arrays.asList(sizes),
            Comparator.comparingLong(s -> Math.min((long)s.getWidth() * s.getHeight(),
                1920L * 1080L)));
    }

    private Size chooseJpegSize(Size[] sizes) {
        Size best = sizes[0];
        long limit = 16_000_000L;
        for (Size size : sizes) {
            long pixels = (long)size.getWidth() * size.getHeight();
            long bestPixels = (long)best.getWidth() * best.getHeight();
            if (pixels <= limit && (bestPixels > limit || pixels > bestPixels)) best = size;
        }
        return best;
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float cx = viewRect.centerX();
        float cy = viewRect.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float)viewHeight / previewSize.getHeight(),
                (float)viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, cx, cy);
        }
        preview.setTransform(matrix);
    }

    private void closeCamera() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
    }
}
