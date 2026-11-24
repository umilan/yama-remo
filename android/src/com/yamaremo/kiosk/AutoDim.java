package com.yamaremo.kiosk;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.List;

/**
 * Measures room light: the light sensor (lux) if the tablet has one, otherwise the average
 * brightness (0-255) of a small front-camera frame once a minute. Camera frames are only
 * averaged in memory, never stored.
 */
class AutoDim implements SensorEventListener {
    static final String SENSOR = "light sensor", CAMERA = "front camera";

    interface Listener { void onReading(String source, float value); }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler();
    private SensorManager sm;
    private Sensor light;
    private HandlerThread camThread;
    private Handler cam;
    private boolean running;
    private Camera openCam;

    AutoDim(Context c, Listener l) {
        ctx = c;
        listener = l;
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        light = sm != null ? sm.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
    }

    /** Which source start() will use: SENSOR, CAMERA or null (none available / camera not allowed). */
    String source(boolean allowCamera) {
        if (light != null) return SENSOR;
        if (allowCamera && Camera.getNumberOfCameras() > 0
                && ctx.getPackageManager().checkPermission(android.Manifest.permission.CAMERA, ctx.getPackageName())
                   == PackageManager.PERMISSION_GRANTED) return CAMERA;
        return null;
    }

    void start(boolean allowCamera) {
        stop();
        String src = source(allowCamera);
        if (src == null) return;
        running = true;
        if (SENSOR.equals(src)) {
            sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            camThread = new HandlerThread("yamaremo-light");
            camThread.start();
            cam = new Handler(camThread.getLooper());
            cam.postDelayed(measure, 2000);
        }
    }

    void stop() {
        running = false;
        if (sm != null) sm.unregisterListener(this);
        if (cam != null) {
            final Handler h = cam;
            final HandlerThread t = camThread;
            h.removeCallbacksAndMessages(null);
            // release the camera on its own thread, then end that thread (quitSafely needs Android 4.3)
            h.post(new Runnable() { public void run() { releaseCamera(); t.quit(); } });
            cam = null;
            camThread = null;
        }
    }

    public void onSensorChanged(SensorEvent e) {
        final float lux = e.values[0];
        main.post(new Runnable() { public void run() { listener.onReading(SENSOR, lux); } });
    }

    public void onAccuracyChanged(Sensor s, int a) { }

    /** Runs on the camera thread: open, let auto-exposure settle a few frames, average Y, release. */
    private final Runnable measure = new Runnable() {
        public void run() {
            if (!running) return;
            try {
                int id = 0;
                Camera.CameraInfo ci = new Camera.CameraInfo();
                for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                    Camera.getCameraInfo(i, ci);
                    if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) { id = i; break; }
                }
                final Camera c = Camera.open(id);
                openCam = c;
                Camera.Parameters p = c.getParameters();
                Camera.Size small = null;
                List<Camera.Size> sizes = p.getSupportedPreviewSizes();
                for (Camera.Size s : sizes) if (small == null || s.width * s.height < small.width * small.height) small = s;
                if (small != null) p.setPreviewSize(small.width, small.height);
                c.setParameters(p);
                final Camera.Size size = c.getParameters().getPreviewSize();
                final SurfaceTexture tex = new SurfaceTexture(10);
                c.setPreviewTexture(tex);
                c.setPreviewCallback(new Camera.PreviewCallback() {
                    int frames;
                    public void onPreviewFrame(byte[] data, Camera camera) {
                        if (++frames < 15) return; // give auto-exposure ~1 s
                        long sum = 0; int n = 0;
                        int lumaBytes = Math.min(data.length, size.width * size.height);
                        for (int i = 0; i < lumaBytes; i += 7) { sum += data[i] & 0xff; n++; }
                        final float mean = n > 0 ? (float) sum / n : 0;
                        releaseCamera();
                        tex.release();
                        main.post(new Runnable() { public void run() { listener.onReading(CAMERA, mean); } });
                    }
                });
                c.startPreview();
                // safety net if no frames arrive
                Handler h = cam;
                if (h != null) h.postDelayed(new Runnable() { public void run() { releaseCamera(); } }, 6000);
            } catch (Exception e) {
                releaseCamera(); // camera busy or missing: try again next time
            }
            Handler h = cam;
            if (running && h != null) h.postDelayed(this, 60000);
        }
    };

    private void releaseCamera() {
        Camera c = openCam;
        openCam = null;
        if (c == null) return;
        try { c.setPreviewCallback(null); c.stopPreview(); } catch (Exception ignored) { }
        try { c.release(); } catch (Exception ignored) { }
    }
}
