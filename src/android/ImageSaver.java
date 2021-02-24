package org.apache.cordova.mediacapture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

@SuppressLint("LogNotTimber")
public class ImageSaver implements Runnable {
	private final String TAG = ImageSaver.class.getSimpleName();

	/**
	 * The JPEG image
	 */
	private final Image mImage;
	/**
	 * The file we save the image into.
	 */
	private File mFile = null;

	private Uri mUri = null;
	private Context mContext = null;

	private final ImageSaver.Callback mCallback;

	ImageSaver(Image image, File file, ImageSaver.Callback callback) {
		mImage = image;
		mFile = file;
		mCallback = callback;
	}

	ImageSaver(Context context, Image image, Uri uri, ImageSaver.Callback callback) {
		mContext = context;
		mImage = image;
		mUri = uri;
		mCallback = callback;
	}

	@Override
	public void run() {
		boolean success = true;
		ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		OutputStream output = null;
		try {
			if (mUri != null) {
				output = mContext.getContentResolver().openOutputStream(mUri);
			} else {
				output = new FileOutputStream(mFile);
			}

			if (output == null) {
				throw new IOException("Could not open OutputStream");
			}

			output.write(bytes);
		} catch (IOException e) {
			success = false;
			Log.e(TAG, "Error while saving image", e);
			mCallback.onFailure(e);
		} finally {
			mImage.close();
			if (output != null) {
				try {
					output.close();
				} catch (IOException e) {
					Log.e(TAG, "Error while closing OutputStream", e);
				}
			}
		}

		if (!success)
			return;

		if (mUri != null) {
			String[] filePath = {MediaStore.Images.Media.DATA};
			Cursor cursor = mContext.getContentResolver().query(mUri, filePath, null, null, null);

			if (cursor == null) {
				Log.w(TAG, "Cursor of content resolver is null");
				mCallback.onSuccess(null);
				return;
			}

			cursor.moveToFirst();
			String imagePath = cursor.getString(cursor.getColumnIndex(filePath[0]));
			cursor.close();

			mCallback.onSuccess(getRotatedBitmap(imagePath));
		} else {
			mCallback.onSuccess(getRotatedBitmap(mFile.getAbsolutePath()));
		}
	}

	@Nullable
	private Bitmap getRotatedBitmap(String imagePath) {
		Bitmap bitmap = BitmapFactory.decodeFile(imagePath);

		if (bitmap == null) {
			Log.e(TAG, "Could not decode image");
			return  null;
		}

		ExifInterface exif;

		try {
			exif = new ExifInterface(imagePath);
		} catch (IOException e) {
			Log.e(TAG, "Failed receiving ExIf metadata", e);
			return bitmap;
		}
		int rotation = exif.getAttributeInt(android.support.media.ExifInterface.TAG_ORIENTATION, android.support.media.ExifInterface.ORIENTATION_NORMAL);
		int rotationDegrees = exifToDegrees(rotation);

		if (rotationDegrees != 0) {
			Matrix matrix = new Matrix();
			matrix.preRotate(rotationDegrees);
			bitmap = Bitmap.createBitmap(bitmap, 0, 0,
					bitmap.getWidth(),
					bitmap.getHeight(),
					matrix,
					true);
		}

		return bitmap;
	}

	private int exifToDegrees(int exifOrientation) {
		if (exifOrientation == android.support.media.ExifInterface.ORIENTATION_ROTATE_90) {
			return 90;
		} else if (exifOrientation == android.support.media.ExifInterface.ORIENTATION_ROTATE_180) {
			return 180;
		} else if (exifOrientation == android.support.media.ExifInterface.ORIENTATION_ROTATE_270) {
			return 270;
		}
		return 0;
	}

	public interface Callback {
		void onSuccess(@Nullable Bitmap bitmap);
		void onFailure(Throwable t);
	}
}