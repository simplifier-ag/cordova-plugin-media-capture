package org.apache.cordova.mediacapture;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.Image;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import org.apache.cordova.LOG;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

@SuppressLint("LogNotTimber")
public class ImageSaver implements Runnable {
    private final String TAG = ImageSaver.class.getSimpleName();

    /**
     * The image from camera callback
     */
    @Nullable
    private final Image mImage;

    /**
     * An already existing bitmap
     */
    @Nullable
    private Bitmap mBitmap;


    /**
     * target content:// uri for image
     */
    private final Uri mUri;

    /**
     * activity context
     */
    private final Context mContext;

    /**
     *
     */
    private float mRotation = 0f;

    private final ImageSaver.Callback mCallback;

    /**
     * saves jpeg from given image to taget uri
     * @param context activity context
     * @param image image with at least one plane
     * @param uri target uri to save the image to
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
     * @param context activity context
     * @param bitmap source bitmap
     * @param uri target content-uri
     * @param rotation target rotation in degrees
     * @param callback calls .success with processed bitmap if images was saved successfully,
     *      *                 .error if something failed
     */
    ImageSaver(Context context, @NonNull Bitmap bitmap, Uri uri, float rotation, ImageSaver.Callback callback) {
        this.mContext = context;
        this.mImage = null;
        this.mBitmap = bitmap;
        this.mUri = uri;
        this.mCallback = callback;
        this.mRotation = rotation;
    }

    @Override
    public void run() {
        try {
            mCallback.onSuccess(getBitmap());
        } catch (Exception e) {
            mCallback.onFailure(e);
        }
    }

    @Nullable
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
                mImage.close();
                mBitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            } catch (SecurityException e) {
                // received security exception from copyFileToUri()->openOutputStream() from Google Play
                LOG.e(TAG, "security exception writing file", e);
                throw new Exception("security exception writing file");
            } catch (NullPointerException e) {
                LOG.e(TAG, "nullPointer exception writing file", e);
                throw new Exception("nullPointer exception writing file");
            } finally {
                mImage.close();
            }
        }

        if (mBitmap == null) {
            throw new Exception("Error generating image");
        }

        //get exif from image data
        ExifInterface exif;
        if (data != null) { //data was provided by bitmap or image
            try (InputStream inputStream = new ByteArrayInputStream(data)) {
                exif = new ExifInterface(inputStream);
            } catch (IOException e) {
                LOG.e(TAG, "Failed receiving ExIf metadata", e);
                throw new Exception("Failed receiving ExIf metadata");
            }
        } else { //try reading exif from uri
            try (ParcelFileDescriptor parcelFileDescriptor = mContext.getContentResolver().openFileDescriptor(mUri, "rw")) {
                exif = new ExifInterface(parcelFileDescriptor.getFileDescriptor());
            } catch (IOException e) {
                LOG.e(TAG, "Failed receiving ExIf metadata", e);
                throw new Exception("Failed receiving ExIf metadata");
            }
        }

        int exifRotation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        int exifRotationDegrees = exifToDegrees(exifRotation);

        //rotates according to exif meta data from given image
        if (exifRotationDegrees != 0) {
            mBitmap = rotateBitMap(mBitmap, exifRotationDegrees);
        }

        //rotates by given rotation
        if (mRotation != 0f) {
            mBitmap = rotateBitMap(mBitmap, mRotation);
        }

        //save compressed file
        try (OutputStream output = mContext.getContentResolver().openOutputStream(mUri)) {
            if (!mBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                throw new IOException("could not compress image");
        } catch (Exception e) {
            LOG.e(TAG, "Failed saving image", e);
        }

        return mBitmap;
    }

    /**
     * rotates image a given degree
     * @param bitmap  source bitmap
     * @param degrees positive integer in degrees rotates right, negative left
     */
    Bitmap rotateBitMap(Bitmap bitmap, float degrees) {
        Matrix matrix = new Matrix();
        matrix.preRotate(degrees);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * maps exif orientation to int
     * @param exifOrientation
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
        void onSuccess(final Bitmap bitmap);

        void onFailure(Throwable t);
    }
}