/*
	Copyright (c) 2013-2016 EasyDarwin.ORG.  All rights reserved.
	Github: https://github.com/EasyDarwin
	WEChat: EasyDarwin
	Website: http://www.easydarwin.org
*/

package org.easydarwin.easypusher;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.easydarwin.config.Config;

public class SettingActivity extends AppCompatActivity {

    private static final boolean TEST_ = true;

    public static final int REQUEST_OVERLAY_PERMISSION = 1004;
    public static final String KEY_ENABLE_BACKGROUND_CAMERA = "key_enable_background_camera";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        final EditText txtIp = (EditText) findViewById(R.id.edt_server_address);
        final EditText txtPort = (EditText) findViewById(R.id.edt_server_port);
        final EditText txtId = (EditText) findViewById(R.id.edt_stream_id);
        final View rtspGroup = findViewById(R.id.rtsp_group);
        final EditText rtmpUrl = (EditText) findViewById(R.id.rtmp_url);
        if (BuildConfig.FLAVOR.equals("rtmp")) {
            rtspGroup.setVisibility(View.GONE);
            rtmpUrl.setVisibility(View.VISIBLE);
        }else{
            rtspGroup.setVisibility(View.VISIBLE);
            rtmpUrl.setVisibility(View.GONE);
        }
        String ip = EasyApplication.getEasyApplication().getIp();
        String port = EasyApplication.getEasyApplication().getPort();
        String id = EasyApplication.getEasyApplication().getId();

        txtIp.setText(ip);
        txtPort.setText(port);
        txtId.setText(id);

        rtmpUrl.setText(EasyApplication.getEasyApplication().getUrl());
        Button btnSave = (Button) findViewById(R.id.btn_save);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ipValue = txtIp.getText().toString().trim();
                String portValue = txtPort.getText().toString().trim();
                String idValue = txtId.getText().toString().trim();
                String url = rtmpUrl.getText().toString().trim();

                if (TextUtils.isEmpty(ipValue)) {
                    ipValue = Config.DEFAULT_SERVER_IP;
                }

                if (TextUtils.isEmpty(portValue)) {
                    portValue = Config.DEFAULT_SERVER_PORT;
                }

                if (TextUtils.isEmpty(idValue)) {
                    idValue = Config.DEFAULT_STREAM_ID;
                }

                if (TextUtils.isEmpty(url)) {
                    url = Config.DEFAULT_SERVER_URL;
                }

                EasyApplication.getEasyApplication().saveStringIntoPref(Config.SERVER_IP, ipValue);
                EasyApplication.getEasyApplication().saveStringIntoPref(Config.SERVER_PORT, portValue);
                EasyApplication.getEasyApplication().saveStringIntoPref(Config.STREAM_ID, idValue);
                EasyApplication.getEasyApplication().saveStringIntoPref(Config.SERVER_URL, url);

                finish();
            }
        });


        CheckBox backgroundPushing = (CheckBox) findViewById(R.id.enable_background_camera_pushing);
        backgroundPushing.setChecked(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(KEY_ENABLE_BACKGROUND_CAMERA, false));

        backgroundPushing.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton buttonView, boolean isChecked) {
                if (isChecked){
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (!Settings.canDrawOverlays(SettingActivity.this)) {

                                new AlertDialog.Builder(SettingActivity.this).setTitle("后台上传视频").setMessage("后台上传视频需要APP出现在顶部.是否确定?").setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i) {

                                        final Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + BuildConfig.APPLICATION_ID));
                                        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                                    }
                                }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialogInterface, int i) {
                                        PreferenceManager.getDefaultSharedPreferences(SettingActivity.this).edit()
                                                .putBoolean(KEY_ENABLE_BACKGROUND_CAMERA, false).apply();
                                        buttonView.toggle();
                                    }
                                }).setCancelable(false).show();
                            }
                        }else {
                            PreferenceManager.getDefaultSharedPreferences(SettingActivity.this).edit().putBoolean(KEY_ENABLE_BACKGROUND_CAMERA, true).apply();
                        }
                }else {
                    PreferenceManager.getDefaultSharedPreferences(SettingActivity.this).edit().putBoolean(KEY_ENABLE_BACKGROUND_CAMERA, false).apply();
                }
            }
        });


        CheckBox x264enc = (CheckBox) findViewById(R.id.use_x264_encode);
        x264enc.setChecked(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("key-sw-codec", false));

        x264enc.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PreferenceManager.getDefaultSharedPreferences(SettingActivity.this).edit().putBoolean("key-sw-codec", isChecked).apply();
            }
        });

        TextView versionCode = (TextView) findViewById(R.id.txt_version);
        versionCode.setText("关于" + getString(R.string.app_name) );


        CheckBox enable_video_overlay = (CheckBox) findViewById(R.id.enable_video_overlay);
        enable_video_overlay.setChecked(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("key_enable_video_overlay", false));

        enable_video_overlay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PreferenceManager.getDefaultSharedPreferences(SettingActivity.this).edit().putBoolean("key_enable_video_overlay", isChecked).apply();
            }
        });

        CheckBox only_push_audio = (CheckBox) findViewById(R.id.only_push_audio);
        only_push_audio.setChecked(!PreferenceManager.getDefaultSharedPreferences(this).getBoolean(EasyApplication.KEY_ENABLE_VIDEO, true));

        only_push_audio.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PreferenceManager.getDefaultSharedPreferences(SettingActivity.this).edit().putBoolean(EasyApplication.KEY_ENABLE_VIDEO, !isChecked).apply();
            }
        });
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    public void onOpenLocalRecord(View view) {
        startActivity(new Intent(this, MediaFilesActivity.class));
    }

    public void onAbut(View view) {

        startActivity(new Intent(this, AboutActivity.class));
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean canDraw = Settings.canDrawOverlays(this);
                PreferenceManager.getDefaultSharedPreferences(SettingActivity.this).edit()
                        .putBoolean(KEY_ENABLE_BACKGROUND_CAMERA, canDraw).apply();
                if (!canDraw){
                    CheckBox backgroundPushing = (CheckBox) findViewById(R.id.enable_background_camera_pushing);
                    backgroundPushing.setChecked(false);
                }
            }
        }
    }
}
