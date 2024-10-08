package org.apache.cordova.mediacapture;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.cordova.LOG;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

@SuppressLint("LogNotTimber")
public class ImageSaver implements Runnable {
	private final String TAG = ImageSaver.class.getSimpleName();

	/**
	 * The image from camera callback
	 */
	@Nullable
	private final Image mImage;
	/**
	 * target content:// uri for image
	 */
	private final Uri mUri;
	/**
	 * activity context
	 */
	private final Context mContext;
	private final ImageSaver.Callback mCallback;
	/**
	 * An already existing bitmap
	 */
	@Nullable
	private Bitmap mBitmap;
	/**
	 *
	 */
	private float mRotation = 0f;

	/**
	 * saves jpeg from given image to taget uri
	 *
	 * @param context  activity context
	 * @param image    image with at least one plane
	 * @param uri      target uri to save the image to
	 * @param callback calls .success with processed bitmap if images was saved successfully,
	 *                 .error if something failed
	 */
	ImageSaver(Context context, @NonNull Image image, Uri uri, ImageSaver.Callback callback) {
		this.mContext = context;
		this.mImage = image;
		this.mBitmap = null;
		this.mUri = uri;
		this.mCallback = callback;
	}

	/**
	 * rotates a given bitmap and saves it to a give url
	 *
	 * @param context  activity context
	 * @param bitmap   source bitmap
	 * @param uri      target content-uri
	 * @param rotation target rotation in degrees
	 * @param callback calls .success with processed bitmap if images was saved successfully,
	 *                 *                 .error if something failed
	 */
	ImageSaver(Context context, @NonNull Bitmap bitmap, Uri uri, float rotation, ImageSaver.Callback callback) {
		this.mContext = context;
		this.mImage = null;
		this.mBitmap = bitmap;
		this.mUri = uri;
		this.mCallback = callback;
		this.mRotation = rotation;
	}

	/**
	 * rotates a given bitmap and saves it to a give url
	 *
	 * @param context  activity context
	 * @param bitmap   source bitmap
	 * @param uri      target content-uri
	 * @param callback calls .success with processed bitmap if images was saved successfully,
	 *                 *                 .error if something failed
	 */
	ImageSaver(Context context, @NonNull Bitmap bitmap, Uri uri, ImageSaver.Callback callback) {
		this.mContext = context;
		this.mImage = null;
		this.mBitmap = bitmap;
		this.mUri = uri;
		this.mCallback = callback;
	}

	@Override
	public void run() {
		try {
			mCallback.onSuccess(getBitmap());
		} catch (Exception e) {
			LOG.e(TAG, "error saving image", e);
			mCallback.onFailure(e);
		}
	}

	@NonNull
	private Bitmap getBitmap() throws Exception {
		byte[] data = null;

		//resolve bitmap from image
		if (mImage != null) {
			try {
				Image.Plane[] planes = mImage.getPlanes();
				ByteBuffer buffer = planes[0].getBuffer();
				buffer.rewind();
				data = new byte[buffer.capacity()];
				buffer.get(data);
				mBitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
			} catch (NullPointerException e) {
				throw new Exception("nullPointer exception writing file");
			} finally {
				mImage.close();
			}
		}

		//resolve from uri
		if (mBitmap == null) {
			try (ParcelFileDescriptor parcelFileDescriptor = mContext.getContentResolver().openFileDescriptor(mUri, "rw")) {
				mBitmap = BitmapFactory.decodeFileDescriptor(parcelFileDescriptor.getFileDescriptor());
			} catch (IOException e) {
				throw new Exception("Error generating image");
			}
		}

		//rotates by given rotation
		if (mRotation != 0f) {
			mBitmap = rotateBitMap(mBitmap, mRotation);
		}

		ContentResolver cr = mContext.getContentResolver();
		//save compressed file
		try (OutputStream output = mContext.getContentResolver().openOutputStream(mUri)) {
			if (!mBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
				throw new IOException("could not compress image");
			else {
				//store meta data
				try (ParcelFileDescriptor parcelFileDescriptor = mContext.getContentResolver().openFileDescriptor(mUri, "rw")) {
					ExifInterface exif = new ExifInterface(parcelFileDescriptor.getFileDescriptor());
					SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault());
					sdf.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
					String date = sdf.format(new Date());
					exif.setAttribute(ExifInterface.TAG_DATETIME, date);
					exif.saveAttributes();
				} catch (IOException e) {
					LOG.e(TAG, "Failed receiving ExIf metadata", e);
				}
			}
		} catch (Exception e) {
			throw new IOException("could not create image");
		}

		return mBitmap;
	}

	/**
	 * rotates image a given degree
	 *
	 * @param bitmap  source bitmap
	 * @param degrees positive integer in degrees rotates right, negative left
	 */
	@NonNull
	Bitmap rotateBitMap(Bitmap bitmap, float degrees) {
		Matrix matrix = new Matrix();
		matrix.preRotate(degrees);
		return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
	}

	/**
	 * maps exif orientation to int
	 *
	 * @param exifOrientation given exif orientation
	 * @return orientation in degrees
	 */
	private int exifToDegrees(int exifOrientation) {
		if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) {
			return 90;
		} else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {
			return 180;
		} else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {
			return 270;
		}
		return 0;
	}

	public interface Callback {
		void onSuccess(@NonNull final Bitmap bitmap);

		void onFailure(Throwable t);
	}
}