package com.skyking0007.irishdrviewfinder;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements CameraController.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final String STATE_CAMERA_ID = "cameraId";
    private static final String STATE_MODE_INDEX = "modeIndex";
    private static final String STATE_SHORT_INDEX = "shortIndex";
    private static final String STATE_LONG_INDEX = "longIndex";
    private static final String STATE_ISO_INDEX = "isoIndex";
    private static final String STATE_AUTO_HDR = "autoHdr";
    private static final long[] EXPOSURES_NS = {
            1_000_000_000L / 2000,
            1_000_000_000L / 1000,
            1_000_000_000L / 500,
            1_000_000_000L / 480,
            1_000_000_000L / 240,
            1_000_000_000L / 120,
            1_000_000_000L / 60,
            1_000_000_000L / 30,
            1_000_000_000L / 15,
            1_000_000_000L / 8
    };
    private static final int[] ISO_VALUES = {100, 200, 400, 800, 1600, 3200};

    private HdrGlView glView;
    private CameraController controller;
    private TextView statusText;
    private TextView shortLabel;
    private TextView longLabel;
    private TextView isoLabel;
    private Spinner cameraSpinner;
    private Spinner modeSpinner;
    private SeekBar shortBar;
    private SeekBar longBar;
    private SeekBar isoBar;
    private Button captureButton;
    private Button autoButton;
    private final List<CameraController.CameraDescriptor> cameras = new ArrayList<>();
    private boolean updatingControls;
    private String selectedCameraId;
    private volatile int modeIndex = 2;
    private volatile int shortIndex = 3;
    private volatile int longIndex = 6;
    private volatile int isoIndex = 2;
    private volatile boolean autoHdrEnabled = true;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private boolean heartbeatScheduled;
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            heartbeatScheduled = false;
            if (glView != null) {
                RuntimeLogger.event(
                        "UI_HEARTBEAT",
                        String.format(
                                Locale.US,
                                "mode=%d auto=%s camera=%.1ffps pairs=%.1ffps dropped=%d",
                                modeIndex,
                                autoHdrEnabled,
                                glView.getInputFps(),
                                glView.getHdrPairFps(),
                                glView.getDroppedRenderFrames()));
            }
            scheduleHeartbeat();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RuntimeLogger.install(getApplicationContext());
        RuntimeLogger.event("ACTIVITY", "onCreate");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        restoreUiState(savedInstanceState);
        buildUi();
        controller = new CameraController(this, this);
        glView.setInputSurfaceListener(controller::setPreviewSurface);
        controller.setAutoHdrExposure(autoHdrEnabled);
        controller.setPreviewMode(previewModeForIndex(modeIndex));
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            loadCameras();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void restoreUiState(Bundle state) {
        if (state == null) return;
        selectedCameraId = state.getString(STATE_CAMERA_ID);
        modeIndex = clampIndex(state.getInt(STATE_MODE_INDEX, modeIndex), 3);
        shortIndex = clampIndex(state.getInt(STATE_SHORT_INDEX, shortIndex), EXPOSURES_NS.length);
        longIndex = clampIndex(state.getInt(STATE_LONG_INDEX, longIndex), EXPOSURES_NS.length);
        isoIndex = clampIndex(state.getInt(STATE_ISO_INDEX, isoIndex), ISO_VALUES.length);
        autoHdrEnabled = state.getBoolean(STATE_AUTO_HDR, autoHdrEnabled);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CAMERA_ID, selectedCameraId);
        outState.putInt(STATE_MODE_INDEX, modeIndex);
        outState.putInt(STATE_SHORT_INDEX, shortIndex);
        outState.putInt(STATE_LONG_INDEX, longIndex);
        outState.putInt(STATE_ISO_INDEX, isoIndex);
        outState.putBoolean(STATE_AUTO_HDR, autoHdrEnabled);
    }

    private void buildUi() {
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        glView = new HdrGlView(this);
        root.addView(glView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));
        panel.setBackgroundColor(0xCC000000);

        statusText = textView("Waiting for camera permission…", portrait ? 12 : 13);
        panel.addView(statusText, matchWrap());

        cameraSpinner = new Spinner(this);
        cameraSpinner.setBackgroundColor(0xCCEEEEEE);

        modeSpinner = new Spinner(this);
        ArrayAdapter<String> modes = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"NORMAL AE", "SPLIT", "HDR FUSED"});
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modes);
        modeSpinner.setSelection(modeIndex, false);

        autoButton = new Button(this);
        refreshAutoButton();

        captureButton = new Button(this);
        captureButton.setText("CAPTURE HDR SET");

        shortLabel = textView("Short " + CameraController.exposureText(EXPOSURES_NS[shortIndex]), 12);
        shortBar = new SeekBar(this);
        shortBar.setMax(EXPOSURES_NS.length - 1);
        shortBar.setProgress(shortIndex);

        longLabel = textView("Long " + CameraController.exposureText(EXPOSURES_NS[longIndex]), 12);
        longBar = new SeekBar(this);
        longBar.setMax(EXPOSURES_NS.length - 1);
        longBar.setProgress(longIndex);

        isoLabel = textView("ISO " + ISO_VALUES[isoIndex], 12);
        isoBar = new SeekBar(this);
        isoBar.setMax(ISO_VALUES.length - 1);
        isoBar.setProgress(isoIndex);

        if (portrait) {
            buildPortraitControls(panel, autoButton);
        } else {
            buildLandscapeControls(panel, autoButton);
        }

        root.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        setManualControlsEnabled(!autoHdrEnabled);

        modeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            modeIndex = clampIndex(position, 3);
            HdrGlView.Mode glMode = glModeForIndex(modeIndex);
            glView.setMode(glMode);
            if (controller != null) controller.setPreviewMode(previewModeForIndex(modeIndex));
        }));

        cameraSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            if (updatingControls || position < 0 || position >= cameras.size() || controller == null) return;
            selectedCameraId = cameras.get(position).id;
            controller.openCamera(selectedCameraId);
        }));

        SeekBar.OnSeekBarChangeListener settingsListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingControls || controller == null) return;
                shortIndex = shortBar.getProgress();
                longIndex = longBar.getProgress();
                isoIndex = isoBar.getProgress();
                if (autoHdrEnabled) {
                    autoHdrEnabled = false;
                    controller.setAutoHdrExposure(false);
                    refreshAutoButton();
                    setManualControlsEnabled(true);
                }
                controller.setManualSettings(
                        EXPOSURES_NS[shortIndex],
                        EXPOSURES_NS[longIndex],
                        ISO_VALUES[isoIndex]);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        shortBar.setOnSeekBarChangeListener(settingsListener);
        longBar.setOnSeekBarChangeListener(settingsListener);
        isoBar.setOnSeekBarChangeListener(settingsListener);

        autoButton.setOnClickListener(v -> {
            autoHdrEnabled = !autoHdrEnabled;
            refreshAutoButton();
            setManualControlsEnabled(!autoHdrEnabled);
            if (controller != null) {
                controller.setAutoHdrExposure(autoHdrEnabled);
                if (!autoHdrEnabled) {
                    controller.setManualSettings(
                            EXPOSURES_NS[shortIndex],
                            EXPOSURES_NS[longIndex],
                            ISO_VALUES[isoIndex]);
                }
            }
            Toast.makeText(
                    this,
                    autoHdrEnabled
                            ? "AUTO HDR continuously meters the scene; manual sliders are locked"
                            : "MANUAL SAFE: sliders set the target bracket; flicker-safe timing may use gain separation",
                    Toast.LENGTH_SHORT).show();
        });

        captureButton.setOnClickListener(v -> {
            if (controller == null) return;
            captureButton.setEnabled(false);
            controller.captureHdrSet();
        });
    }

    private void buildPortraitControls(LinearLayout panel, Button autoButton) {
        panel.addView(cameraSpinner, matchWrap());

        LinearLayout modeRow = makeHorizontalRow();
        modeRow.addView(modeSpinner, weighted(1f));
        modeRow.addView(autoButton, weighted(1f));
        panel.addView(modeRow, matchWrap());

        panel.addView(captureButton, matchWrap());
        panel.addView(makeSliderRow(shortLabel, shortBar), matchWrap());
        panel.addView(makeSliderRow(longLabel, longBar), matchWrap());
        panel.addView(makeSliderRow(isoLabel, isoBar), matchWrap());
    }

    private void buildLandscapeControls(LinearLayout panel, Button autoButton) {
        LinearLayout row1 = makeHorizontalRow();
        row1.addView(cameraSpinner, weighted(2f));
        row1.addView(modeSpinner, weighted(1f));
        row1.addView(autoButton, weighted(1f));
        row1.addView(captureButton, weighted(1.2f));
        panel.addView(row1, matchWrap());

        LinearLayout row2 = makeHorizontalRow();
        row2.addView(shortLabel, weighted(0.7f));
        row2.addView(shortBar, weighted(1.3f));
        row2.addView(longLabel, weighted(0.7f));
        row2.addView(longBar, weighted(1.3f));
        row2.addView(isoLabel, weighted(0.5f));
        row2.addView(isoBar, weighted(1f));
        panel.addView(row2, matchWrap());
    }

    private LinearLayout makeSliderRow(TextView label, SeekBar bar) {
        LinearLayout row = makeHorizontalRow();
        row.addView(label, weighted(0.35f));
        row.addView(bar, weighted(0.65f));
        return row;
    }

    private void loadCameras() {
        try {
            cameras.clear();
            cameras.addAll(controller.getCompatibleCameras());
            if (cameras.isEmpty()) {
                statusText.setText("No back camera exposes both RAW and MANUAL_SENSOR capability.");
                return;
            }
            ArrayAdapter<CameraController.CameraDescriptor> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    cameras);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            int selectedIndex = findCameraIndex(selectedCameraId);
            if (selectedIndex < 0) selectedIndex = 0;
            updatingControls = true;
            cameraSpinner.setAdapter(adapter);
            cameraSpinner.setSelection(selectedIndex, false);
            selectedCameraId = cameras.get(selectedIndex).id;
            updatingControls = false;
            controller.openCamera(selectedCameraId);
        } catch (CameraAccessException e) {
            statusText.setText("Camera discovery failed: " + e.getMessage());
        }
    }

    private int findCameraIndex(String id) {
        if (id == null) return -1;
        for (int i = 0; i < cameras.size(); i++) {
            if (id.equals(cameras.get(i).id)) return i;
        }
        return -1;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadCameras();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            statusText.setText("Camera permission is required.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        RuntimeLogger.event("ACTIVITY", "onResume");
        scheduleHeartbeat();
        if (glView != null) {
            glView.onResume();
            glView.republishInputSurface();
        }
        if (controller != null && selectedCameraId != null
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            controller.openCamera(selectedCameraId);
        }
    }

    @Override
    protected void onPause() {
        RuntimeLogger.event("ACTIVITY", "onPause");
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatScheduled = false;
        if (controller != null) controller.stopCamera();
        if (glView != null) glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        RuntimeLogger.event("ACTIVITY", "onDestroy");
        if (controller != null) controller.close();
        super.onDestroy();
    }

    @Override
    public void onStatus(String text) {
        String combined = String.format(
                Locale.US,
                "%s   |   camera %.1f fps   HDR pairs %.1f fps   dropped %d",
                text,
                glView.getInputFps(),
                glView.getHdrPairFps(),
                glView.getDroppedRenderFrames());
        runOnUiThread(() -> statusText.setText(combined));
    }

    @Override
    public void onPreviewMeta(FrameMeta meta) {
        glView.enqueueMeta(meta);
    }

    @Override
    public void onPreviewSurfaceSizeRequired(Size previewSize) {
        glView.configureInputBufferSize(
                previewSize.getWidth(),
                previewSize.getHeight(),
                controller::onPreviewSurfaceConfigured);
    }

    @Override
    public void onCameraConfigured(
            String cameraId,
            int sensorOrientation,
            Size previewSize,
            Size rawSize,
            Size jpegSize,
            Integer syncLatency,
            int targetPreviewFps,
            android.util.Range<Integer> aeFpsRange,
            boolean srgbTonemap) {
        int displayDegrees = rotationToDegrees(getWindowManager().getDefaultDisplay().getRotation());
        int previewRelation = (sensorOrientation - displayDegrees + 360) % 360;
        int jpegOrientation = (sensorOrientation - displayDegrees + 360) % 360;
        glView.setProducerOwnedOrientationDegrees(previewRelation);
        controller.setJpegOrientationDegrees(jpegOrientation);
        controller.setPreviewMode(previewModeForIndex(modeIndex));
        controller.setAutoHdrExposure(autoHdrEnabled);
        controller.setManualSettings(
                EXPOSURES_NS[shortIndex],
                EXPOSURES_NS[longIndex],
                ISO_VALUES[isoIndex]);
        String configurationText =
                "Camera " + cameraId
                        + " | preview " + previewSize
                        + " | RAW " + rawSize
                        + " | JPEG " + jpegSize
                        + " | preview orientation=SurfaceTexture relation=" + previewRelation + "°"
                        + " | JPEG/DNG orientation=" + jpegOrientation + "°"
                        + " | target=" + targetPreviewFps + " fps"
                        + " | AE fps=" + (aeFpsRange == null ? "auto" : aeFpsRange)
                        + " | sRGB tonemap=" + (srgbTonemap ? "preset" : "HAL default")
                        + " | sync latency=" + (syncLatency == null ? "?" : syncLatency)
                        + " | files -> Downloads/IrisHDRViewfinder"
                        + " | log -> " + RuntimeLogger.location();
        RuntimeLogger.event("CAMERA_CONFIG", configurationText);
        runOnUiThread(() -> statusText.setText(configurationText));
    }

    @Override
    public void onManualSettings(long shortExposureNs, long longExposureNs, int iso) {
        runOnUiThread(() -> {
            updatingControls = true;
            shortIndex = nearestExposureIndex(shortExposureNs);
            longIndex = nearestExposureIndex(longExposureNs);
            isoIndex = nearestIsoIndex(iso);
            shortBar.setProgress(shortIndex);
            longBar.setProgress(longIndex);
            isoBar.setProgress(isoIndex);
            shortLabel.setText("Short " + CameraController.exposureText(shortExposureNs));
            longLabel.setText("Long " + CameraController.exposureText(longExposureNs));
            isoLabel.setText("ISO " + iso + "  MANUAL");
            updatingControls = false;
            if (!autoHdrEnabled) setManualControlsEnabled(true);
        });
    }

    @Override
    public void onAutoHdrSettings(
            long shortExposureNs,
            int shortIso,
            long longExposureNs,
            int longIso,
            String flickerLabel,
            double bracketEv) {
        runOnUiThread(() -> {
            if (!autoHdrEnabled) return;
            shortLabel.setText(
                    "Short AUTO " + CameraController.exposureText(shortExposureNs) + " ISO" + shortIso);
            longLabel.setText(
                    "Long AUTO " + CameraController.exposureText(longExposureNs) + " ISO" + longIso);
            isoLabel.setText(String.format(
                    Locale.US,
                    "AUTO HDR %.1f EV  flicker %s",
                    bracketEv,
                    flickerLabel));
            setManualControlsEnabled(false);
        });
    }

    @Override
    public void onCaptureFinished(String captureId, boolean success, String message) {
        RuntimeLogger.event(success ? "CAPTURE_UI_DONE" : "CAPTURE_UI_FAIL", captureId + " " + message);
        runOnUiThread(() -> {
            captureButton.setEnabled(true);
            Toast.makeText(
                    this,
                    (success ? "Saved " : "Capture failed: ") + captureId + "\n" + message,
                    Toast.LENGTH_LONG).show();
            statusText.setText(message);
        });
    }

    private void scheduleHeartbeat() {
        if (heartbeatScheduled) return;
        heartbeatScheduled = true;
        heartbeatHandler.postDelayed(heartbeatRunnable, 10_000L);
    }

    private void refreshAutoButton() {
        if (autoButton == null) return;
        autoButton.setText(autoHdrEnabled ? "HDR AUTO: ON" : "HDR MANUAL SAFE");
    }

    private void setManualControlsEnabled(boolean enabled) {
        if (shortBar != null) shortBar.setEnabled(enabled);
        if (longBar != null) longBar.setEnabled(enabled);
        if (isoBar != null) isoBar.setEnabled(enabled);
        if (!enabled) {
            if (shortBar != null) shortBar.setAlpha(0.45f);
            if (longBar != null) longBar.setAlpha(0.45f);
            if (isoBar != null) isoBar.setAlpha(0.45f);
        } else {
            if (shortBar != null) shortBar.setAlpha(1.0f);
            if (longBar != null) longBar.setAlpha(1.0f);
            if (isoBar != null) isoBar.setAlpha(1.0f);
        }
    }

    private static HdrGlView.Mode glModeForIndex(int index) {
        if (index == 0) return HdrGlView.Mode.NORMAL;
        if (index == 1) return HdrGlView.Mode.SPLIT;
        return HdrGlView.Mode.HDR;
    }

    private static CameraController.PreviewMode previewModeForIndex(int index) {
        if (index == 0) return CameraController.PreviewMode.NORMAL;
        if (index == 1) return CameraController.PreviewMode.SPLIT;
        return CameraController.PreviewMode.HDR;
    }

    private static int clampIndex(int index, int count) {
        return Math.max(0, Math.min(count - 1, index));
    }

    private static int nearestExposureIndex(long exposureNs) {
        int best = 0;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < EXPOSURES_NS.length; i++) {
            long delta = Math.abs(EXPOSURES_NS[i] - exposureNs);
            if (delta < bestDelta) {
                best = i;
                bestDelta = delta;
            }
        }
        return best;
    }

    private static int nearestIsoIndex(int iso) {
        int best = 0;
        int bestDelta = Integer.MAX_VALUE;
        for (int i = 0; i < ISO_VALUES.length; i++) {
            int delta = Math.abs(ISO_VALUES[i] - iso);
            if (delta < bestDelta) {
                best = i;
                bestDelta = delta;
            }
        }
        return best;
    }

    private static int rotationToDegrees(int rotation) {
        if (rotation == Surface.ROTATION_90) return 90;
        if (rotation == Surface.ROTATION_180) return 180;
        if (rotation == Surface.ROTATION_270) return 270;
        return 0;
    }

    private TextView textView(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(4), dp(2), dp(4), dp(2));
        return view;
    }

    private LinearLayout makeHorizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private interface SelectionHandler {
        void onSelected(int position);
    }

    private static final class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final SelectionHandler handler;

        SimpleItemSelectedListener(SelectionHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            handler.onSelected(position);
        }

        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}

final class RuntimeLogger {
    private static final String TAG = "IrisHDR";
    // Event producers are deliberately rate-limited; this logger must never become a frame-rate owner.
    private static final Object FILE_LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "IrisHdrLogger");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile boolean installed;
    private static volatile String location = "log not initialized";
    private static BufferedWriter writer;
    private static Thread.UncaughtExceptionHandler previousCrashHandler;

    private RuntimeLogger() {}

    static void install(Context context) {
        if (installed) return;
        synchronized (FILE_LOCK) {
            if (installed) return;
            installed = true;
            Context app = context.getApplicationContext();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String fileName = "IrisHDR_Runtime_" + stamp + ".txt";
            try {
                ContentResolver resolver = app.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/IrisHDRViewfinder/Logs");
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
                OutputStream output = resolver.openOutputStream(uri, "w");
                if (output == null) throw new IllegalStateException("MediaStore output stream is null");
                writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
                location = "Downloads/IrisHDRViewfinder/Logs/" + fileName;
            } catch (Throwable t) {
                location = "Logcat only; file logger init failed: " + t.getClass().getSimpleName();
                Log.e(TAG, "Runtime log file initialization failed", t);
            }

            previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                writeCrashSynchronously(thread, throwable);
                Thread.UncaughtExceptionHandler prior = previousCrashHandler;
                if (prior != null) prior.uncaughtException(thread, throwable);
            });
        }

        String version = "?";
        long versionCode = -1L;
        try {
            PackageInfo info = appPackageInfo(context);
            version = info.versionName == null ? "?" : info.versionName;
            versionCode = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable ignored) {
        }
        event(
                "SESSION",
                "start version=" + version + "/" + versionCode
                        + " manufacturer=" + Build.MANUFACTURER
                        + " brand=" + Build.BRAND
                        + " model=" + Build.MODEL
                        + " device=" + Build.DEVICE
                        + " sdk=" + Build.VERSION.SDK_INT
                        + " log=" + location);
    }

    private static PackageInfo appPackageInfo(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    }

    static String location() {
        return location;
    }

    static void event(String category, String message) {
        String safeCategory = category == null ? "EVENT" : category;
        String safeMessage = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ');
        Log.i(TAG, safeCategory + " " + safeMessage);
        String line = formatLine(safeCategory, safeMessage, Thread.currentThread().getName());
        IO.execute(() -> writeLine(line));
    }

    static void error(String category, Throwable throwable) {
        StringWriter stack = new StringWriter();
        if (throwable != null) throwable.printStackTrace(new PrintWriter(stack));
        String message = throwable == null ? "null" : throwable.toString();
        Log.e(TAG, category + " " + message, throwable);
        String line = formatLine(
                category == null ? "ERROR" : category,
                message + " | " + stack.toString().replace('\n', '|').replace('\r', ' '),
                Thread.currentThread().getName());
        IO.execute(() -> writeLine(line));
    }

    private static void writeCrashSynchronously(Thread thread, Throwable throwable) {
        StringWriter stack = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stack));
        String text = formatLine(
                "UNCAUGHT_CRASH",
                throwable + " | " + stack.toString().replace('\n', '|').replace('\r', ' '),
                thread == null ? "?" : thread.getName());
        Log.e(TAG, "UNCAUGHT_CRASH", throwable);
        writeLine(text);
    }

    private static String formatLine(String category, String message, String threadName) {
        String wall = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        return wall + " [" + threadName + "] " + category + " " + message;
    }

    private static void writeLine(String line) {
        synchronized (FILE_LOCK) {
            if (writer == null) return;
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (Throwable t) {
                Log.e(TAG, "Runtime log write failed", t);
            }
        }
    }
}
