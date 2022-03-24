package org.apache.cordova.mediacapture;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

import org.apache.cordova.LOG;

public class AutoFitTextureView extends TextureView {

	private static String TAG = AutoFitTextureView.class.getCanonicalName();
	private float aspectRatio = 0f;

	public AutoFitTextureView(Context context) {
		this(context, null);
	}

	public AutoFitTextureView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	/**
	 * Sets the aspect ratio for this view. The size of the view will be measured based on the ratio
	 * calculated from the parameters. Note that the actual sizes of parameters don't matter, that
	 * is, calling setAspectRatio(2, 3) and setAspectRatio(4, 6) make the same result.
	 *
	 * @param width  Relative horizontal size
	 * @param height Relative vertical size
	 */
	public void setAspectRatio(int width, int height) {
		LOG.v(TAG, String.format("setAspectRatio - width: %s, height: %s", width, height));
		if (width < 0 || height < 0) {
			throw new IllegalArgumentException("Size cannot be negative.");
		}

		aspectRatio = (float) width / (float) height;
		requestLayout();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		int width = MeasureSpec.getSize(widthMeasureSpec);
		int height = MeasureSpec.getSize(heightMeasureSpec);
		LOG.v(TAG, String.format("onMeasure - width: %s, height: %s", width, height));
		if (aspectRatio == 0f) {
			setMeasuredDimension(width, height);
		} else {
			int newWidth;
			int newHeight;
			float actualRatio = width > height ? aspectRatio : 1f / aspectRatio;
			if (width > height * actualRatio) {
				newHeight = height;
				newWidth = Math.round(height * actualRatio);
			} else {
				newWidth = width;
				newHeight = Math.round(width / actualRatio);
			}

			LOG.v(TAG, String.format("Measured dimensions set: %s x %s", newWidth, newHeight));
			setMeasuredDimension(newWidth, newHeight);
		}
	}
}