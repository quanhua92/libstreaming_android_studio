package com.quan404.streamusbcamera;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamUsbCamera extends Activity implements CameraDialog.CameraDialogParent, RtspClient.Callback,
        Session.Callback, SurfaceHolder.Callback{

    // for debugging
    private static String TAG = "SingleCameraPreview";
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
    private UVCCamera mCamera = null;
    private UVCCameraTextureView mUVCCameraView;
    private Surface mPreviewSurface;


    // Rtsp session
    private boolean READY_FOR_STREAM = false;
    private Session mSession;
    private static RtspClient mClient;
    private static SurfaceView mSurfaceView;

    private Surface canvasSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stream_usb_camera);


        mUVCCameraView = (UVCCameraTextureView) findViewById(R.id.UVCCameraTextureView);
        mUVCCameraView.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH * 1.0f / UVCCamera.DEFAULT_PREVIEW_HEIGHT);
        mUVCCameraView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mCamera == null) {
                    CameraDialog.showDialog(StreamUsbCamera.this);
                } else {
                    releaseUVCCamera();
                }
            }
        });
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);

        // Initialize RTSP client
        mSurfaceView = (SurfaceView) findViewById(R.id.surface);
        mSurfaceView.getHolder().addCallback(this);
        initRtspClient();
    }

    @Override
    protected void onResume() {
        super.onResume();

        mUSBMonitor.register();
        if (mCamera != null)
            mCamera.startPreview();

        toggleStreaming();
    }

    @Override
    protected void onPause() {
        mUSBMonitor.unregister();
        if (mCamera != null)
            mCamera.stopPreview();

        toggleStreaming();
        super.onPause();
    }

    @Override
    public void surfaceChanged(SurfaceHolder arg0, int arg1, int arg2, int arg3) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder arg0) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    @Override
    public void onBitrateUpdate(long bitrate) {

//        Log.d(TAG, "Bitrate: " + bitrate);
    }

    @Override
    public void onSessionError(int reason, int streamType, Exception e) {
        switch (reason) {
            case Session.ERROR_CAMERA_ALREADY_IN_USE:
                break;
            case Session.ERROR_CAMERA_HAS_NO_FLASH:
                break;
            case Session.ERROR_INVALID_SURFACE:
                break;
            case Session.ERROR_STORAGE_NOT_READY:
                break;
            case Session.ERROR_CONFIGURATION_NOT_SUPPORTED:
                break;
            case Session.ERROR_OTHER:
                break;
        }

        if (e != null) {
            alertError(e.getMessage());
            e.printStackTrace();
        }
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
    private void alertError(final String msg) {
        final String error = (msg == null) ? "Unknown error: " : msg;
        AlertDialog.Builder builder = new AlertDialog.Builder(StreamUsbCamera.this);
        builder.setMessage(error).setPositiveButton("Ok",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                    }
                });
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    @Override
    public void onRtspUpdate(int message, Exception exception) {
        switch (message) {
            case RtspClient.ERROR_CONNECTION_FAILED:
            case RtspClient.ERROR_WRONG_CREDENTIALS:
                alertError(exception.getMessage());
                exception.printStackTrace();
                break;
        }
    }

    private void initRtspClient() {
        // Configures the SessionBuilder
        mSession = SessionBuilder.getInstance()
                .setContext(getApplicationContext())
                .setAudioEncoder(SessionBuilder.AUDIO_NONE)
                .setAudioQuality(new AudioQuality(8000, 16000))
                .setVideoQuality(new VideoQuality(640, 480, 15, 200000))
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
    public void onDestroy() {
        super.onDestroy();
        mClient.release();
        mSession.release();

        if(mUSBMonitor != null){
            mUSBMonitor.destroy();
        }
        if (mCamera != null)
            mCamera.destroy();


        super.onDestroy();
    }

    private USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onAttach:" + device);
            Toast.makeText(StreamUsbCamera.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onDettach(UsbDevice device) {
            if (DEBUG) Log.v(TAG, "onDetach:" + device);
            Toast.makeText(StreamUsbCamera.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            if(mCamera != null) return;

            if (DEBUG) Log.v(TAG, "onConnect: " + device);

            final UVCCamera camera = new  UVCCamera();

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
//                            camera.destroy();
                            releaseUVCCamera();
                            e2.printStackTrace();
                        }
                    }

                    // Start Preview
                    if (mCamera == null) {
                        mCamera = camera;
                        if (mPreviewSurface != null) {
                            if (DEBUG) Log.v(TAG, "mPreviewSurface.release()");
                            mPreviewSurface.release();
                            mPreviewSurface = null;
                        }

                        final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
                        if (st != null) {
                            if (DEBUG) Log.v(TAG, "mPreviewSurface = new Surface(st);");
                            mPreviewSurface = new Surface(st);
                        }

                        camera.setPreviewDisplay(mPreviewSurface);
                        camera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_RGB565);
                        camera.startPreview();
                    }
                }
            });

        }

        @Override
        public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            if(DEBUG) Log.v(TAG, "onDisconnect" + device);
            if(mCamera != null && device.equals(mCamera.getDevice())){
                releaseUVCCamera();
            }
        }

        @Override
        public void onCancel() {

        }
    };

    private void releaseUVCCamera(){
        if(DEBUG) Log.v(TAG, "releaseUVCCamera");
        mCamera.close();

        if (mPreviewSurface != null){
            mPreviewSurface.release();
            mPreviewSurface = null;
        }
        mCamera.destroy();
        mCamera = null;
    }

    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }
    android.os.Handler handlr = new android.os.Handler();
    final Bitmap bitmap = Bitmap.createBitmap(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, Bitmap.Config.RGB_565);
    private final IFrameCallback mIFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {

            frame.clear();

            synchronized (bitmap) {

                bitmap.copyPixelsFromBuffer(frame.asReadOnlyBuffer());

                if(!READY_FOR_STREAM){

                    READY_FOR_STREAM = true;
                    toggleStreaming();
                }

                drawOnCanvas(bitmap);
            }
        }
    };

    private void drawOnCanvas(Bitmap bitmap){

        Paint paint = new Paint();

        /* Test: draw 10 frames at 30fps before start
         * these should be dropped and not causing malformed stream.
         */
        try{
            if(canvasSurface == null || !canvasSurface.isValid()) return;
            Canvas canvas = canvasSurface.lockCanvas(null);
            Log.d(TAG, "drawOnCanvas locked");

            canvas.drawBitmap(bitmap, 0, 0, paint);

            canvasSurface.unlockCanvasAndPost(canvas);

        }catch (Exception e) {
            e.printStackTrace();
            return;
        }
    }
}
