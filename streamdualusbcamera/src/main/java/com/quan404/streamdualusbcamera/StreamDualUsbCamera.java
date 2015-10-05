package com.quan404.streamdualusbcamera;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Toast;

import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

import net.majorkernelpanic.streaming.Session;
import net.majorkernelpanic.streaming.SessionBuilder;
import net.majorkernelpanic.streaming.audio.AudioQuality;
import net.majorkernelpanic.streaming.gl.SurfaceView;
import net.majorkernelpanic.streaming.rtsp.RtspClient;
import net.majorkernelpanic.streaming.video.VideoQuality;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamDualUsbCamera extends Activity implements CameraDialog.CameraDialogParent, RtspClient.Callback,
        Session.Callback, SurfaceHolder.Callback{

    // for debugging
    private static String TAG = "StreamDualUsbCamera";
    private static boolean DEBUG = true;

    // for thread pool
    private static final int CORE_POOL_SIZE = 1;		// initial/minimum threads
    private static final int MAX_POOL_SIZE = 4;			// maximum threads
    private static final int KEEP_ALIVE_TIME = 10;		// time periods while keep the idle thread
    protected static final ThreadPoolExecutor EXECUTER
            = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_TIME,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;

    private UVCCamera mCameraLeft = null;
    private UVCCameraTextureView mUVCCameraViewLeft;
    private Surface mPreviewSurfaceLeft;

    private UVCCamera mCameraRight = null;
    private UVCCameraTextureView mUVCCameraViewRight;
    private Surface mPreviewSurfaceRight;

    private int SELECTED_ID = -1;


    // Rtsp session
    private boolean READY_FOR_STREAM = false;
    private Session mSession;
    private static RtspClient mClient;
    private static SurfaceView mSurfaceView;

    private Surface canvasSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stream_dual_usb_camera);

        hideNavigationBar();

        mSurfaceView = (SurfaceView) findViewById(R.id.surface);
        mSurfaceView.getHolder().addCallback(this);

        mUVCCameraViewLeft = (UVCCameraTextureView) findViewById(R.id.cameraView01);
        mUVCCameraViewLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mCameraLeft == null) {
                    CameraDialog.showDialog(StreamDualUsbCamera.this);
                    SELECTED_ID = 0;
                } else {
                    releaseUVCCamera(0);
                }
            }
        });

        mUVCCameraViewRight = (UVCCameraTextureView) findViewById(R.id.cameraView02);
        mUVCCameraViewRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mCameraRight == null) {
                    CameraDialog.showDialog(StreamDualUsbCamera.this);
                    SELECTED_ID = 1;
                } else {
                    releaseUVCCamera(1);
                }
            }
        });


        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);

        new Thread(new MyStreamThread()).start();
        // Initialize RTSP client
        initRtspClient();
    }
    private void initRtspClient() {
        // Configures the SessionBuilder
        mSession = SessionBuilder.getInstance()
                .setContext(getApplicationContext())
                .setAudioEncoder(SessionBuilder.AUDIO_NONE)
                .setAudioQuality(new AudioQuality(8000, 16000))
                .setVideoQuality(new VideoQuality(UVCCamera.DEFAULT_PREVIEW_WIDTH * 2, UVCCamera.DEFAULT_PREVIEW_HEIGHT, 15, 200000))
                .setVideoEncoder(SessionBuilder.VIDEO_H264)
                .setPreviewOrientation(90)
                .setSurfaceView(mSurfaceView)
                .setCallback(this).build();

        // Get Surface
        canvasSurface = mSession.getVideoTrack().getSurface();

        // Configures the RTSP client
        mClient = new RtspClient();
        mClient.setSession(mSession);
        mClient.setCallback(this);

        mSurfaceView.setAspectRatioMode(SurfaceView.ASPECT_RATIO_PREVIEW);

        String ip, port, path;

        // We parse the URI written in the Editext
        Pattern uri = Pattern.compile("rtsp://(.+):(\\d+)/(.+)");
        Matcher m = uri.matcher(AppConfig.STREAM_URL);
        m.find();
        ip = m.group(1);
        port = m.group(2);
        path = m.group(3);

        mClient.setCredentials(AppConfig.PUBLISHER_USERNAME,
                AppConfig.PUBLISHER_PASSWORD);
        mClient.setServerAddress(ip, Integer.parseInt(port));
        mClient.setStreamPath("/" + path);

        Log.e(TAG, "done initRtsp");
    }

    private void toggleStreaming() {
        if(!READY_FOR_STREAM) return;

        if (!mClient.isStreaming()) {
            // Start camera preview
            mSession.startPreview();

            // Start video stream
            mClient.startStream();
        } else {
            // already streaming, stop streaming
            // stop camera preview
            mSession.stopPreview();

            // stop streaming
            mClient.stopStream();
        }
    }
    @Override
    protected void onResume() {
        super.onResume();

        mUSBMonitor.register();
        if (mCameraLeft != null)
            mCameraLeft.startPreview();

        if (mCameraRight != null)
            mCameraRight.startPreview();
    }

    @Override
    protected void onPause() {
        mUSBMonitor.unregister();
        if (mCameraLeft != null)
            mCameraLeft.stopPreview();
        if (mCameraRight != null)
            mCameraRight.stopPreview();

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if(mUSBMonitor != null){
            mUSBMonitor.destroy();
        }
        if (mCameraLeft != null)
            mCameraLeft.destroy();

        if (mCameraRight != null)
            mCameraRight.destroy();

        releaseUVCCamera(2);

        super.onDestroy();
    }

    private USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:" + device);
            Toast.makeText(StreamDualUsbCamera.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDettach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDetach:" + device);
            Toast.makeText(StreamDualUsbCamera.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            if(mCameraLeft != null && mCameraRight != null) return;

            if (DEBUG) Log.v(TAG, "onConnect: " + device);

            final UVCCamera camera = new  UVCCamera();
            final int current_id = SELECTED_ID;
            SELECTED_ID = -1;
            EXECUTER.execute(new Runnable() {
                @Override
                public void run() {
                    // Open Camera
                    camera.open(ctrlBlock);


                    // Set Preview Mode
                    try {
                        if (DEBUG) Log.v(TAG, "MJPEG MODE");
                        camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG, 0.5f);
                    } catch (IllegalArgumentException e1) {
                        e1.printStackTrace();

                        if (DEBUG) Log.v(TAG, "PREVIEW MODE");
                        try {
                            camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE, 0.5f);
                        } catch (IllegalArgumentException e2) {
                            if (DEBUG) Log.v(TAG, "CAN NOT ENTER PREVIEW MODE");
                            camera.destroy();
                            e2.printStackTrace();
                        }
                    }

                    // Start Preview
                    if (mCameraLeft == null && current_id == 0) {
                        mCameraLeft = camera;
                        if (mPreviewSurfaceLeft != null) {
                            if (DEBUG) Log.v(TAG, "mPreviewSurface.release()");
                            mPreviewSurfaceLeft.release();
                            mPreviewSurfaceLeft = null;
                        }

                        final SurfaceTexture st = mUVCCameraViewLeft.getSurfaceTexture();
                        if (st != null) {
                            if (DEBUG) Log.v(TAG, "mPreviewSurface = new Surface(st);");
                            mPreviewSurfaceLeft = new Surface(st);
                        }

                        camera.setPreviewDisplay(mPreviewSurfaceLeft);
                        camera.setFrameCallback(mIFrameCallbackLeft, UVCCamera.PIXEL_FORMAT_RGB565);
                        camera.startPreview();
                    }

                    if (mCameraRight == null  && current_id == 1) {
                        mCameraRight = camera;
                        if (mPreviewSurfaceRight != null) {
                            if (DEBUG) Log.v(TAG, "mPreviewSurface.release()");
                            mPreviewSurfaceRight.release();
                            mPreviewSurfaceRight = null;
                        }

                        final SurfaceTexture st = mUVCCameraViewRight.getSurfaceTexture();
                        if (st != null) {
                            if (DEBUG) Log.v(TAG, "mPreviewSurface = new Surface(st);");
                            mPreviewSurfaceRight = new Surface(st);
                        }

                        camera.setPreviewDisplay(mPreviewSurfaceRight);
                        camera.setFrameCallback(mIFrameCallbackRight, UVCCamera.PIXEL_FORMAT_RGB565);
                        camera.startPreview();
                    }


                }
            });

        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            if(DEBUG) Log.v(TAG, "onDisconnect" + device);
            if(mCameraLeft != null && device.equals(mCameraLeft.getDevice())){
                releaseUVCCamera(0);
            }
            if(mCameraRight != null && device.equals(mCameraRight.getDevice())){
                releaseUVCCamera(1);
            }
        }

        @Override
        public void onCancel() {

        }
    };

    private static final int MAX_FRAME_AVAILABLE = 1;

    private Semaphore flagLeft = new Semaphore(MAX_FRAME_AVAILABLE);
    private final Bitmap bitmapLeft = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.RGB_565);
    private final IFrameCallback mIFrameCallbackLeft = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            frame.clear();
            synchronized (bitmapLeft) {
                bitmapLeft.copyPixelsFromBuffer(frame.asReadOnlyBuffer());

                flagLeft.release(); //++
            }
        }
    };

    private Semaphore flagRight = new Semaphore(MAX_FRAME_AVAILABLE);
    final Bitmap bitmapRight = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.RGB_565);
    private final IFrameCallback mIFrameCallbackRight = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            frame.clear();
            synchronized (bitmapRight) {
                bitmapRight.copyPixelsFromBuffer(frame.asReadOnlyBuffer());

                flagRight.release(); //++
            }
        }
    };

    @Override
    public void onBitrateUpdate(long bitrate) {

    }

    @Override
    public void onSessionError(int reason, int streamType, Exception e) {

    }

    @Override
    public void onPreviewStarted() {

    }

    @Override
    public void onSessionConfigured() {

    }

    @Override
    public void onSessionStarted() {

    }

    @Override
    public void onSessionStopped() {

    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

    }

    @Override
    public void onRtspUpdate(int message, Exception exception) {

    }

    private class MyStreamThread implements Runnable{
        @Override
        public void run() {
            flagLeft.drainPermits();
            flagRight.drainPermits();
            while(true){
                try {
                    flagLeft.acquire();//--
                    flagRight.acquire();
                    Bitmap left = bitmapLeft;
                    Bitmap right = bitmapRight;

                    if(!READY_FOR_STREAM){

                        READY_FOR_STREAM = true;
                        toggleStreaming();
                    }

                    Bitmap merge = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH * 2, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888); // merge left , right
                    Canvas canvas = new Canvas(merge);
                    canvas.drawBitmap(left, 0, 0, null);
                    canvas.drawBitmap(right, left.getWidth(), 0, null);

                    drawOnCanvas(merge);

                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void drawOnCanvas(Bitmap bitmap){

        Paint paint = new Paint();

        /* Test: draw 10 frames at 30fps before start
         * these should be dropped and not causing malformed stream.
         */
        try{
            if(canvasSurface == null || !canvasSurface.isValid()) {
                Log.d(TAG, "if(canvasSurface == null || !canvasSurface.isValid())");
                canvasSurface = mSession.getVideoTrack().getSurface();
                if(canvasSurface == null || !canvasSurface.isValid()) {
                    Log.d(TAG, "if(canvasSurface == null || !canvasSurface.isValid()) READ OK");
                }else{
                    Log.d(TAG, "if(canvasSurface == null || !canvasSurface.isValid()) READ FAIL");
                }
                return;
            }
            Canvas canvas = canvasSurface.lockCanvas(null);
            Log.d(TAG, "drawOnCanvas locked");

            canvas.drawBitmap(bitmap, 0, 0, paint);

            canvasSurface.unlockCanvasAndPost(canvas);

        }catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }

    private void releaseUVCCamera(int id){
        if(DEBUG) Log.v(TAG, "releaseUVCCamera");

        if(id == 0 || id == 2){
            mCameraLeft.close();

            if (mPreviewSurfaceLeft != null){
                mPreviewSurfaceLeft.release();
                mPreviewSurfaceLeft = null;
            }
            mCameraLeft.destroy();
            mCameraLeft = null;
        }
        if(id == 1 || id == 2){
            mCameraRight.close();

            if (mPreviewSurfaceRight != null){
                mPreviewSurfaceRight.release();
                mPreviewSurfaceRight = null;
            }
            mCameraRight.destroy();
            mCameraRight = null;
        }
        SELECTED_ID = -1;
    }

    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }


    // for UI fullscreen

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if(hasFocus)
            hideNavigationBar();
    }

    private void hideNavigationBar() {
        View decorView = getWindow().getDecorView();
        // Hide both the navigation bar and the status bar.
        // SYSTEM_UI_FLAG_FULLSCREEN is only available on Android 4.1 and higher, but as
        // a general rule, you should design your app to hide the status bar whenever you
        // hide the navigation bar.
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

        decorView.setSystemUiVisibility(uiOptions);
    }
}