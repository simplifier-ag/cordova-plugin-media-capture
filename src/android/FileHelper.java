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

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginManager;
import org.apache.cordova.file.FileUtils;
import org.apache.cordova.file.LocalFilesystemURL;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

// TODO: Replace with CordovaResourceApi.getMimeType() post 3.1.
public class FileHelper {

	static final String VIDEO_MP4 = "video/mpeg";
	static final String AUDIO_MPEG = "audio/mpeg";
	static final String[] AUDIO_TYPES = new String[]{"audio/3gpp", "audio/aac", "audio/amr", "audio/wav"};
	static final String IMAGE_JPEG = "image/jpeg";
	private static final String AUDIO_3GPP = "audio/3gpp";
	static final String VIDEO_3GPP = "video/3gpp";

	private final static String TAG = "FileHelper";

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
	public static String getMimeType(Uri uri, Context context) {
		String mimeType;
		if ("content".equals(uri.getScheme())) {
			mimeType = context.getContentResolver().getType(uri);
		} else {
			mimeType = getMimeTypeForExtension(uri.getPath());
		}

		return mimeType;
	}

	public static Uri getAndCreateFile(String action, Activity activity) throws IllegalArgumentException {
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
				throw new IllegalArgumentException("Unexpected action: " + action);
		}
	}

	/**
	 * Creates a JSONObject that represents a File from the Uri
	 *
	 * @param data    the Uri of the audio/image/video
	 * @param webView the CordovaWebView
	 * @return a JSONObject that represents a File
	 * @throws IOException
	 */
	public static JSONObject createMediaFile(Uri data, CordovaWebView webView) {
		Context context = webView.getContext();
		try {
			if (data.getScheme() != null && data.getScheme().equals("content")) {
				Bundle bundle = new Bundle();
//				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//					bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.MediaColumns._ID + " = " + (data.toString().substring(data.toString().lastIndexOf("/") + 1)));
//				} else {
//					bundle.putString(MediaStore.MediaColumns.DATA, data.toString());
//				}
				JSONObject columns = new JSONObject() {{
					put("int.id", MediaStore.Audio.Media._ID);
					put("fileName", MediaStore.Audio.AudioColumns.DISPLAY_NAME);
					put("mimeType", MediaStore.Audio.AudioColumns.MIME_TYPE);

					put("nativeURL", MediaStore.MediaColumns.DATA); // will not be returned to javascript
				}};

				final JSONArray queryResults = queryContentProvider(context, data, columns, 0, bundle);
				return queryResults.getJSONObject(0);
			}
		} catch (Exception e) {
			LOG.e(TAG, e.getMessage(), e);
			return null;
		}

		File fp = webView.getResourceApi().mapUriToFile(data);
		if (fp == null) {
			return null;
		}

		JSONObject obj = new JSONObject();

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
		FileUtils filePlugin = (FileUtils) pm.getPlugin("File");
		LocalFilesystemURL url = filePlugin.filesystemURLforLocalPath(fp.getAbsolutePath());

		try {
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
				if (data.toString().contains("/audio/")) {
					obj.put("type", AUDIO_3GPP);
				} else {
					obj.put("type", VIDEO_3GPP);
				}
			} else {
				obj.put("type", FileHelper.getMimeType(Uri.fromFile(fp), context));
			}

			obj.put("lastModifiedDate", fp.lastModified());
			obj.put("size", fp.length());
		} catch (JSONException e) {
			// this will never happen
			e.printStackTrace();
		}
		return obj;
	}


	/**
	 * Queries the content provider for media files based on the provided parameters.
	 *
	 * @param context   The application context.
	 * @param collection The content URI to query.
	 * @param columns   A JSONObject specifying the columns to select.
	 *                  Example:
	 *                  {
	 *                  "int.id": MediaStore.Audio.Media._ID,
	 *                  "fileName": MediaStore.Audio.AudioColumns.DISPLAY_NAME,
	 *                  "mimeType": MediaStore.Audio.AudioColumns.MIME_TYPE,
	 *                  "nativeURL": MediaStore.MediaColumns.DATA // will not be returned to javascript
	 *                  }
	 * @param limit     The maximum number of results to return (0 for no limit).
	 * @return A JSONArray of JSONObjects, each representing a media file.
	 * Each JSONObject will have the following structure:
	 * {
	 * "name": "filename",
	 * "fullPath": "content uri or native file path",
	 * "type": "mimetype",
	 * "lastModifiedDate": "date modified",
	 * "size": "file size in bytes"
	 * }
	 * @throws JSONException If there is an error parsing JSON data.
	 */
	public JSONArray queryContentProvider(Context context, Uri collection, JSONObject columns, int limit) throws JSONException {
		return queryContentProvider(context, collection, columns, limit, null);
	}

	public static JSONArray queryContentProvider(Context context, Uri collection, JSONObject columns, int limit, @Nullable Bundle bundle) throws JSONException {
		final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault());

		final ArrayList<String> columnNames = new ArrayList<>();
		final ArrayList<String> columnValues = new ArrayList<>();

		Iterator<String> iteratorFields = columns.keys();

		while (iteratorFields.hasNext()) {
			String column = iteratorFields.next();

			columnNames.add(column);
			columnValues.add("" + columns.getString(column));
		}

		if (bundle == null) {
			bundle = new Bundle();
		}
		if (limit > 0) {
			bundle.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
		}
		bundle.putInt(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
		bundle.putStringArray(
				ContentResolver.QUERY_ARG_SORT_COLUMNS,
				new String[]{MediaStore.Audio.Media.DATE_TAKEN});

		final JSONArray buffer = new JSONArray();
		try (Cursor cursor = context.getContentResolver().query(
				collection,
				columnValues.toArray(new String[columns.length()]),
				bundle, null)) {
			if (cursor == null) {
				LOG.e(TAG, "Cursor is null");
				return buffer;
			}

			int count = 0;

			if (cursor.moveToFirst()) {
				do {

					JSONObject item = new JSONObject();

					for (String column : columnNames) {
						int columnIndex = cursor.getColumnIndex(columns.get(column).toString());
						if (columnIndex == -1) {
							LOG.w(TAG, "Column not found: " + columns.get(column).toString());
							continue;
						}

						if (column.startsWith("int.")) {
							item.put(column.substring(4), cursor.getInt(columnIndex));
						} else if (column.startsWith("float.")) {
							item.put(column.substring(6), cursor.getFloat(columnIndex));
						} else if (column.startsWith("date.")) {
							long intDate = cursor.getLong(columnIndex);
							Date date = new Date(intDate);
							item.put(column.substring(5), dateFormatter .format(date));
						} else {
							item.put(column, cursor.getString(columnIndex));
						}
					}
					//construct the final json object
					JSONObject finalItem = new JSONObject();
					if (item.has("fileName")) {
						finalItem.put("name", item.getString("fileName"));
					} else {
						finalItem.put("name", "");
					}
					if (item.has("nativeURL")) {
						finalItem.put("fullPath", item.getString("nativeURL"));
					} else {
						finalItem.put("fullPath", collection.toString());
					}
					String mimeType = "";
					if (item.has("mimeType")) {
						mimeType = item.getString("mimeType");
					}

					if (mimeType.isEmpty()) {
						mimeType = FileHelper.getMimeType(collection, context);
					}

					finalItem.put("type", mimeType != null ? mimeType : "");
					if (item.has("dateTaken")) {
						finalItem.put("lastModifiedDate", item.getString("dateTaken"));
					} else {
						finalItem.put("lastModifiedDate", "");
					}
					if (item.has("size")) {
						finalItem.put("size", item.getLong("size"));
					} else {
						finalItem.put("size", 0);
					}

					buffer.put(finalItem);

					count++;
					if (limit > 0 && count >= limit) { // Limit reached
						break;
					}

				}
				while (cursor.moveToNext());
			}
		} catch (Exception e) {
			LOG.e(TAG, "Error querying content provider", e);
			throw new JSONException("Error querying content provider: " + e.getMessage());
		}

		return buffer;
	}
}
