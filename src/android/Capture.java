/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/
package org.apache.cordova.mediacapture;

import static org.apache.cordova.mediacapture.FileHelper.AUDIO_3GPP;
import static org.apache.cordova.mediacapture.FileHelper.AUDIO_TYPES;
import static org.apache.cordova.mediacapture.FileHelper.IMAGE_JPEG;
import static org.apache.cordova.mediacapture.FileHelper.VIDEO_3GPP;
import static org.apache.cordova.mediacapture.FileHelper.VIDEO_MP4;
import static org.apache.cordova.mediacapture.FileHelper.queryContentProvider;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginManager;
import org.apache.cordova.file.FileUtils;
import org.apache.cordova.file.LocalFilesystemURL;
import org.apache.cordova.mediacapture.PendingRequests.Request;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Capture extends CordovaPlugin {


    private static final int CAPTURE_AUDIO = 0;     // Constant for capture audio
    private static final int CAPTURE_IMAGE = 1;     // Constant for capture image
    private static final int CAPTURE_VIDEO = 2;     // Constant for capture video
    private static final String LOG_TAG = "Capture";

    // Camera or microphone failed to capture image or sound.
    private static final int CAPTURE_INTERNAL_ERR = 0;
    // Camera application or audio capture application is currently serving other capture request.
    private static final int CAPTURE_APPLICATION_BUSY = 1;
    // Invalid use of the API (e.g. limit parameter has value less than one).
    private static final int CAPTURE_INVALID_ARGUMENT = 2;
    // User exited camera application or audio capture application before capturing anything.
    private static final int CAPTURE_NO_MEDIA_FILES = 3;
    // User denied permissions required to perform the capture request.
    private static final int CAPTURE_PERMISSION_DENIED = 4;
    // The requested capture operation is not supported.
    private static final int CAPTURE_NOT_SUPPORTED = 20;

    private static String[] audioPermissions;

    static {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioPermissions = new String[]{
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            audioPermissions = new String[]{
                    Manifest.permission.RECORD_AUDIO
            };
        }
    }

    private static String[] storagePermissions;

    static {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermissions = new String[]{
            };
        } else {
            storagePermissions = new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
        }
    }

    private boolean cameraPermissionInManifest;     // Whether or not the CAMERA permission is declared in AndroidManifest.xml

    private final PendingRequests pendingRequests = new PendingRequests();

    private int numPics;                            // Number of pictures before capture activity
    private Uri fileUri;

//    public void setContext(Context mCtx)
//    {
//        if (CordovaInterface.class.isInstance(mCtx))
//            cordova = (CordovaInterface) mCtx;
//        else
//            LOG.d(LOG_TAG, "ERROR: You must use the CordovaInterface for this to work correctly. Please implement it in your activity");
//    }

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();

        // CB-10670: The CAMERA permission does not need to be requested unless it is declared
        // in AndroidManifest.xml. This plugin does not declare it, but others may and so we must
        // check the package info to determine if the permission is present.

        cameraPermissionInManifest = false;
        try {
            PackageManager packageManager = this.cordova.getActivity().getPackageManager();
            String[] permissionsInPackage = packageManager.getPackageInfo(this.cordova.getActivity().getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (permissionsInPackage != null) {
                for (String permission : permissionsInPackage) {
                    if (permission.equals(Manifest.permission.CAMERA)) {
                        cameraPermissionInManifest = true;
                        break;
                    }
                }
            }
        } catch (NameNotFoundException e) {
            // We are requesting the info for our package, so this should
            // never be caught
            LOG.e(LOG_TAG, "Failed checking for CAMERA permission in manifest", e);
        }
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "getFormatData": {
                JSONObject obj = getFormatData(args.getString(0), args.getString(1));
                callbackContext.success(obj);
                return true;
            }
            default:
                break;
        }

        JSONObject options = args.optJSONObject(0);

        switch (action) {
            case "captureAudio":
                this.captureAudio(pendingRequests.createRequest(CAPTURE_AUDIO, options, callbackContext));
                break;
            case "captureImage":
                if (!options.has("useInternalCameraApp") || (options.has("useInternalCameraApp") && !options.getBoolean("useInternalCameraApp"))) {
                    //check cordova options
                    options.put("useInternalCameraApp", preferences.getBoolean("useInternalCameraApp", false));
                }
                this.captureImage(pendingRequests.createRequest(CAPTURE_IMAGE, options, callbackContext));
                break;
            case "captureVideo":
                if (!options.has("useInternalCameraApp") || (options.has("useInternalCameraApp") && !options.getBoolean("useInternalCameraApp"))) {
                    //check cordova options
                    options.put("useInternalCameraApp", preferences.getBoolean("useInternalCameraApp", false));
                }
                this.captureVideo(pendingRequests.createRequest(CAPTURE_VIDEO, options, callbackContext));
                break;
            default:
                return false;
        }

        return true;
    }

    /**
     * Provides the media data file data depending on it's mime type
     *
     * @param filePath path to the file
     * @param mimeType of the file
     * @return a MediaFileData object
     */
    private JSONObject getFormatData(String filePath, String mimeType) throws JSONException {
        Uri fileUrl = filePath.startsWith("file:") ? Uri.parse(filePath) : Uri.fromFile(new File(filePath));
        JSONObject obj = new JSONObject();
        // setup defaults
        obj.put("height", 0);
        obj.put("width", 0);
        obj.put("bitrate", 0);
        obj.put("duration", 0);
        obj.put("codecs", "");

        // If the mimeType isn't set the rest will fail
        // so let's see if we can determine it.
        if (mimeType == null || mimeType.equals("") || "null".equals(mimeType)) {
            mimeType = FileHelper.getMimeType(fileUrl, cordova.getContext());
        }
        LOG.d(LOG_TAG, "Mime type = " + mimeType);

        if (mimeType.equals(IMAGE_JPEG) || filePath.endsWith(".jpg")) {
            obj = getImageData(fileUrl, obj);
        } else if (Arrays.asList(AUDIO_TYPES).contains(mimeType)) {
            obj = getAudioVideoData(filePath, obj, false);
        } else if (mimeType.equals(VIDEO_3GPP) || mimeType.equals(VIDEO_MP4)) {
            obj = getAudioVideoData(filePath, obj, true);
        }
        return obj;
    }

    /**
     * Get the Image specific attributes
     *
     * @param fileUrl uri to the file
     * @param obj     represents the Media File Data
     * @return a JSONObject that represents the Media File Data
     * @throws JSONException
     */
    private JSONObject getImageData(Uri fileUrl, JSONObject obj) throws JSONException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(fileUrl.getPath(), options);
        obj.put("height", options.outHeight);
        obj.put("width", options.outWidth);
        return obj;
    }

    /**
     * Get the Image specific attributes
     *
     * @param filePath path to the file
     * @param obj      represents the Media File Data
     * @param video    if true get video attributes as well
     * @return a JSONObject that represents the Media File Data
     * @throws JSONException
     */
    private JSONObject getAudioVideoData(String filePath, JSONObject obj, boolean video) throws JSONException {
        MediaPlayer player = new MediaPlayer();
        try {
            player.setDataSource(filePath);
            player.prepare();
            obj.put("duration", player.getDuration() / 1000);
            if (video) {
                obj.put("height", player.getVideoHeight());
                obj.put("width", player.getVideoWidth());
            }
        } catch (IOException e) {
            LOG.d(LOG_TAG, "Error: loading video file");
        }
        return obj;
    }

    private boolean isMissingPermissions(Request req, ArrayList<String> permissions) {
        ArrayList<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (!PermissionHelper.hasPermission(this, permission)) {
                missingPermissions.add(permission);
            }
        }

        boolean isMissingPermissions = missingPermissions.size() > 0;
        if (isMissingPermissions) {
            String[] missing = missingPermissions.toArray(new String[missingPermissions.size()]);
            PermissionHelper.requestPermissions(this, req.requestCode, missing);
        }
        return isMissingPermissions;
    }

    private boolean isMissingPermissions(Request req, String mediaPermission) {
        ArrayList<String> permissions = new ArrayList<>(Arrays.asList(storagePermissions));
        if (mediaPermission != null && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(mediaPermission);
        }
        return isMissingPermissions(req, permissions);
    }

    private boolean isMissingCameraPermissions(Request req, String mediaPermission) {
        ArrayList<String> cameraPermissions = new ArrayList<>(Arrays.asList(storagePermissions));
        if (cameraPermissionInManifest) {
            cameraPermissions.add(Manifest.permission.CAMERA);
        }
        if (mediaPermission != null && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cameraPermissions.add(mediaPermission);
        }
        return isMissingPermissions(req, cameraPermissions);
    }

    /**
     * Sets up an intent to capture audio.  Result handled by onActivityResult()
     */
    private void captureAudio(Request req) {
        try {
            ArrayList<String> requiredPermissions = new ArrayList<>(
                    storagePermissions.length + audioPermissions.length
            );
            requiredPermissions.addAll(Arrays.asList(audioPermissions));
            requiredPermissions.addAll(Arrays.asList(storagePermissions));
            if (isMissingPermissions(req, requiredPermissions)) return;

            setActivityEnabled(this.cordova.getActivity(), AudioCaptureActivity.class.getCanonicalName(), true);

            Intent intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
            fileUri = FileHelper.getAndCreateFile(MediaStore.Audio.Media.RECORD_SOUND_ACTION, cordova.getActivity());
            intent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);

            PackageManager packageManager = cordova.getActivity().getPackageManager();
            List<ResolveInfo> activities = packageManager.queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);

            if (activities.isEmpty()) {
                LOG.w(LOG_TAG, "No Activity found to handle Audio Capture. Forcing internal.");
                intent = new Intent(cordova.getContext(), CaptureActivity.class);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);
            } else {
                LOG.w(LOG_TAG, String.format("Found Activities: %s", intent.resolveActivity(cordova.getActivity().getPackageManager()).flattenToString()));
            }

            LOG.d(LOG_TAG, "Recording audio and saving to: " + fileUri.toString());
            this.cordova.startActivityForResult(this, intent, req.requestCode);
        } catch (ActivityNotFoundException ex) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NOT_SUPPORTED, "No Activity found to handle Audio Capture."));
        } catch (Exception e) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_INTERNAL_ERR, "Canceled."));
        }

    }

    /**
     * Sets up an intent to capture images.  Result handled by onActivityResult()
     */
    private void captureImage(Request req) {
        if (isMissingCameraPermissions(req, Manifest.permission.READ_MEDIA_IMAGES)) return;

        // Save the number of images currently on disk for later
        Cursor cursor = queryImgDB(FileHelper.isExternalContentStore()
                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        this.numPics = cursor.getCount();
        cursor.close();

        try {
            fileUri = FileHelper.getAndCreateFile(MediaStore.ACTION_IMAGE_CAPTURE, cordova.getActivity());
        } catch (IllegalArgumentException e) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_INTERNAL_ERR, "error creating file"));
        }

        Intent intent = req.useInternalCameraApp
                ? new Intent(MediaStore.ACTION_IMAGE_CAPTURE, fileUri, cordova.getContext(), CaptureActivity.class)
                : new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);

        LOG.d(LOG_TAG, "Taking a picture and saving to: " + fileUri.toString());
        setActivityEnabled(this.cordova.getActivity(), CaptureActivity.class.getCanonicalName(), true);
        this.cordova.startActivityForResult(this, intent, req.requestCode);
    }

    private static void createWritableFile(File file) throws IOException {
        file.createNewFile();
        file.setWritable(true, false);
    }

    /**
     * Sets up an intent to capture video.  Result handled by onActivityResult()
     */
    private void captureVideo(Request req) {
        if (isMissingCameraPermissions(req, Manifest.permission.READ_MEDIA_VIDEO)) return;

        try {
            fileUri = FileHelper.getAndCreateFile(MediaStore.ACTION_VIDEO_CAPTURE, cordova.getActivity());
        } catch (IllegalArgumentException e) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_INTERNAL_ERR, "Canceled."));
        }

        Intent intent = req.useInternalCameraApp
                ? new Intent(MediaStore.ACTION_VIDEO_CAPTURE, fileUri, cordova.getActivity(), CaptureActivity.class)
                : new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);

        intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, req.duration);
        intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, req.quality);

        LOG.d(LOG_TAG, "Taking a video and saving to: " + fileUri.toString());
        setActivityEnabled(this.cordova.getActivity(), CaptureActivity.class.getCanonicalName(), true);
        this.cordova.startActivityForResult(this, intent, req.requestCode);
    }

    /**
     * Called when the video view exits.
     *
     * @param requestCode The request code originally supplied to startActivityForResult(),
     *                    allowing you to identify who this result came from.
     * @param resultCode  The integer result code returned by the child activity through its setResult().
     * @param intent      An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     * @throws JSONException
     */
    public void onActivityResult(int requestCode, int resultCode, final Intent intent) {
        final Request req = pendingRequests.get(requestCode);

        //NOTE: move or uncomment this to debug activity results
		if (CAPTURE_IMAGE == req.action || CAPTURE_VIDEO == req.action) {
			//disable ImageActivity alias to prevent other apps using this one
			setActivityEnabled(this.cordova.getActivity(), CaptureActivity.class.getCanonicalName(), false);
		} else if (CAPTURE_AUDIO == req.action) {
			setActivityEnabled(this.cordova.getActivity(), AudioCaptureActivity.class.getCanonicalName(), false);
		}

        // Result received okay
        if (resultCode == Activity.RESULT_OK) {
            Runnable processActivityResult = new Runnable() {
                @Override
                public void run() {
                    switch (req.action) {
                        case CAPTURE_AUDIO:
                            onAudioActivityResult(req, intent);
                            break;
                        case CAPTURE_IMAGE:
                            onImageActivityResult(req, intent);
                            break;
                        case CAPTURE_VIDEO:
                            onVideoActivityResult(req, intent);
                            break;
                    }
                }
            };

            this.cordova.getThreadPool().execute(processActivityResult);
            return;
        }

		// There is no image captured so delete file
        Capture.this.cordova.getActivity().getContentResolver().delete(fileUri, null, null);

        // If canceled
        if (resultCode == Activity.RESULT_CANCELED) {
            // If we have partial results send them back to the user
            if (req.results.length() > 0) {
                pendingRequests.resolveWithSuccess(req);
            }
            // user canceled the action
            else {
                pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NO_MEDIA_FILES, "Canceled."));
            }

            return;
        }

        // If something else
        // If we have partial results send them back to the user
        if (req.results.length() > 0) {
            pendingRequests.resolveWithSuccess(req);
        }
        // something bad happened
        else {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NO_MEDIA_FILES, "Did not complete!"));
        }
    }


    public void onAudioActivityResult(Request req, Intent intent) {
        // Create a file object from the uri
        JSONObject mediaFile = createMediaFile(intent);

        if (mediaFile == null) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_INTERNAL_ERR, "Error: no mediaFile created from " + fileUri));
            return;
        }

        req.results.put(mediaFile);

        if (req.results.length() >= req.limit) {
            // Send Uri back to JavaScript for listening to audio
            pendingRequests.resolveWithSuccess(req);
        } else {
            // still need to capture more audio clips
            captureAudio(req);
        }
    }

    public void onImageActivityResult(Request req, Intent intent) {
        // Create a file object from the uri
        JSONObject mediaFile = createMediaFile(intent);

        if (mediaFile == null) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_INTERNAL_ERR, "Error: no mediaFile created from " + fileUri));
            return;
        }

        // Add image to results
        req.results.put(mediaFile);

        checkForDuplicateImage();

        if (req.results.length() >= req.limit) {
            // Send Uri back to JavaScript for viewing image
            pendingRequests.resolveWithSuccess(req);
        } else {
            // still need to capture more images
            captureImage(req);
        }
    }

    public void onVideoActivityResult(Request req, Intent intent) {
        // Create a file object from the uri
        JSONObject mediaFile = createMediaFile(intent);

        if (mediaFile == null) {
            pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_INTERNAL_ERR, "Error: no mediaFile created from " + fileUri));
            return;
        }

        req.results.put(mediaFile);

        if (req.results.length() >= req.limit) {
            // Send Uri back to JavaScript for viewing video
            pendingRequests.resolveWithSuccess(req);
        } else {
            // still need to capture more video clips
            captureVideo(req);
        }
    }


    /**
     * Creates a JSONObject that represents a File from the Uri
     *
     * @param intent result from the intent
     * @return a JSONObject that represents a File
     */
    public JSONObject createMediaFile(@Nullable Intent intent) {
        Context context = cordova.getContext();

        ContentResolver contentResolver = context.getContentResolver();
        //check if intent data uri overrides intended fileUri
        if (intent != null && intent.getData() != null && !fileUri.equals(intent.getData())) {
            //remove fileUri from mediaStore
            contentResolver.delete(fileUri, null, null);
            //register new uri
            fileUri = intent.getData();
            MediaScannerConnection.scanFile(
                    context, new String[]{fileUri.getPath()}, null,
                    (path, uri) -> LOG.i(LOG_TAG, "onScanCompleted"));
        }

        JSONObject obj = new JSONObject();

        File fp = webView.getResourceApi().mapUriToFile(fileUri);

        Class webViewClass = webView.getClass();
        PluginManager pm = null;
        try {
            Method gpm = webViewClass.getMethod("getPluginManager");
            pm = (PluginManager) gpm.invoke(webView);
        } catch (NoSuchMethodException e) {
        } catch (IllegalAccessException e) {
        } catch (InvocationTargetException e) {
        }
        if (pm == null) {
            try {
                Field pmf = webViewClass.getField("pluginManager");
                pm = (PluginManager) pmf.get(webView);
            } catch (NoSuchFieldException e) {
            } catch (IllegalAccessException e) {
            }
        }

        try {
            FileUtils filePlugin = (FileUtils) pm.getPlugin("File");
            LocalFilesystemURL url = filePlugin.filesystemURLforLocalPath(fp.getAbsolutePath());

            // File properties
            obj.put("name", fp.getName());
            obj.put("fullPath", Uri.fromFile(fp));
            if (url != null) {
                obj.put("localURL", url.toString());
            }
            // Because of an issue with MimeTypeMap.getMimeTypeFromExtension() all .3gpp files
            // are reported as video/3gpp. I'm doing this hacky check of the URI to see if it
            // is stored in the audio or video content store.
            if (fp.getAbsoluteFile().toString().endsWith(".3gp") || fp.getAbsoluteFile().toString().endsWith(".3gpp")) {
                if (fileUri.toString().contains("/audio/")) {
                    obj.put("type", AUDIO_3GPP);
                } else {
                    obj.put("type", VIDEO_3GPP);
                }
            } else {
                obj.put("type", FileHelper.getMimeType(Uri.fromFile(fp), context));
            }

            obj.put("lastModifiedDate", fp.lastModified());
            obj.put("size", fp.length());
        } catch (Exception e) {
            e.printStackTrace();
            obj = null;
        }

        if (obj == null) {
            try {
                obj = queryContentProvider(context, fileUri, 0);

            } catch (Exception e) {
                LOG.e(LOG_TAG, "Error: no mediaFile created from " + fileUri);
            }
        }

        return obj;
    }

    private JSONObject createErrorObject(int code, String message) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("code", code);
            obj.put("message", message);
        } catch (JSONException e) {
            // This will never happen
        }
        return obj;
    }

    /**
     * Creates a cursor that can be used to determine how many images we have.
     *
     * @return a cursor
     */
    private Cursor queryImgDB(Uri contentStore) {
        return this.cordova.getActivity().getContentResolver().query(
                contentStore,
                new String[]{MediaStore.Images.Media._ID},
                null,
                null,
                null);
    }

    /**
     * Used to find out if we are in a situation where the Camera Intent adds to images
     * to the content store.
     */
    private void checkForDuplicateImage() {
        Uri contentStore = FileHelper.isExternalContentStore() ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.INTERNAL_CONTENT_URI;
        Cursor cursor = queryImgDB(contentStore);
        int currentNumOfImages = cursor.getCount();

        // delete the duplicate file if the difference is 2
        if ((currentNumOfImages - numPics) == 2) {
            cursor.moveToLast();
            int index = cursor.getColumnIndex(MediaStore.Images.Media._ID);
            if (index >= 0) {
                int id = cursor.getInt(index) - 1;
                Uri uri = Uri.parse(contentStore + "/" + id);
                this.cordova.getActivity().getContentResolver().delete(uri, null, null);
            }
        }
        cursor.close();
    }
    private void executeRequest(Request req) {
        switch (req.action) {
            case CAPTURE_AUDIO:
                this.captureAudio(req);
                break;
            case CAPTURE_IMAGE:
                this.captureImage(req);
                break;
            case CAPTURE_VIDEO:
                this.captureVideo(req);
                break;
        }
    }

    public void onRequestPermissionResult(int requestCode, String[] permissions,
                                          int[] grantResults) throws JSONException {
        Request req = pendingRequests.get(requestCode);

        if (req != null) {
            boolean success = true;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_DENIED) {
                    success = false;
                    break;
                }
            }

            if (success) {
                executeRequest(req);
            } else {
                pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_PERMISSION_DENIED, "Permission denied."));
            }
        }
    }

    public Bundle onSaveInstanceState() {
        return pendingRequests.toBundle();
    }

    public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
        pendingRequests.setLastSavedState(state, callbackContext);
    }

    /**
     * Enables/Disables activity. Default is disabled. Prevents other apps to call the
     * activity but enabling it before calling startActivity will cause android to make it choosable by user
     *
     * @param activity activity containing the target package
     * @param enabled  sets activity android:enabled for CaptureImageActivity (default false)
     */
    public static void setActivityEnabled(Activity activity, String targetActivity, boolean enabled) {
        String packageName = activity.getPackageName();
        PackageManager pm = activity.getApplicationContext().getPackageManager();

        ComponentName activityAlias = new ComponentName(packageName, targetActivity);
        if (enabled) {
            pm.setComponentEnabledSetting(
                    activityAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } else {
            pm.setComponentEnabledSetting(
                    activityAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        LOG.i(LOG_TAG, String.format("%s %s", enabled ? "enabled" : "disabled", activityAlias));
    }

}
