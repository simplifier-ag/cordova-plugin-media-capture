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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.LOG;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.mediacapture.PendingRequests.Request;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

public class Capture extends CordovaPlugin {

	protected static final int CAPTURE_AUDIO = 0;     // Constant for capture audio
	protected static final int CAPTURE_IMAGE = 1;     // Constant for capture image
	protected static final int CAPTURE_VIDEO = 2;     // Constant for capture video
	protected final static String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
	private static final String VIDEO_3GPP = "video/3gpp";
	private static final String VIDEO_MP4 = "video/mp4";
	private static final String AUDIO_3GPP = "audio/3gpp";
	private static final String[] AUDIO_TYPES = new String[]{"audio/3gpp", "audio/aac", "audio/amr", "audio/wav"};
	private static final String IMAGE_JPEG = "image/jpeg";
	private static final String LOG_TAG = "Capture";
	// Camera or microphone failed to capture image or sound.
	private final int CAPTURE_INTERNAL_ERR = 0;
	// Camera application or audio capture application is currently serving other capture request.
	private final int CAPTURE_APPLICATION_BUSY = 1;
	// Invalid use of the API (e.g. limit parameter has value less than one).
	private final int CAPTURE_INVALID_ARGUMENT = 2;
	// User exited camera application or audio capture application before capturing anything.
	private final int CAPTURE_NO_MEDIA_FILES = 3;
	// User denied permissions required to perform the capture request.
	private final int CAPTURE_PERMISSION_DENIED = 4;
	// The requested capture operation is not supported.
	private final int CAPTURE_NOT_SUPPORTED = 20;
	private final PendingRequests pendingRequests = new PendingRequests();
	private boolean cameraPermissionInManifest;     // Whether or not the CAMERA permission is declared in AndroidManifest.xml
	private int numPics;                            // Number of pictures before capture activity


	private File mRequestedFile;

	/**
	 * Enables/Disables activity image . Default is disabled. Prevents other apps to call the
	 * activity but enabling it before calling startActivity will cause android to make it choosable by user
	 *
	 * @param activity activity containing the target package
	 * @param enabled  sets activity android:enabled for CaptureImageActivity (default false)
	 */
	public static void setActivityEnabled(Activity activity, String targetActivity, boolean enabled) {
		String packageName = activity.getPackageName();
		PackageManager pm = activity.getApplicationContext().getPackageManager();

		ComponentName cameraActivityAlias = new ComponentName(packageName, targetActivity);
		if (enabled) {
			pm.setComponentEnabledSetting(
					cameraActivityAlias,
					PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
					PackageManager.DONT_KILL_APP);
		} else {
			pm.setComponentEnabledSetting(
					cameraActivityAlias,
					PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
					PackageManager.DONT_KILL_APP);
		}
		LOG.i(LOG_TAG, String.format("%s %s", enabled ? "enabled" : "disabled", cameraActivityAlias));
	}

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
			case "deleteFile": {
				this.deleteFile(args.getString(0), callbackContext);
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
				if (!options.has("useInternalCameraApp")) {
					//check cordova options
					options.put("useInternalCameraApp", preferences.getBoolean("useInternalCameraApp", false));
				}
				this.captureImage(pendingRequests.createRequest(CAPTURE_IMAGE, options, callbackContext));
				break;
			case "captureVideo":
				if (!options.has("useInternalCameraApp")) {
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
			mimeType = FileHelper.getMimeType(fileUrl, cordova);
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
	 * @param fileUrl url pointing to the file
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

	//TODO add cdv api option to specify target activity. implicit intents (w/o class name) will
	// always call system camera starting from android 11
	// (see https://developer.android.com/about/versions/11/behavior-changes-11#camera)

	/**
	 * Sets up an intent to capture audio.  Result handled by onActivityResult()
	 */
	private void captureAudio(Request req) {
		if (!PermissionHelper.hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
				|| !PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
				|| !PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
			PermissionHelper.requestPermissions(this, req.requestCode, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO});
		} else {
			try {
				Intent intent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);

				mRequestedFile = FileHelper.getMediaFile(CAPTURE_AUDIO, cordova.getContext());
				intent.putExtra(MediaStore.EXTRA_OUTPUT, FileHelper.getUriFromFile(mRequestedFile, cordova.getContext()));
				intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, req.duration);
				intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				LOG.d(LOG_TAG, "Recording audio and saving to: " + mRequestedFile);

				//enable activity's intent filter to appear in camera app chooser dialog
				setActivityEnabled(this.cordova.getActivity(), AudioCaptureActivity.class.getCanonicalName(), true);

				this.cordova.startActivityForResult(this, intent, req.requestCode);
			} catch (ActivityNotFoundException | IOException ex) {
				pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NOT_SUPPORTED, "No Activity found to handle Audio Capture."));
			}
		}
	}

	/**
	 * Sets up an intent to capture images.  Result handled by onActivityResult()
	 */
	private void captureImage(Request req) {
		boolean needExternalStoragePermission =
				!PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

		boolean needCameraPermission = cameraPermissionInManifest &&
				!PermissionHelper.hasPermission(this, Manifest.permission.CAMERA);

		if (needExternalStoragePermission || needCameraPermission) {
			if (needExternalStoragePermission && needCameraPermission) {
				PermissionHelper.requestPermissions(this, req.requestCode, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA});
			} else if (needExternalStoragePermission) {
				PermissionHelper.requestPermission(this, req.requestCode, Manifest.permission.WRITE_EXTERNAL_STORAGE);
			} else {
				PermissionHelper.requestPermission(this, req.requestCode, Manifest.permission.CAMERA);
			}
		} else {
			try {
				// Save the number of images currently on disk for later
				Cursor cursor = queryImgDB(whichContentStore());
				this.numPics = cursor.getCount();
				cursor.close();

				Intent intent;
				if (req.useInternalCameraApp) {
					intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE, FileHelper.getUriFromFile(mRequestedFile, cordova.getContext()), cordova.getActivity(), CaptureActivity.class);
				} else {
					intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
				}

				mRequestedFile = FileHelper.getMediaFile(CAPTURE_IMAGE, cordova.getContext());
				intent.putExtra(MediaStore.EXTRA_OUTPUT, FileHelper.getUriFromFile(mRequestedFile, cordova.getContext()));
				intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
				LOG.d(LOG_TAG, "Taking a picture and saving to: " + mRequestedFile);

				//enable activity's intent filter to appear in camera app chooser dialog
				setActivityEnabled(this.cordova.getActivity(), CaptureActivity.class.getCanonicalName(), true);

				this.cordova.startActivityForResult(this, intent, req.requestCode);
			} catch (ActivityNotFoundException | IOException ex) {
				pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NOT_SUPPORTED, "No Activity found to handle Audio Capture."));
			}
		}
	}

	/**
	 * Sets up an intent to capture video.  Result handled by onActivityResult()
	 */
	private void captureVideo(Request req) {
		boolean needRecordAudioPermission =
				!PermissionHelper.hasPermission(this, Manifest.permission.RECORD_AUDIO);
		boolean needExternalStoragePermission =
				!PermissionHelper.hasPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
		boolean needCameraPermission = cameraPermissionInManifest &&
				!PermissionHelper.hasPermission(this, Manifest.permission.CAMERA);
		boolean needWriteExternalStoragePermission =
				!PermissionHelper.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

		if (needExternalStoragePermission || needWriteExternalStoragePermission || needRecordAudioPermission || needCameraPermission) {
			PermissionHelper.requestPermissions(this, req.requestCode, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA});
		} else {
			try {
				Intent intent = req.useInternalCameraApp
						? new Intent(MediaStore.ACTION_VIDEO_CAPTURE, FileHelper.getUriFromFile(mRequestedFile, cordova.getContext()), cordova.getActivity(), CaptureActivity.class)
						: new Intent(MediaStore.ACTION_VIDEO_CAPTURE);

				mRequestedFile = FileHelper.getMediaFile(CAPTURE_VIDEO, cordova.getContext());
				intent.putExtra(MediaStore.EXTRA_OUTPUT, FileHelper.getUriFromFile(mRequestedFile, cordova.getContext()));
				intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

				intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, req.duration);
				intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, req.quality);
				LOG.d(LOG_TAG, "Recording a video and saving to: " + mRequestedFile);

				//enable activity's intent filter to appear in camera app chooser dialog
				setActivityEnabled(this.cordova.getActivity(), CaptureActivity.class.getCanonicalName(), true);

				this.cordova.startActivityForResult(this, intent, req.requestCode);
			} catch (ActivityNotFoundException | IOException ex) {
				pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NOT_SUPPORTED, "No Activity found to handle Audio Capture."));
			}
		}
	}

	/**
	 * Deletes a file or content uri from string
	 */
	public void deleteFile(String contentUri, CallbackContext callbackContext) {
		try {
			if (cordova.getContext().getContentResolver().delete(Uri.parse(contentUri), null, null) <= 0) {
				File file = new File(contentUri);
				if (!file.delete() || file.exists()) {
					if (!file.getCanonicalFile().delete() || file.exists()) {
						callbackContext.error("error deleting file");
						return;
					}
				}
			}
			callbackContext.success();
		} catch (Exception e) {
			callbackContext.error(e.getMessage());
		}
	}

	/**
	 * Called when the video view exits.
	 *
	 * @param requestCode The request code originally supplied to startActivityForResult(),
	 *                    allowing you to identify who this result came from.
	 * @param resultCode  The integer result code returned by the child activity through its setResult().
	 * @param intent      An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
	 */
	public void onActivityResult(int requestCode, int resultCode, final Intent intent) {
		final Request req = pendingRequests.get(requestCode);

		if (CAPTURE_IMAGE == req.action || CAPTURE_VIDEO == req.action) {
			//disable ImageActivity alias to prevent other apps using this one
			setActivityEnabled(this.cordova.getActivity(), CaptureActivity.class.getCanonicalName(), false);
		} else if (CAPTURE_AUDIO == req.action) {
			setActivityEnabled(this.cordova.getActivity(), AudioCaptureActivity.class.getCanonicalName(), false);
		}

		// Result received okay
		if (resultCode == Activity.RESULT_OK) {
			Runnable processActivityResult = () -> {
				//data might be empty, but assuming RESULT_OK the requested content uri should point to the created media file
				Uri resultData;
				if (intent == null || intent.getData() == null) {
					resultData = FileHelper.getUriFromFile(mRequestedFile, cordova.getContext());
				} else {
					resultData = intent.getData();
				}


				/*
				 Tell the media scanner about the new file so that it is
				 immediately available to the user.
				*/
				MediaScannerConnection.scanFile(cordova.getContext(),
						new String[]{mRequestedFile.toString()}, null,
						(path, uri) -> {
							switch (req.action) {
								case CAPTURE_AUDIO:
									onAudioActivityResult(req, uri);
									break;
								case CAPTURE_IMAGE:
									onImageActivityResult(req, uri);
									break;
								case CAPTURE_VIDEO:
									onVideoActivityResult(req, uri);
									break;
							}
						});
			};

			this.cordova.getThreadPool().execute(processActivityResult);
		}

		// If canceled
		else if (resultCode == Activity.RESULT_CANCELED) {
			// If we have partial results send them back to the user
			if (req.results.length() > 0) {
				pendingRequests.resolveWithSuccess(req);
			}
			// user canceled the action
			else {
				pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NO_MEDIA_FILES, "Canceled."));
			}
		}

		// If something else
		else {
			// If we have partial results send them back to the user
			if (req.results.length() > 0) {
				pendingRequests.resolveWithSuccess(req);
			}
			// something bad happened
			else {
				pendingRequests.resolveWithFailure(req, createErrorObject(CAPTURE_NO_MEDIA_FILES, "Did not complete!"));
			}
		}
	}

	public void onAudioActivityResult(Request req, Uri result) {
		// create a file object from the audio absolute path
		req.results.put(createMediaFile(result, CAPTURE_AUDIO));

		if (req.results.length() >= req.limit) {
			// Send Uri back to JavaScript for listening to audio
			pendingRequests.resolveWithSuccess(req);
		} else {
			// still need to capture more audio clips
			captureAudio(req);
		}
	}

	public void onImageActivityResult(Request req, Uri result) {
		// Add image to results
		req.results.put(createMediaFile(result, CAPTURE_IMAGE));

		checkForDuplicateImage();

		if (req.results.length() >= req.limit) {
			// Send Uri back to JavaScript for viewing image
			pendingRequests.resolveWithSuccess(req);
		} else {
			// still need to capture more images
			captureImage(req);
		}
	}

	public void onVideoActivityResult(Request req, Uri result) {
		req.results.put(createMediaFile(result, CAPTURE_VIDEO));

		if (req.results.length() >= req.limit) {
			// Send Uri back to JavaScript for viewing video
			pendingRequests.resolveWithSuccess(req);
		} else {
			captureVideo(req);
		}
	}

	/**
	 * Creates a JSONObject that represents a File from the Uri
	 *
	 * @param data the Uri of the audio/image/video
	 * @return a JSONObject that represents a File
	 */
	@SuppressLint("Range")
	private JSONObject createMediaFile(Uri data) {

		JSONObject obj = new JSONObject();

		String[] projection = new String[]{
				MediaStore.MediaColumns._ID,
				MediaStore.MediaColumns.DATE_MODIFIED,
				MediaStore.MediaColumns.DISPLAY_NAME,
				MediaStore.MediaColumns.SIZE
		};

		String name = "";
		String mimetype = "";
		int size = 0;
		String lastModifiedDate = "";
		ContentResolver cr = cordova.getContext().getContentResolver();

		Cursor metaCursor = cr.query(data, projection, null, null, null);
		if (metaCursor != null) {
			try {
				//read meta data from media cursor
				if (metaCursor.moveToFirst()) {
					if (metaCursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME) > -1)
						name = metaCursor.getString(metaCursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME));
					if (metaCursor.getColumnIndex(MediaStore.MediaColumns.SIZE) > -1)
						size = metaCursor.getInt(metaCursor.getColumnIndex(MediaStore.MediaColumns.SIZE));
					if (metaCursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED) > -1) {
						lastModifiedDate = metaCursor.getString(metaCursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED));
					}
				}

				//try reading from parcelFileDescriptor / ExifInterface
				if (lastModifiedDate.isEmpty() || size <= 0) {
					try (ParcelFileDescriptor pfd = cr.openFileDescriptor(data, "r")) {
						pfd.getFileDescriptor().sync();

						if (lastModifiedDate.isEmpty()) {
							ExifInterface exif;
							try {
								exif = new ExifInterface(pfd.getFileDescriptor());
								lastModifiedDate = exif.getAttribute(ExifInterface.TAG_DATETIME);
							} catch (IOException e) {
								LOG.e(LOG_TAG, "Error reading exif interface for %s", data);
							}
						}

						if (size <= 0)
							size = Math.toIntExact(pfd.getStatSize());
					}
				}

			} catch (Exception e) {
				LOG.e(LOG_TAG, "error reading meta data", e);
			} finally {
				metaCursor.close();
			}
		}
		mimetype = cr.getType(data);

		try {
			// File properties
			obj.put("name", name);
			obj.put("type", mimetype);
			obj.put("size", size);
			obj.put("lastModifiedDate", lastModifiedDate);
			obj.put("fullPath", FileHelper.getUriFromFile(mRequestedFile, cordova.getContext()));
		} catch (JSONException e) {
			// this will never happen
			e.printStackTrace();
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
		// delete the duplicate file if the difference is 2
		Uri contentStore = whichContentStore();
		try (Cursor cursor = queryImgDB(contentStore)) {
			int currentNumOfImages = cursor.getCount();
			if ((currentNumOfImages - numPics) == 2) {
				cursor.moveToLast();
				int id = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
				Uri uri = Uri.parse(contentStore + "/" + id);
				this.cordova.getActivity().getContentResolver().delete(uri, null, null);
			}
		} catch (Exception e) {
			LOG.e(LOG_TAG, "checkForDuplicateImage", e);
		}
	}

	/**
	 * Determine if we are storing the images in internal or external storage
	 *
	 * @return Uri
	 */
	private Uri whichContentStore() {
		if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
			return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
		} else {
			return MediaStore.Images.Media.INTERNAL_CONTENT_URI;
		}
	}

	private File getFileFromUri(Uri uri, Context context) {
		File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_MOVIES), context.getPackageName());
		return new File(mediaStorageDir +
				File.separator + uri.getLastPathSegment());
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
}
