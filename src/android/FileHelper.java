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

import static org.apache.cordova.mediacapture.Capture.CAPTURE_AUDIO;
import static org.apache.cordova.mediacapture.Capture.CAPTURE_IMAGE;
import static org.apache.cordova.mediacapture.Capture.CAPTURE_VIDEO;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.LOG;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// TODO: Replace with CordovaResourceApi.getMimeType() post 3.1.
public class FileHelper {
	private final static String LOG_TAG = FileHelper.class.getSimpleName();


	public static String getMimeTypeForExtension(String path) {
		String extension = path;
		int lastDot = extension.lastIndexOf('.');
		if (lastDot != -1) {
			extension = extension.substring(lastDot + 1);
		}
		// Convert the URI string to lower case to ensure compatibility with MimeTypeMap (see CB-2185).
		extension = extension.toLowerCase(Locale.getDefault());
		if (extension.equals("3ga")) {
			return "audio/3gpp";
		}
		return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
	}

	/**
	 * Returns the mime type of the data specified by the given URI string.
	 *
	 * @param uri String the URI string of the data
	 * @return the mime type of the specified data
	 */
	public static String getMimeType(Uri uri, CordovaInterface cordova) {
		String mimeType = null;
		if ("content".equals(uri.getScheme())) {
			mimeType = cordova.getActivity().getContentResolver().getType(uri);
		} else {
			mimeType = getMimeTypeForExtension(uri.getPath());
		}

		return mimeType;
	}

	/**
	 * generates a file with MediaStore for a given media file type
	 *
	 * @param type    target media type
	 * @param context activity context
	 * @return content://-uri for a given media file type
	 */
	@Nullable
	public static Uri getDataUriForMediaFile(int type, Context context) throws IllegalArgumentException, IOException {

		String applicationId = context.getPackageName();
		File mediaStorageDir;
		Uri uri;
		String timeStamp = new SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault()).format(new Date());
		switch (type) {
			case CAPTURE_AUDIO: {
				mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
						Environment.DIRECTORY_MUSIC), applicationId);
				String fileName = "AUDIO_" + timeStamp + ".mp3";
				File audio = new File(mediaStorageDir, fileName);

				uri = FileProvider.getUriForFile(context,
						applicationId + ".cordova.plugin.mediacapture.provider",
						audio);
				break;
			}

			case CAPTURE_IMAGE: {
				mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
						Environment.DIRECTORY_PICTURES), applicationId);
				String fileName = "IMG_" + timeStamp + ".jpg";
				File image = new File(mediaStorageDir, fileName);

				uri = FileProvider.getUriForFile(context,
						applicationId + ".cordova.plugin.mediacapture.provider",
						image);
			}
			break;
			case CAPTURE_VIDEO: {
				mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
						Environment.DIRECTORY_MOVIES), applicationId);
				String fileName = "VID_" + timeStamp + ".mp4";
				File video = new File(mediaStorageDir, fileName);

				uri = FileProvider.getUriForFile(context,
						applicationId + ".cordova.plugin.mediacapture.provider",
						video);

			}
			break;
			default:
				return null;
		}

		if (!mediaStorageDir.exists()) {
			if (!mediaStorageDir.mkdirs()) {
				LOG.d(LOG_TAG, "failed to create directory");
				return null;
			}
		}
		return uri;
	}

}
