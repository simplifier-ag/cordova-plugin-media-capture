package org.apache.cordova.mediacapture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.Image;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import org.apache.cordova.LOG;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
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
     * content:// uri
     */
    private Uri mUri; //android q+

    private Context mContext;

    private final ImageSaver.Callback mCallback;

    ImageSaver(Context context, Image image, Uri uri, ImageSaver.Callback callback) {
        mContext = context;
        mImage = image;
        mUri = uri;
        mCallback = callback;
    }

    @Override
    public void run() {

        try {
            mCallback.onSuccess(
                    getRotatedBitmap());

        } catch (SecurityException e) {
            // received security exception from copyFileToUri()->openOutputStream() from Google Play
            LOG.e(TAG, "security exception writing file", e);
            mCallback.onFailure(e);
        } catch (NullPointerException e) {
            // received security exception from copyFileToUri()->openOutputStream() from Google Play
            LOG.e(TAG, "nullPointer exception writing file", e);
            mCallback.onFailure(e);
        } finally {
            //free up resources
            mImage.close();
        }


        //ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
        //byte[] bytes = new byte[buffer.remaining()];
        //buffer.get(bytes);

//		try {
//			output = mContext.getContentResolver().openOutputStream(mUri);
//
//			if (output == null) {
//				throw new IOException("Could not open OutputStream");
//			}
//
//			output.write(bytes);
//		} catch (IOException e) {
//			success = false;
//			LOG.e(TAG, "Error while saving image", e);
//			mCallback.onFailure(e);
//		} finally {
//			mImage.close();
//			if (output != null) {
//				try {
//					output.close();
//				} catch (IOException e) {
//					LOG.e(TAG, "Error while closing OutputStream", e);
//				}
//			}
//		}
//
//		if (!success)
//			return;
//
//		String[] filePath = {MediaStore.Images.Media.DATA};
//		Cursor cursor = mContext.getContentResolver().query(mUri, filePath, null, null, null);
//
//		if (cursor == null) {
//			LOG.w(TAG, "Cursor of content resolver is null");
//			mCallback.onSuccess(null);
//			return;
//		}
//
//		cursor.moveToFirst();
//		String imagePath = cursor.getString(cursor.getColumnIndex(filePath[0]));
//		cursor.close();
//
//		mCallback.onSuccess(getRotatedBitmap(imagePath));
    }

    @Nullable
    private Bitmap getRotatedBitmap() {
        Image.Plane[] planes = mImage.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        buffer.rewind();
        byte[] data = new byte[buffer.capacity()];
        buffer.get(data);
        mImage.close();
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);

        try (InputStream inputStream = new ByteArrayInputStream(data)) {
            ParcelFileDescriptor parcelFileDescriptor = mContext.getContentResolver().openFileDescriptor(mUri, "rw");
            ExifInterface exif = new ExifInterface(parcelFileDescriptor.getFileDescriptor());

            //ExifInterface exif = new ExifInterface(inputStream);

            int currentExifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            int mappedExifOrientation = 0;
            // see http://jpegclub.org/exif_orientation.html
            // and http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
            switch (currentExifOrientation) {
                case ExifInterface.ORIENTATION_UNDEFINED:
                case ExifInterface.ORIENTATION_NORMAL:
                    // leave unchanged
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    mappedExifOrientation = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    mappedExifOrientation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    mappedExifOrientation = 270;
                    break;
                default:
                    // just leave unchanged for now
                    LOG.e(TAG, "    unsupported exif orientation: " + currentExifOrientation);
                    break;
            }

            if (mappedExifOrientation > ExifInterface.ORIENTATION_NORMAL) {
                Matrix matrix = new Matrix();
                matrix.setRotate(mappedExifOrientation);
                Bitmap rotated_bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(),
                        bitmap.getHeight(),
                        matrix,
                        true);
                if (rotated_bitmap != bitmap) {
                    bitmap.recycle();
                    bitmap = rotated_bitmap;
                }
            }


            if (mUri == null || bitmap == null) {
                throw new FileNotFoundException("no uri provided");
            }

            //save compressed file
            try (OutputStream output = mContext.getContentResolver().openOutputStream(mUri)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                    throw new IOException("could not compress image");
            }


        } catch (IOException e) {
            LOG.e(TAG, "Failed receiving ExIf metadata", e);

            return bitmap;
        }
        return bitmap;
    }

    public interface Callback {
        void onSuccess(@Nullable Bitmap bitmap);

        void onFailure(Throwable t);
    }
}