package com.skyking0007.irishdrviewfinder;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraAccessException;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements CameraController.Listener {
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final long[] EXPOSURES_NS = {
            1_000_000_000L / 1000,
            1_000_000_000L / 500,
            1_000_000_000L / 250,
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
    private final List<CameraController.CameraDescriptor> cameras = new ArrayList<>();
    private boolean updatingControls;
    private String selectedCameraId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        controller = new CameraController(this, this);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            loadCameras();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        glView = new HdrGlView(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));
        panel.setBackgroundColor(0x99000000);

        statusText = textView("Waiting for camera permission…", 13);
        panel.addView(statusText, matchWrap());

        LinearLayout row1 = makeHorizontalRow();
        cameraSpinner = new Spinner(this);
        cameraSpinner.setBackgroundColor(0xCCEEEEEE);
        row1.addView(cameraSpinner, weighted(2f));

        modeSpinner = new Spinner(this);
        ArrayAdapter<String> modes = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"NORMAL AE", "SPLIT", "HDR FUSED"});
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modes);
        modeSpinner.setSelection(2);
        row1.addView(modeSpinner, weighted(1f));

        Button autoButton = new Button(this);
        autoButton.setText("AUTO BRACKET");
        row1.addView(autoButton, weighted(1f));

        captureButton = new Button(this);
        captureButton.setText("CAPTURE HDR SET");
        row1.addView(captureButton, weighted(1.2f));
        panel.addView(row1, matchWrap());

        LinearLayout row2 = makeHorizontalRow();
        shortLabel = textView("Short 1/120s", 12);
        row2.addView(shortLabel, weighted(0.7f));
        shortBar = new SeekBar(this);
        shortBar.setMax(EXPOSURES_NS.length - 1);
        shortBar.setProgress(3);
        row2.addView(shortBar, weighted(1.3f));

        longLabel = textView("Long 1/30s", 12);
        row2.addView(longLabel, weighted(0.7f));
        longBar = new SeekBar(this);
        longBar.setMax(EXPOSURES_NS.length - 1);
        longBar.setProgress(5);
        row2.addView(longBar, weighted(1.3f));

        isoLabel = textView("ISO 400", 12);
        row2.addView(isoLabel, weighted(0.5f));
        isoBar = new SeekBar(this);
        isoBar.setMax(ISO_VALUES.length - 1);
        isoBar.setProgress(2);
        row2.addView(isoBar, weighted(1f));
        panel.addView(row2, matchWrap());

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(panel, panelParams);
        setContentView(root);

        modeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            HdrGlView.Mode glMode = position == 0
                    ? HdrGlView.Mode.NORMAL
                    : position == 1 ? HdrGlView.Mode.SPLIT : HdrGlView.Mode.HDR;
            CameraController.PreviewMode cameraMode = position == 0
                    ? CameraController.PreviewMode.NORMAL
                    : position == 1 ? CameraController.PreviewMode.SPLIT : CameraController.PreviewMode.HDR;
            glView.setMode(glMode);
            if (controller != null) controller.setPreviewMode(cameraMode);
        }));

        cameraSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            if (position < 0 || position >= cameras.size() || controller == null) return;
            selectedCameraId = cameras.get(position).id;
            controller.openCamera(selectedCameraId);
        }));

        SeekBar.OnSeekBarChangeListener settingsListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingControls || controller == null) return;
                controller.setManualSettings(
                        EXPOSURES_NS[shortBar.getProgress()],
                        EXPOSURES_NS[longBar.getProgress()],
                        ISO_VALUES[isoBar.getProgress()]);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        shortBar.setOnSeekBarChangeListener(settingsListener);
        longBar.setOnSeekBarChangeListener(settingsListener);
        isoBar.setOnSeekBarChangeListener(settingsListener);

        autoButton.setOnClickListener(v -> {
            if (controller != null) controller.autoBracketFromLastAe();
            Toast.makeText(this, "Uses the most recent NORMAL AE frame as the bracket center", Toast.LENGTH_SHORT).show();
        });

        captureButton.setOnClickListener(v -> {
            if (controller == null) return;
            captureButton.setEnabled(false);
            controller.captureHdrSet();
        });
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
            cameraSpinner.setAdapter(adapter);
            selectedCameraId = cameras.get(0).id;
        } catch (CameraAccessException e) {
            statusText.setText("Camera discovery failed: " + e.getMessage());
        }
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
        if (glView != null) glView.onResume();
        if (controller != null && selectedCameraId != null
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            controller.openCamera(selectedCameraId);
        }
    }

    @Override
    protected void onPause() {
        if (controller != null) controller.stopCamera();
        if (glView != null) glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.close();
        super.onDestroy();
    }

    @Override
    public void onStatus(String text) {
        runOnUiThread(() -> statusText.setText(String.format(
                Locale.US,
                "%s   |   GPU frames %.1f fps   dropped %d",
                text,
                glView.getFusionFps(),
                glView.getDroppedRenderFrames())));
    }

    @Override
    public void onPreviewFrame(YuvFrame frame, FrameMeta meta) {
        glView.enqueueFrame(frame, meta);
    }

    @Override
    public void onCameraConfigured(
            String cameraId,
            int sensorOrientation,
            Size previewSize,
            Size rawSize,
            Size jpegSize,
            Integer syncLatency) {
        int displayDegrees = rotationToDegrees(getWindowManager().getDefaultDisplay().getRotation());
        int relative = (sensorOrientation - displayDegrees + 360) % 360;
        glView.setRelativeRotationDegrees(relative);
        runOnUiThread(() -> statusText.setText(
                "Camera " + cameraId
                        + " | preview " + previewSize
                        + " | RAW " + rawSize
                        + " | JPEG " + jpegSize
                        + " | sync latency=" + (syncLatency == null ? "?" : syncLatency)
                        + " | files -> Downloads/IrisHDRViewfinder"));
    }

    @Override
    public void onManualSettings(long shortExposureNs, long longExposureNs, int iso) {
        runOnUiThread(() -> {
            updatingControls = true;
            shortBar.setProgress(nearestExposureIndex(shortExposureNs));
            longBar.setProgress(nearestExposureIndex(longExposureNs));
            isoBar.setProgress(nearestIsoIndex(iso));
            shortLabel.setText("Short " + CameraController.exposureText(shortExposureNs));
            longLabel.setText("Long " + CameraController.exposureText(longExposureNs));
            isoLabel.setText("ISO " + iso);
            updatingControls = false;
        });
    }

    @Override
    public void onCaptureFinished(String captureId, boolean success, String message) {
        runOnUiThread(() -> {
            captureButton.setEnabled(true);
            Toast.makeText(
                    this,
                    (success ? "Saved " : "Capture failed: ") + captureId + "\n" + message,
                    Toast.LENGTH_LONG).show();
            statusText.setText(message);
        });
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
