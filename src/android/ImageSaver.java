package org.apache.cordova.mediacapture;

import android.content.Context;
import android.media.Image;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

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
		mImage = image;
		mUri = uri;
		mCallback = callback;
		mContext = context;
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
					Log.e(TAG, "Error while closing outputstream", e);
				}
			}
		}

		if (success)
			mCallback.onSuccess();
	}

	public interface Callback {
		void onSuccess();
		void onFailure(Throwable t);
	}
}