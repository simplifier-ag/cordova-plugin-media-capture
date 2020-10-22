package org.apache.cordova.mediacapture;

import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;

public class CaptureActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("camera_activity", "layout", getPackageName()));
        if (savedInstanceState == null && getIntent() != null && getIntent().getAction() != null) {
            if (getIntent().getAction().equals(MediaStore.ACTION_IMAGE_CAPTURE)) {
                //getSupportFragmentManager().beginTransaction()
                //        .replace(getResources().getIdentifier("container", "id", getPackageName()), CameraFragment.newInstance())
                //        .commit();
            } else if (getIntent().getAction().equals(MediaStore.ACTION_VIDEO_CAPTURE)) {
                //TODO Record video
            }
        }
    }
}