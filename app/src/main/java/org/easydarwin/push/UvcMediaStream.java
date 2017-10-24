package org.easydarwin.push;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.media.MediaCodec;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.jiangdg.usbcamera.USBCameraManager;
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
import java.lang.ref.WeakReference;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.IllegalFormatException;
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
    Pusher mEasyPusher;
    static final String TAG = "EasyPusher";
    int framerate, bitrate;
    MediaCodec mMediaCodec;
    WeakReference<SurfaceTexture> mSurfaceHolderRef;
    NV21Convertor mConvertor;
    boolean pushStream = false;//是否要推送数据
    AudioStream audioStream;
    private int mDgree;
    private Context mApplicationContext;
    private VideoConsumer mVC;
    private TxtOverlay overlay;
    private EasyMuxer mMuxer;
    private Handler showToastHandler;
//    private final HandlerThread mCameraThread;
//    private final Handler mCameraThreadHandler;
    private EncoderDebugger debugger;
    private static final int previewFormat = ImageFormat.NV21;
    private static final boolean mSWCodec = false;

    private UVCCameraTextureView cameraView;

    private AbstractUVCCameraHandler.PreviewCallback mPreviewCallback;

    private int frameSize;

//    private int PREVIEW_WIDTH = 640 * 2;
//    private int PREVIEW_HEIGHT = 480 * 2;
    private int PREVIEW_WIDTH = 1280;
    private int PREVIEW_HEIGHT = 720;
    private static final int ENCODER_TYPE = 1;
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

    public UvcMediaStream(Activity activity, final UVCCameraTextureView cameraView, final OnMyDevConnectListener listener) {
        mApplicationContext = activity.getApplicationContext();
        mSurfaceHolderRef = new WeakReference(cameraView.getSurfaceTexture());
        this.cameraView = cameraView;
        init(activity, this.cameraView, listener);
        if (EasyApplication.isRTMP())
            mEasyPusher = new EasyRTMP();
        else
            mEasyPusher = new EasyPusher();

        mDgree = 0;

        frameSize = PREVIEW_WIDTH * PREVIEW_HEIGHT * ImageFormat.getBitsPerPixel(previewFormat) / 8;

        showToastHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == 0) {
                    showShortMsg(msg.obj.toString());
                }
            }
        };

        if (mPreviewCallback == null) {
            mPreviewCallback = new AbstractUVCCameraHandler.PreviewCallback() {
                @Override
                public void onPreviewFrame(ByteBuffer frame) {
                    if (frameSize > frame.capacity()) {
                        return;
                    } else {
                        frame.limit(frameSize);
                    }

                    byte[] data = new byte[frameSize];

                    try {
                        frame.get(data);
                    } catch (IndexOutOfBoundsException e) {
//                        showShortMsg("bytes is larger than buffer");
                    } catch (BufferUnderflowException e) {
//                        showShortMsg("bytes is fewer than buffer");
                    }

//                    if (PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getBoolean("key_enable_video_overlay", false)) {
//                        String txt = "EasyPusher " + new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
//                        overlay.overlay(data, txt);
//                    }

                    mVC.onVideo(data, previewFormat);
                }
            };
        }
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
                stopPreview();
                closeCamera();
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

    public void setGetSupportedSizeListener(AbstractUVCCameraHandler.GetSupportedSizeListener supportedSizeListener) {
        if (mCameraHandler != null) {
            mCameraHandler.setGetSupportedSizeListener(supportedSizeListener);
        }
    }

    /**
     * 更新分辨率
     */
    public void updateResolution(final int w, final int h) {
        PREVIEW_WIDTH = w;
        PREVIEW_HEIGHT = h;
        frameSize = PREVIEW_WIDTH * PREVIEW_HEIGHT * ImageFormat.getBitsPerPixel(previewFormat) / 8;
        mCameraHandler.previewSizeChanged(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        double oldratio = cameraView.getAspectRatio();
        double ratio = PREVIEW_WIDTH / (double) PREVIEW_HEIGHT;
        cameraView.setAspectRatio(ratio);
        stopPreview();
        closeCamera();
        if (ratio == oldratio) {
            openCamera();
            startPreview();
        }
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
        if (mCameraHandler != null) {
            mCameraHandler.startPreview(cameraView.getSurfaceTexture());
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
                mVC.onVideoStart(PREVIEW_WIDTH, PREVIEW_HEIGHT);
                overlay.init(PREVIEW_WIDTH, PREVIEW_HEIGHT, mApplicationContext.getFileStreamPath("SIMYOU.ttf").getPath());
            } catch (IOException ex) {
                ex.printStackTrace();
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            }

            mCameraHandler.setPreviewCallback(mPreviewCallback);
        }
        audioStream = new AudioStream(mEasyPusher);
        audioStream.startRecord();
    }

    @Provides
    @Nullable
    public EasyMuxer getMuxer() {
        return mMuxer;
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
            mCameraHandler.setPreviewCallback(null);
            mCameraHandler.stopPreview();
        }
        if (audioStream != null) {
            audioStream.stop();
            audioStream = null;
        }
        if (mVC != null) {
            mVC.onVideoStop();
            mVC = null;
        }
        if (overlay != null) {
            overlay.release();
            overlay = null;
        }

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
            mCameraHandler.setGetSupportedSizeListener(null);
            mCameraHandler.setPreviewCallback(null);
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

    public void setSurfaceTexture(SurfaceTexture texture) {
        mSurfaceHolderRef = new WeakReference<SurfaceTexture>(texture);
    }

    public boolean isRecording() {
        return mMuxer != null;
    }

    public boolean isCameraOpened(){
        if(mCameraHandler != null){
            return mCameraHandler.isOpened();
        }
        return false;
    }

    /**
     * 是否已注册mUSBMonitor
     * @return
     */
    public boolean isUsbMonitroRegisted() {
        return mUSBMonitor != null ? mUSBMonitor.isRegistered() : false;
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
            throw new IllegalArgumentException("index illegal,should be < devList.size()");
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

    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    private void showShortMsg(String msg) {
        Toast.makeText(mApplicationContext, msg, Toast.LENGTH_SHORT).show();
    }

    private void sendShowToast(String message) {
        if (showToastHandler == null) return;
        Message msg = new Message();
        msg.what = 0;
        msg.obj = message;
        showToastHandler.sendMessage(msg);
    }
}
