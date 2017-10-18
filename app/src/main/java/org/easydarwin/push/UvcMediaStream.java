package org.easydarwin.push;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.common.AbstractUVCCameraHandler;
import com.serenegiant.usb.common.UVCCameraHandler;
import com.serenegiant.usb.widget.CameraViewInterface;
import com.serenegiant.usb.widget.UVCCameraTextureView;

import org.easydarwin.audio.AudioStream;
import org.easydarwin.bus.SupportResolution;
import org.easydarwin.easypusher.BuildConfig;
import org.easydarwin.easypusher.EasyApplication;
import org.easydarwin.easypusher.R;
import org.easydarwin.easyrtmp.push.EasyRTMP;
import org.easydarwin.hw.EncoderDebugger;
import org.easydarwin.hw.NV21Convertor;
import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.sw.TxtOverlay;
import org.easydarwin.util.Util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import dagger.Module;
import dagger.Provides;

import static org.easydarwin.easypusher.EasyApplication.BUS;

/**
 * Created by Zheming.xin on 2017/10/17.
 */

@Module
public class UvcMediaStream {
    private static final boolean VERBOSE = BuildConfig.DEBUG;
    private static final int SWITCH_CAMERA = 11;
    Pusher mEasyPusher;
    static final String TAG = "EasyPusher";
    int width = 640, height = 480;
    int framerate, bitrate;
//    int mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    MediaCodec mMediaCodec;
    WeakReference<SurfaceTexture> mSurfaceHolderRef;
//    Camera mCamera;
    NV21Convertor mConvertor;
    boolean pushStream = false;//是否要推送数据
    AudioStream audioStream;
    private boolean isCameraBack = true;
    private int mDgree;
    private Context mApplicationContext;
    private boolean mSWCodec;
    private VideoConsumer mVC;
    private TxtOverlay overlay;
    private EasyMuxer mMuxer;
//    private final HandlerThread mCameraThread;
//    private final Handler mCameraThreadHandler;
    private EncoderDebugger debugger;
    private int previewFormat;

    private UVCCameraTextureView cameraView;

    public UvcMediaStream(Activity activity, UVCCameraTextureView cameraView, final OnMyDevConnectListener listener) {
        mApplicationContext = activity.getApplicationContext();
        mSurfaceHolderRef = new WeakReference(cameraView.getSurfaceTexture());
        this.cameraView = cameraView;
        init(activity, this.cameraView, listener);
        if (EasyApplication.isRTMP())
            mEasyPusher = new EasyRTMP();
        else mEasyPusher = new EasyPusher();
//        mCameraThread = new HandlerThread("CAMERA"){
//            public void run(){
//                try{
//                    super.run();
//                } finally {
//                    stopStream();
//                    closeCamera();
//                }
//            }
//        };
//        mCameraThread.start();
//        mCameraThreadHandler = new Handler(mCameraThread.getLooper());

//        if (enableVideo) {
//            previewCallback = new Camera.PreviewCallback() {
//
//                @Override
//                public void onPreviewFrame(byte[] data, Camera camera) {
//                    if (mDgree == 0) {
//                        Camera.CameraInfo camInfo = new Camera.CameraInfo();
//                        Camera.getCameraInfo(mCameraId, camInfo);
//                        int cameraRotationOffset = camInfo.orientation;
//
//                        if (cameraRotationOffset % 180 != 0) {
//                            if (previewFormat == ImageFormat.YV12) {
//                                yuvRotate(data, 0, width, height, cameraRotationOffset);
//                            } else {
//                                yuvRotate(data, 1, width, height, cameraRotationOffset);
//                            }
//                        }
//                        save2file(data, String.format("/sdcard/yuv_%d_%d.yuv", height, width));
//                    }
//                    if (PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getBoolean("key_enable_video_overlay", false)) {
//                        String txt = String.format("drawtext=fontfile=" + mApplicationContext.getFileStreamPath("SIMYOU.ttf") + ": text='%s%s':x=(w-text_w)/2:y=H-60 :fontcolor=white :box=1:boxcolor=0x00000000@0.3", "EasyPusher", new SimpleDateFormat("yyyy-MM-ddHHmmss").format(new Date()));
//                        txt = "EasyPusher " + new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
//                        overlay.overlay(data, txt);
//                    }
//                    mVC.onVideo(data, previewFormat);
//                    mCamera.addCallbackBuffer(data);
//                }
//
//            };
//        }
    }

    public void startStream(String url, InitCallback callback) {
        if (PreferenceManager.getDefaultSharedPreferences(EasyApplication.getEasyApplication()).getBoolean(EasyApplication.KEY_ENABLE_VIDEO, true))
            mEasyPusher.initPush(url, mApplicationContext, callback);
        else
            mEasyPusher.initPush(url, mApplicationContext, callback, ~0);
        pushStream = true;
    }

    public void startStream(String ip, String port, String id, InitCallback callback) {
        mEasyPusher.initPush(ip, port, String.format("%s.sdp", id), mApplicationContext, callback);
        pushStream = true;
    }

    public void setDgree(int dgree) {
        mDgree = dgree;
    }

    /**
     * 更新分辨率
     */
    public void updateResolution(final int w, final int h) {
        stopPreview();
        closeCamera();
//        mCameraThreadHandler.post(new Runnable() {
//            @Override
//            public void run() {
//                width = w;
//                height = h;
//            }
//        });
        openCamera();
        startPreview();
    }


    public static int[] determineMaximumSupportedFramerate(Camera.Parameters parameters) {
        int[] maxFps = new int[]{0, 0};
        List<int[]> supportedFpsRanges = parameters.getSupportedPreviewFpsRange();
        for (Iterator<int[]> it = supportedFpsRanges.iterator(); it.hasNext(); ) {
            int[] interval = it.next();
            if (interval[1] > maxFps[1] || (interval[0] > maxFps[0] && interval[1] == maxFps[1])) {
                maxFps = interval;
            }
        }
        return maxFps;
    }

    private void save2file(byte[] data, String path) {
        if (true) return;
        try {
            FileOutputStream fos = new FileOutputStream(path, true);
            fos.write(data);
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 根据Unicode编码完美的判断中文汉字和符号
    private static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION) {
            return true;
        }
        return false;
    }

    private int getTxtPixelLength(String txt, boolean zoomed) {
        int length = 0;
        int fontWidth = zoomed ? 16 : 8;
        for (int i = 0; i < txt.length(); i++) {
            length += isChinese(txt.charAt(i)) ? fontWidth * 2 : fontWidth;
        }
        return length;
    }

    public synchronized void startRecord() {
//        if (Thread.currentThread() != mCameraThread) {
//            mCameraThreadHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    startRecord();
//                }
//            });
//            return;
//        }
        long millis = PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getInt("record_interval", 300000);
        mMuxer = new EasyMuxer(new File(recordPath, new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date())).toString(), millis);
        if (mVC == null || audioStream == null) {
            throw new IllegalStateException("you need to start preview before startRecord!");
        }
        mVC.setMuxer(mMuxer);
        audioStream.setMuxer(mMuxer);
    }


    public synchronized void stopRecord() {
//        if (Thread.currentThread() != mCameraThread) {
//            mCameraThreadHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    stopRecord();
//                }
//            });
//            return;
//        }
        if (mVC == null || audioStream == null) {
//            nothing
        } else {
            mVC.setMuxer(null);
            audioStream.setMuxer(null);
        }
        if (mMuxer != null) mMuxer.release();
        mMuxer = null;
    }

    /**
     * 开启预览
     */
    public synchronized void startPreview() {
//        if (Thread.currentThread() != mCameraThread) {
//            mCameraThreadHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    startPreview();
//                }
//            });
//            return;
//        }
        if (!cameraView.isAvailable()) {
            throw new RuntimeException("texture view is not available");
        }

        SurfaceTexture st = cameraView.getSurfaceTexture();
        if (st == null) {
            throw new NullPointerException("SurfaceTexture should not be null");
        }

        if (mCameraHandler != null) {
            Log.d(TAG, "start camera Preview");
            showShortMsg("start camera Preview");
            mCameraHandler.startPreview(cameraView.getSurfaceTexture());

            boolean rotate = false;
            if (Util.getSupportResolution(mApplicationContext).size() == 0) {
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(PREVIEW_WIDTH + "x" + PREVIEW_HEIGHT).append(";");
                Util.saveSupportResolution(mApplicationContext, stringBuilder.toString());
            }
            BUS.post(new SupportResolution());

            overlay = new TxtOverlay(mApplicationContext);
            try {
                if (mSWCodec) {
                    mVC = new SWConsumer(mApplicationContext, mEasyPusher);
                } else {
                    mVC = new HWConsumer(mApplicationContext, mEasyPusher);
                }
                if (!rotate) {
                    mVC.onVideoStart(PREVIEW_WIDTH, PREVIEW_HEIGHT);
                    overlay.init(PREVIEW_WIDTH, PREVIEW_HEIGHT, mApplicationContext.getFileStreamPath("SIMYOU.ttf").getPath());
                } else {
                    mVC.onVideoStart(PREVIEW_HEIGHT, PREVIEW_WIDTH);
                    overlay.init(PREVIEW_HEIGHT, PREVIEW_WIDTH, mApplicationContext.getFileStreamPath("SIMYOU.ttf").getPath());
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            }
        }
        Log.d(TAG, "start Audio Record");
        audioStream = new AudioStream(mEasyPusher);
        audioStream.startRecord();
    }

    @Provides
    @Nullable
    public EasyMuxer getMuxer() {
        return mMuxer;
    }

    Camera.PreviewCallback previewCallback;

    /**
     * 旋转YUV格式数据
     *
     * @param src    YUV数据
     * @param format 0，420P；1，420SP
     * @param width  宽度
     * @param height 高度
     * @param degree 旋转度数
     */
    private static void yuvRotate(byte[] src, int format, int width, int height, int degree) {
        int offset = 0;
        if (format == 0) {
            JNIUtil.rotateMatrix(src, offset, width, height, degree);
            offset += (width * height);
            JNIUtil.rotateMatrix(src, offset, width / 2, height / 2, degree);
            offset += width * height / 4;
            JNIUtil.rotateMatrix(src, offset, width / 2, height / 2, degree);
        } else if (format == 1) {
            JNIUtil.rotateMatrix(src, offset, width, height, degree);
            offset += width * height;
            JNIUtil.rotateShortMatrix(src, offset, width / 2, height / 2, degree);
        }
    }

    /**
     * 停止预览
     */
    public synchronized void stopPreview() {
//        if (Thread.currentThread() != mCameraThread) {
//            mCameraThreadHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    stopPreview();
//                }
//            });
//            return;
//        }
        if(mCameraHandler != null){
            mCameraHandler.stopPreview();
            Log.i(TAG,"StopPreview");
        }
        if (audioStream != null) {
            audioStream.stop();
            Log.i(TAG,"Stop AudioStream");
            audioStream = null;
        }
        if (mVC != null) {
            mVC.onVideoStop();

            Log.i(TAG,"Stop VC");
        }
        if (overlay != null)
            overlay.release();

        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
    }

    private String recordPath = Environment.getExternalStorageDirectory().getPath();

    public void setRecordPath(String recordPath) {
        this.recordPath = recordPath;
    }

    public boolean isStreaming() {
        return pushStream;
    }

    public void stopStream() {
        mEasyPusher.stop();
        pushStream = false;
    }

    public void setSurfaceTexture(SurfaceTexture texture) {
        mSurfaceHolderRef = new WeakReference<SurfaceTexture>(texture);
    }

    public boolean isRecording() {
        return mMuxer != null;
    }

    public static final String ROOT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath()
            + File.separator;
    public static final String SUFFIX_PNG = ".png";
    public static final String SUFFIX_MP4 = ".mp4";
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private static final int ENCODER_TYPE = 1;
    //0为YUYV，1为MJPEG
    private static final int PREVIEW_FORMAT = 1;

    // USB设备管理类
    private USBMonitor mUSBMonitor;
    // Camera业务逻辑处理
    private UVCCameraHandler mCameraHandler;

    private Context mContext;

    //request camera request
    private boolean isCameraRequest = false;

    private USBMonitor.UsbControlBlock currentCtrlBlock = null;

    public interface OnMyDevConnectListener{
        void onAttachDev(UsbDevice device);
        void onDettachDev(UsbDevice device);
        void onConnectDev(UsbDevice device);
        void onDisConnectDev(UsbDevice device);
    }

    /** 初始化
     *
     *  context  上下文
     *  cameraView Camera要渲染的Surface
     *  listener USB设备检测与连接状态事件监听器
     * */
    private void init(Activity activity, CameraViewInterface cameraView, final OnMyDevConnectListener listener){
        if(cameraView == null)
            throw new NullPointerException("CameraViewInterface cannot be null!");
        mContext = activity.getApplicationContext();

        showShortMsg("Usb Manager init");

        mUSBMonitor = new USBMonitor(activity.getApplicationContext(), new USBMonitor.OnDeviceConnectListener() {
            // 当检测到USB设备，被回调
            @Override
            public void onAttach(UsbDevice device) {
                if(getUsbDeviceCount() == 0){
                    showShortMsg("未检测到USB摄像头设备");
                    return;
                }
                // 请求打开摄像头
                if(!isCameraRequest){
                    isCameraRequest = true;
                    requestPermission(0);
                }
                if(listener != null){
                    listener.onAttachDev(device);
                }
            }

            // 当拨出或未检测到USB设备，被回调
            @Override
            public void onDettach(UsbDevice device) {
                if(isCameraRequest){
                    // 关闭摄像头
                    isCameraRequest = false;
                    showShortMsg(device.getDeviceName()+"已拨出");
                }
                if(listener != null){
                    listener.onDettachDev(device);
                }
            }

            // 当连接到USB Camera时，被回调
            @Override
            public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                showShortMsg(device.getDeviceName()+"已连接");
                currentCtrlBlock = ctrlBlock;
                // 打开摄像头
                openCamera();
                // 开启预览
                startPreview();
                if(listener != null){
                    listener.onConnectDev(device);
                }
            }

            // 当与USB Camera断开连接时，被回调
            @Override
            public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                // 关闭摄像头
                showShortMsg(device.getDeviceName()+"已断开");
                stopPreview();
                closeCamera();
                if(listener != null){
                    listener.onDisConnectDev(device);
                }
            }

            @Override
            public void onCancel(UsbDevice device) {
            }
        });

        // 设置长宽比
        cameraView.setAspectRatio(PREVIEW_WIDTH / (float)PREVIEW_HEIGHT);
        mCameraHandler = UVCCameraHandler.createHandler(activity,cameraView,ENCODER_TYPE,
                PREVIEW_WIDTH,PREVIEW_HEIGHT,PREVIEW_FORMAT);
    }

    /**
     * 是否已注册mUSBMonitor
     * @return
     */
    public boolean isUsbMonitroRegisted() {
        return mUSBMonitor.isRegistered();
    }

    /**
     * 注册检测USB设备广播接收器
     * */
    public void registerUSB(){
        if(mUSBMonitor != null){
            mUSBMonitor.register();
        }
    }

    /**
     *  注销检测USB设备广播接收器
     */
    public void unregisterUSB(){
        if(mUSBMonitor != null){
            mUSBMonitor.unregister();
        }
    }

    /**
     *  请求开启第index USB摄像头
     */
    public void requestPermission(int index){
        List<UsbDevice> devList = getUsbDeviceList();
        if(devList==null || devList.size() ==0){
            return;
        }
        int count = devList.size();
        if(index >= count)
            new IllegalArgumentException("index illegal,should be < devList.size()");
        if(mUSBMonitor != null) {
            mUSBMonitor.requestPermission(getUsbDeviceList().get(index));
        }
    }

    /**
     * 返回
     * */
    public int getUsbDeviceCount(){
        List<UsbDevice> devList = getUsbDeviceList();
        if(devList==null || devList.size() ==0){
            return 0;
        }
        return devList.size();
    }

    private List<UsbDevice> getUsbDeviceList(){
        List<DeviceFilter> deviceFilters = DeviceFilter.getDeviceFilters(mContext, R.xml.device_filter);
        if(mUSBMonitor == null || deviceFilters == null)
            return null;
        return mUSBMonitor.getDeviceList(deviceFilters.get(0));
    }

/*
    public void capturePicture(String savePath){
        if(mCameraHandler != null && mCameraHandler.isOpened()){
            mCameraHandler.captureStill(savePath);
        }
    }

    public void startRecording(String videoPath, AbstractUVCCameraHandler.OnEncodeResultListener listener){
        if(mCameraHandler != null && ! isRecording()){
            mCameraHandler.startRecording(videoPath,listener);
        }
    }

    public void stopRecording(){
        if(mCameraHandler != null && isRecording()){
            mCameraHandler.stopRecording();
        }
    }

    public boolean isRecording(){
        if(mCameraHandler != null){
            return mCameraHandler.isRecording();
        }
        return false;
    }*/

    public boolean isCameraOpened(){
        if(mCameraHandler != null){
            return mCameraHandler.isOpened();
        }
        return false;
    }

    /**
     * 释放资源
     * */
    public void release(){
        // 关闭摄像头
        closeCamera();
        //释放CameraHandler占用的相关资源
        if(mCameraHandler != null){
            mCameraHandler.release();
            mCameraHandler = null;
        }
        // 释放USBMonitor占用的相关资源
        if(mUSBMonitor != null){
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
//            mCameraThread.quitSafely();
//        } else {
//            if (!mCameraThreadHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    mCameraThread.quit();
//                }
//            })) {
//                mCameraThread.quit();
//            }
//        }
//        try {
//            mCameraThread.join();
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
    }

    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }


    public void closeCamera() {
//        if (Thread.currentThread() != mCameraThread) {
//            mCameraThreadHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    closeCamera();
//                }
//            });
//            return;
//        }
        if(mCameraHandler != null){
            mCameraHandler.close();
        }
        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
    }

    public void openCamera() {
//        if (Thread.currentThread() != mCameraThread) {
//            mCameraThreadHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
//                    openCamera();
//                }
//            });
//            return;
//        }
        if(mCameraHandler != null){
            mCameraHandler.open(currentCtrlBlock);
        }
    }

    private void showShortMsg(String msg) {
        Toast.makeText(mApplicationContext, msg, Toast.LENGTH_SHORT).show();
    }
}
