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

import static org.apache.cordova.mediacapture.Capture.AUDIO_MPEG;
import static org.apache.cordova.mediacapture.Capture.IMAGE_JPEG;
import static org.apache.cordova.mediacapture.Capture.VIDEO_MP4;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.apache.cordova.CordovaInterface;

import java.io.File;
import java.util.Locale;

// TODO: Replace with CordovaResourceApi.getMimeType() post 3.1.
public class FileHelper {
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
	 * @param uri the URI string of the data
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

	public static Uri getAndCreateFile(String action, Activity activity) {
		ContentResolver contentResolver = activity.getContentResolver();
		ContentValues cv = new ContentValues();
		switch (action) {
			case MediaStore.ACTION_VIDEO_CAPTURE:
				cv.put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MP4);
				return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
			case MediaStore.ACTION_IMAGE_CAPTURE:
				cv.put(MediaStore.Images.Media.MIME_TYPE, IMAGE_JPEG);
				return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
			case MediaStore.Audio.Media.RECORD_SOUND_ACTION:
				cv.put(MediaStore.Audio.Media.MIME_TYPE, AUDIO_MPEG);
				if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
					cv.put(MediaStore.Audio.Media.DATA,
							new File(
									Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
									System.currentTimeMillis() + ".mp3"
							).getAbsolutePath());
				}

					return contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
			default:
				throw new IllegalStateException("Unexpected value: " + action);
		}
	}
}
