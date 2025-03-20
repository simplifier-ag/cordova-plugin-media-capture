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

import org.apache.cordova.LOG;
import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// TODO: Replace with CordovaResourceApi.getMimeType() post 3.1.
public class FileHelper {

    static final String VIDEO_MP4 = "video/mpeg";
    static final String AUDIO_MPEG = "audio/mpeg";
    static final String[] AUDIO_TYPES = new String[]{"audio/3gpp", "audio/aac", "audio/amr", "audio/wav"};
    static final String IMAGE_JPEG = "image/jpeg";
    static final String AUDIO_3GPP = "audio/3gpp";
    static final String VIDEO_3GPP = "video/3gpp";

    private final static String TAG = "FileHelper";
    public static Uri getAndCreateFile(String action, Activity activity) throws IllegalArgumentException {
        ContentResolver contentResolver = activity.getContentResolver();
        ContentValues cv = new ContentValues();
        switch (action) {
            case MediaStore.ACTION_VIDEO_CAPTURE:
                cv.put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MP4);
                return contentResolver.insert(isExternalContentStore()
                                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.INTERNAL_CONTENT_URI,
                        cv);
            case MediaStore.ACTION_IMAGE_CAPTURE:
                cv.put(MediaStore.Images.Media.MIME_TYPE, IMAGE_JPEG);
                return contentResolver.insert(isExternalContentStore()
                                ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                        cv);
            case MediaStore.Audio.Media.RECORD_SOUND_ACTION:
                cv.put(MediaStore.Audio.Media.MIME_TYPE, AUDIO_MPEG);
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    cv.put(MediaStore.Audio.Media.DATA,
                            new File(
                                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                    System.currentTimeMillis() + ".mp3"
                            ).getAbsolutePath());
                }

                return contentResolver.insert(isExternalContentStore()
                                ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
                        cv);
            default:
                throw new IllegalArgumentException("Unexpected action: " + action);
        }
    }


    /**
     * Queries the content provider for a single media file based on the provided parameters.
     *
     * @param context    The application context.
     * @param collection The content URI to query.
     * @return A JSONObject representing a media file.
     * The JSONObject will have the following structure:
     * {
     * "name": "filename",
     * "fullPath": "content uri or native file path",
     * "type": "mimetype",
     * "lastModifiedDate": "date modified",
     * "size": "file size in bytes"
     * }
     */

    @Nullable
    public static JSONObject queryContentProvider(Context context, Uri collection, int limit) {
        final DateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault());
        final String[] projection;
        projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_TAKEN
        };
        String selection = null;
        String[] selectionArgs = null;

        Bundle bundle = new Bundle();
        if (limit > 0) {
            bundle.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
        }
        bundle.putInt(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        bundle.putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{MediaStore.Audio.Media.DATE_TAKEN});
        bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
        bundle.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);

        JSONObject finalItem = new JSONObject();
        try (Cursor cursor = context.getContentResolver().query(
                collection,
                projection,
                bundle,null)) {
            if (cursor == null) {
                LOG.e(TAG, "Cursor is null");
                return null;
            }


            // Check if the cursor has any rows
            if (cursor.moveToFirst()) {
                do {
                    int nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    int mimeTypeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
                    int dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                    int sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
                    int dateTakenColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN);
                    int dateModifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);

                    String name = cursor.getString(nameColumn);
                    finalItem.put("name", name != null ? name : "");

                    String fullPath;
                    if (dataColumn != -1) {
                        fullPath = cursor.getString(dataColumn);
                    } else {
                        fullPath = collection.toString();
                    }
                    finalItem.put("fullPath", fullPath != null ? fullPath : collection.toString());

                    String mimeType = null;
                    if (mimeTypeColumn != -1) {
                        mimeType = cursor.getString(mimeTypeColumn);
                    }
                    if (mimeType == null || mimeType.isEmpty()) {
                        mimeType = getMimeType(collection, context);
                    }
                    finalItem.put("type", mimeType != null ? mimeType : "");

                    long dateTaken = 0;
                    if (dateTakenColumn > -1)
                        dateTaken = cursor.getLong(dateTakenColumn);
                    long dateModified = 0;
                    if (dateModifiedColumn > -1)
                        dateModified = cursor.getLong(dateModifiedColumn);
                    long newDate = dateModified > 0 ? dateModified : dateTaken;
                    Date date = new Date(newDate > 0 ? newDate : new Date().getTime());
                    finalItem.put("lastModifiedDate", dateFormatter.format(date));

                    long size = 0;
                    if (sizeColumn > -1)
                        size = cursor.getLong(sizeColumn);
                    finalItem.put("size", size);
                } while (cursor.moveToNext());

            } else {
                LOG.w(TAG, "Cursor is empty. No data found for uri: " + collection);
                return finalItem;
            }
        } catch (Exception e) {
            LOG.e(TAG, "Error querying content provider", e);
            return finalItem;
        }

        return finalItem;
    }

    public static String getMimeType(Uri uri, Context context) {
        String mimeType;
        if ("content".equals(uri.getScheme())) {
            mimeType = context.getContentResolver().getType(uri);
        } else {
            mimeType = FileHelper.getMimeTypeForExtension(uri.getPath());
        }

        return mimeType;
    }

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
     * Determine if we are storing the images in internal or external storage
     *
     * @return Uri
     */
    public static boolean isExternalContentStore() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }


}
