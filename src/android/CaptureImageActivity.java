package org.apache.cordova.mediacapture;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.constraint.ConstraintLayout;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.AppCompatImageButton;
import android.support.v7.widget.AppCompatImageView;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.apache.cordova.BuildConfig;
import org.apache.cordova.LOG;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@SuppressLint({"LogNotTimber", "ClickableViewAccessibility"})
public class CaptureImageActivity extends Activity implements View.OnTouchListener {
	private static final String TAG = CaptureImageActivity.class.getSimpleName();

	private static final int REQUEST_CAMERA_PERMISSION = 1;
	private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

	static {
		ORIENTATIONS.append(Surface.ROTATION_0, 90);
		ORIENTATIONS.append(Surface.ROTATION_90, 0);
		ORIENTATIONS.append(Surface.ROTATION_180, 270);
		ORIENTATIONS.append(Surface.ROTATION_270, 180);
	}

	//Views
	private FrameLayout mCameraPreviewLayout;
	private AutoFitTextureView mCameraPreviewTexture;
	private AppCompatImageButton mChangeFlashModeButton;

	private ConstraintLayout mPicturePreviewLayout;
	private AppCompatImageView mCapturedImageView;

	/**
	 * Flash mode
	 */
	private boolean mUseFlash = false;

	/**
	 * Camera state: Showing camera preview.
	 */
	private static final int STATE_PREVIEW = 0;

	/**
	 * Camera state: Waiting for the focus to be locked.
	 */
	private static final int STATE_WAITING_LOCK = 1;

	/**
	 * Camera state: Waiting for the exposure to be precapture state.
	 */
	private static final int STATE_WAITING_PRECAPTURE = 2;

	/**
	 * Camera state: Waiting for the exposure state to be something other than precapture.
	 */
	private static final int STATE_WAITING_NON_PRECAPTURE = 3;

	/**
	 * Camera state: Picture was taken.
	 */
	private static final int STATE_PICTURE_TAKEN = 4;

	/**
	 * Max preview width that is guaranteed by Camera2 API
	 */
	private static final int MAX_PREVIEW_WIDTH = 1920;

	/**
	 * Max preview height that is guaranteed by Camera2 API
	 */
	private static final int MAX_PREVIEW_HEIGHT = 1080;

	/**
	 * Listener for runtime orientation changes
	 */
	private OrientationEventListener orientationEventListener;

	/**
	 * holds current orientation
	 */
	private int currentOrientation;

	/**
	 * hold current rotation
	 */
	private int currentRotation;

	/**
	 * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
	 * {@link TextureView}.
	 */
	private final TextureView.SurfaceTextureListener mSurfaceTextureListener
			= new TextureView.SurfaceTextureListener() {

		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
			openCamera(width, height);
		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
			configureTransform(width, height);
		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
			return true;
		}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture texture) {
		}

	};

	/**
	 * ID of the current {@link CameraDevice}.
	 */
	private String mCameraId;

	/**
	 * Characteristics of used camera
	 */
	private CameraCharacteristics mCameraInfo;

	/**
	 * A {@link CameraCaptureSession } for camera preview.
	 */
	private CameraCaptureSession mCaptureSession;

	/**
	 * A reference to the opened {@link CameraDevice}.
	 */
	private CameraDevice mCameraDevice;

	/**
	 * The {@link android.util.Size} of camera preview.
	 */
	private Size mPreviewSize;

	/**
	 * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
	 */
	private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

		@Override
		public void onOpened(@NonNull CameraDevice cameraDevice) {
			// This method is called when the camera is opened.  We start camera preview here.
			mCameraOpenCloseLock.release();
			mCameraDevice = cameraDevice;
			createCameraPreviewSession();
			if( orientationEventListener == null ) {
				LOG.d(TAG, "create orientationEventListener");
				orientationEventListener = new OrientationEventListener(CaptureImageActivity.this) {
					@Override
					public void onOrientationChanged(int orientation) {
						handleOnOrientationChanged(orientation);
					}
				};
				orientationEventListener.enable();
			}
		}

		@Override
		public void onDisconnected(@NonNull CameraDevice cameraDevice) {
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
		}

		@Override
		public void onClosed(@NonNull CameraDevice camera) {
			LOG.v(TAG, String.format("camera closed %s", camera.getId()));
			if( orientationEventListener != null ) {
				orientationEventListener.disable();
				orientationEventListener = null;
			}
		}

		@SuppressLint("DefaultLocale")
		@Override
		public void onError(@NonNull CameraDevice cameraDevice, int error) {
			String msg;
			switch (error) {
				case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
					msg = String.format("Error code %d - Camera device already in use", error);
					break;
				case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
					msg = String.format("Error code %d - Too many open cameras", error);
					break;
				case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
					msg = String.format("Error code %d - Could not open camera due to a device policy", error);
					break;
				case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
					msg = String.format("Error code %d - Camera device encountered a fatal camera error", error);
					break;
				case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
					msg = String.format("Error code %d - Camera service encountered a fatal camera error", error);
					break;
				default:
					msg = String.format("Error code %d - Unknown error", error);
			}
			LOG.e(TAG, msg);
			showToast(msg);
			mCameraOpenCloseLock.release();
			cameraDevice.close();
			mCameraDevice = null;
			finish();
		}
	};

	private void handleOnOrientationChanged(int orientation) {
		if( orientation == OrientationEventListener.ORIENTATION_UNKNOWN )
			return;
		if( mCameraInfo == null ) {
			return;
		}
		orientation = (orientation + 45) / 90 * 90;
		this.currentOrientation = orientation % 360;
		int new_rotation;
		int cameraOrientation = mCameraInfo.get(CameraCharacteristics.SENSOR_ORIENTATION);
		if( (mCameraInfo.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) ) {
			new_rotation = (cameraOrientation - orientation + 360) % 360;
		}
		else {
			new_rotation = (cameraOrientation + orientation) % 360;
		}
		if( new_rotation != currentRotation ) {
			LOG.d(TAG, "    currentOrientation " + currentOrientation);
			LOG.d(TAG, "    cameraOrientation " + cameraOrientation);
			LOG.d(TAG, "    set Camera rotation from " + currentRotation + " to " + new_rotation);
			this.currentRotation = new_rotation;
		}
	}

	/**
	 * An additional thread for running tasks that shouldn't block the UI.
	 */
	private HandlerThread mBackgroundThread;

	/**
	 * A {@link Handler} for running tasks in the background.
	 */
	private Handler mBackgroundHandler;

	/**
	 * An {@link ImageReader} that handles still image capture.
	 */
	private ImageReader mImageReader;

	/**
	 * This is the output file for our picture.
	 */
	private File mFile;

	/**
	 * This the output uri if MediaStore.EXTRA_OUTPUT Intent extra exists
	 */
	private Uri mSaveFileUri;

	/**
	 * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
	 * still image is ready to be saved.
	 */
	private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
			= new ImageReader.OnImageAvailableListener() {

		@Override
		public void onImageAvailable(ImageReader reader) {
			if(reader == null){
				return;
			}

			//TODO: acquireNextImage for burst

			Image image = reader.acquireLatestImage();
			LOG.v(TAG, String.format("onImageAvailable - null: %s, maxImages: %s, format: %s",image == null, reader.getMaxImages(), reader.getImageFormat()));

			// No image available so continue, cam unavailable, session got lost
			if (image == null || mCameraDevice == null || mCaptureSession == null) {
				LOG.v(TAG, "closing reader");
				return;
			}


			ImageSaver.Callback callback = new ImageSaver.Callback() {
				@Override
				public void onSuccess(@Nullable Bitmap bitmap) {
					runOnUiThread(() -> {
						mCameraPreviewLayout.setVisibility(View.GONE);
						mPicturePreviewLayout.setVisibility(View.VISIBLE);
						mCapturedImageView.setImageBitmap(bitmap);
					});
				}

				@Override
				public void onFailure(Throwable t) {
					LOG.e(TAG, "Failed saving image", t);
					showErrorDialog(String.format("Failed saving image.\n%s", t.getMessage()));
				}
			};

			if (mSaveFileUri == null) {
				mBackgroundHandler.post(
						new ImageSaver(image, mFile, callback));
			} else {
				mBackgroundHandler.post(
						new ImageSaver(CaptureImageActivity.this, image, mSaveFileUri, callback));
			}
		}
	};

	/**
	 * {@link CaptureRequest.Builder} for the camera preview
	 */
	private CaptureRequest.Builder mPreviewRequestBuilder;

	/**
	 * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
	 */
	private CaptureRequest mPreviewRequest;

	/**
	 * The current state of camera state for taking pictures.
	 *
	 * @see #mCaptureCallback
	 */
	private int mState = STATE_PREVIEW;

	/**
	 * A {@link Semaphore} to prevent the app from exiting before closing the camera.
	 */
	private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

	/**
	 * Whether the current camera device supports Flash or not.
	 */
	private boolean mFlashSupported;

	/**
	 * Orientation of the camera sensor
	 */
	private int mSensorOrientation;

	/**
	 * Indicator if camera shall facing front or back
	 */
	//TODO Cycle - Front, Back, Unknown
	private boolean mShowBackCamera = true;

	/**
	 * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
	 */
	private final CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

		@Override
		public void onCaptureProgressed(@NonNull CameraCaptureSession session,
										@NonNull CaptureRequest request,
										@NonNull CaptureResult partialResult) {
			process(partialResult);
		}

		@Override
		public void onCaptureCompleted(@NonNull CameraCaptureSession session,
									   @NonNull CaptureRequest request,
									   @NonNull TotalCaptureResult result) {
			process(result);
		}

		private void process(CaptureResult result) {
			switch (mState) {
				case STATE_PREVIEW: {
					// We have nothing to do when the camera preview is working normally.
					break;
				}
				case STATE_WAITING_LOCK: {
					Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
					if (afState == null) {
						captureStillPicture();
					} else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
							CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState ||
							CaptureResult.CONTROL_AF_STATE_INACTIVE == afState) {
						// CONTROL_AE_STATE can be null on some devices
						Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
						if (aeState == null ||
								aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
							mState = STATE_PICTURE_TAKEN;
							captureStillPicture();
						} else {
							runPrecaptureSequence();
						}
					}
					break;
				}
				case STATE_WAITING_PRECAPTURE: {
					// CONTROL_AE_STATE can be null on some devices
					Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
					if (aeState == null ||
							aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
							aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
						mState = STATE_WAITING_NON_PRECAPTURE;
					}
					break;
				}
				case STATE_WAITING_NON_PRECAPTURE: {
					// CONTROL_AE_STATE can be null on some devices
					Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
					if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
						mState = STATE_PICTURE_TAKEN;
						captureStillPicture();
					}
					break;
				}
			}
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if(BuildConfig.DEBUG){
			LOG.setLogLevel(LOG.VERBOSE);
		}

		setContentView(getResources().getIdentifier("camera_layout", "layout", getPackageName()));

		mCameraPreviewLayout = findViewById(R.getId(this, "cameraPreview"));
		mCameraPreviewTexture = findViewById(R.getId(this, "cameraPreviewTexture"));
		mChangeFlashModeButton = findViewById(R.getId(this, "changeFlashMode"));
		AppCompatImageButton takePictureButton = findViewById(R.getId(this, "takePicture"));
		AppCompatImageButton switchCameraButton = findViewById(R.getId(this, "switchCamera"));

		mPicturePreviewLayout = findViewById(R.getId(this, "picturePreview"));
		mCapturedImageView = findViewById(R.getId(this, "capturedImageView"));
		AppCompatImageButton pictureAcceptButton = findViewById(R.getId(this, "pictureAccept"));
		AppCompatImageButton pictureRepeatButton = findViewById(R.getId(this, "pictureRepeat"));

		mChangeFlashModeButton.setOnClickListener(v -> {
			mUseFlash = !mUseFlash;

			mChangeFlashModeButton.setImageResource(R.getDrawable(v.getContext(),
					mUseFlash ? "mediacap_flash" : "mediacap_flash_off"));

			closeCamera();
			openCamera(mCameraPreviewTexture.getWidth(), mCameraPreviewTexture.getHeight());
		});

		takePictureButton.setOnClickListener(v -> takePicture());

		switchCameraButton.setOnClickListener(v -> {
			mShowBackCamera = !mShowBackCamera;
			closeCamera();
			openCamera(mCameraPreviewTexture.getWidth(), mCameraPreviewTexture.getHeight());
		});

		pictureAcceptButton.setOnClickListener(v -> {
			Intent resultIntent = new Intent();

			if (mSaveFileUri == null) {
				Uri fileUri = Uri.fromFile(mFile);
				resultIntent.setData(fileUri);
			}
			setResult(Activity.RESULT_OK, resultIntent);
			finish();
		});

		pictureRepeatButton.setOnClickListener(v -> {
			mPicturePreviewLayout.setVisibility(View.GONE);
			mCameraPreviewLayout.setVisibility(View.VISIBLE);

			mCapturedImageView.setImageDrawable(null);

			openCamera(mCameraPreviewTexture.getWidth(), mCameraPreviewTexture.getHeight());
		});

		//TODO Pinch to zoom
		mCameraPreviewTexture.setOnTouchListener(this);

		if (getIntent() != null && getIntent().getExtras() != null
				&& getIntent().getExtras().get(MediaStore.EXTRA_OUTPUT) != null) {
			//Intent contain MediaStore.EXTRA_OUTPUT which tells us that the picture have to be saved in MediaStore using ContentResolver
			mSaveFileUri = (Uri) getIntent().getExtras().get(MediaStore.EXTRA_OUTPUT);
		} else {
			String format = DateFormat.getDateInstance().format(new Date());
			mFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), format + ".jpg");
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		startBackgroundThread();

		// When the screen is turned off and turned back on, the SurfaceTexture is already
		// available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
		// a camera and start preview from here (otherwise, we wait until the surface is ready in
		// the SurfaceTextureListener).
		if (mCameraPreviewTexture.isAvailable()) {
			openCamera(mCameraPreviewTexture.getWidth(), mCameraPreviewTexture.getHeight());
		} else {
			mCameraPreviewTexture.setSurfaceTextureListener(mSurfaceTextureListener);
		}
	}

	@Override
	public void onPause() {
		closeCamera();
		stopBackgroundThread();
		super.onPause();
	}

	/**
	 * Sets up member variables related to camera.
	 *
	 * @param width  The width of available size for camera preview
	 * @param height The height of available size for camera preview
	 */
	@SuppressWarnings("SuspiciousNameCombination")
	private void setUpCameraOutputs(int width, int height, boolean showBackCamera) {
		CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

		if (manager == null) {
			// Currently an NPE is thrown when the Camera2API is used but not supported on the
			// device this code runs.
			showErrorDialog("Camera Error");
			return;
		}

		try {
			for (String cameraId : manager.getCameraIdList()) {
				CameraCharacteristics characteristics
						= manager.getCameraCharacteristics(cameraId);

				Integer deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL); // 0 - limited, 1 - full, 2 - legacy, 3 - uber full
				LOG.v(TAG, String.format("Camera hardware level: %s", deviceLevel));

				// We don't use a front facing camera in this sample.
				Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
				if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT && showBackCamera) {
					continue;
				} else if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && !showBackCamera) {
					continue;
				}

				StreamConfigurationMap map = characteristics.get(
						CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
				if (map == null) {
					continue;
				}

				// For still image captures, we use the largest available size.
				Size largest = Collections.max(
						Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
						new CompareSizesByArea());

				if (mImageReader != null) {
					mImageReader.close();
				}

				mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
						ImageFormat.JPEG, /*maxImages*/1);
				mImageReader.setOnImageAvailableListener(
						mOnImageAvailableListener, mBackgroundHandler);

				// Find out if we need to swap dimension to get the preview size relative to sensor
				// coordinate.
				int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
				Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
				mSensorOrientation = sensorOrientation != null ? sensorOrientation : 0;
				boolean swappedDimensions = false;
				switch (displayRotation) {
					case Surface.ROTATION_0:
					case Surface.ROTATION_180:
						if (mSensorOrientation == 90 || mSensorOrientation == 270) {
							swappedDimensions = true;
						}
						break;
					case Surface.ROTATION_90:
					case Surface.ROTATION_270:
						if (mSensorOrientation == 0 || mSensorOrientation == 180) {
							swappedDimensions = true;
						}
						break;
					default:
						LOG.e(TAG, "Display rotation is invalid: " + displayRotation);
				}

				Point displaySize = new Point();
				getWindowManager().getDefaultDisplay().getSize(displaySize);
				int rotatedPreviewWidth = width;
				int rotatedPreviewHeight = height;
				int maxPreviewWidth = displaySize.x;
				int maxPreviewHeight = displaySize.y;

				if (swappedDimensions) {
					rotatedPreviewWidth = height;
					rotatedPreviewHeight = width;
					maxPreviewWidth = displaySize.y;
					maxPreviewHeight = displaySize.x;
				}

				if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
					maxPreviewWidth = MAX_PREVIEW_WIDTH;
				}

				if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
					maxPreviewHeight = MAX_PREVIEW_HEIGHT;
				}

				// Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
				// bus' bandwidth limitation, resulting in gorgeous previews but the storage of
				// garbage capture data.
				mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
						rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
						maxPreviewHeight, largest);

				// We fit the aspect ratio of TextureView to the size of preview we picked.
				int orientation = getResources().getConfiguration().orientation;
				mCameraPreviewTexture.setAspectRatio(
						mPreviewSize.getWidth(), mPreviewSize.getHeight());

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
					// Check if the flash is supported.
					Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
					mFlashSupported = available == null ? false : available;
				} else {
					LOG.w(TAG, "Torch mode is unavailable");
					mFlashSupported = false;
				}

				mChangeFlashModeButton.setVisibility(mFlashSupported ? View.VISIBLE : View.GONE);

				mCameraInfo = characteristics;
				mCameraId = cameraId;

				return;
			}
		} catch (CameraAccessException e) {
			LOG.e(TAG, "Setting camera outputs failed", e);
			showErrorDialog(String.format("Failed opening camera.\n%s", e.getMessage()));
		}
	}

	/**
	 * Opens the camera specified by {@link CaptureImageActivity#mCameraId}.
	 */
	private void openCamera(int width, int height) {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED) {
			requestCameraPermission();
			return;
		}

		setUpCameraOutputs(width, height, mShowBackCamera);
		configureTransform(width, height);
		CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
		try {
			if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
				throw new RuntimeException("Time out waiting to lock camera opening.");
			}

			if (manager == null) {
				// Currently an NPE is thrown when the Camera2API is used but not supported on the
				// device this code runs.
				showErrorDialog("Camera Error");
				return;
			}

			manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
		} catch (Exception e) {
			LOG.e(TAG, "Failed opening camera", e);
			showErrorDialog(String.format("Failed opening camera.\n%s", e.getMessage()));
		}
	}

	/**
	 * Closes the current {@link CameraDevice}.
	 */
	private void closeCamera() {
		try {
			mCameraOpenCloseLock.acquire();
			if (mCaptureSession != null) {
				mCaptureSession.close();
				mCaptureSession = null;
			}
			if (mCameraDevice != null) {
				mCameraDevice.close();
				mCameraDevice = null;
			}
			if (mImageReader != null) {
				mImageReader.close();
				mImageReader = null;
			}
			if( orientationEventListener != null ) {
				LOG.d(TAG, "free orientationEventListener");
				orientationEventListener.disable();
				orientationEventListener = null;
			}
		} catch (InterruptedException e) {
			LOG.e(TAG, "Failed closing camera", e);
			showErrorDialog(String.format("Failed opening camera.\n%s", e.getMessage()));
		} finally {
			mCameraOpenCloseLock.release();
		}
	}

	/**
	 * Starts a background thread and its {@link Handler}.
	 */
	private void startBackgroundThread() {
		mBackgroundThread = new HandlerThread("CameraBackground");
		mBackgroundThread.start();
		mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
	}

	/**
	 * Stops the background thread and its {@link Handler}.
	 */
	private void stopBackgroundThread() {
		mBackgroundThread.quitSafely();
		try {
			mBackgroundThread.join();
			mBackgroundThread = null;
			mBackgroundHandler = null;
		} catch (InterruptedException e) {
			LOG.e(TAG, "stopping background thread failed", e);
		}
	}

	/**
	 * Creates a new {@link CameraCaptureSession} for camera preview.
	 */
	private void createCameraPreviewSession() {
		try {
			SurfaceTexture texture = mCameraPreviewTexture.getSurfaceTexture();
			assert texture != null;

			// We configure the size of default buffer to be the size of camera preview we want.
			texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

			// This is the output Surface we need to start preview.
			Surface surface = new Surface(texture);

			// We set up a CaptureRequest.Builder with the output Surface.
			mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			mPreviewRequestBuilder.addTarget(surface);

			// Here, we create a CameraCaptureSession for camera preview.
			mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
					new CameraCaptureSession.StateCallback() {

						@Override
						public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
							LOG.v(TAG, "preview configured");
							// The camera is already closed
							if (null == mCameraDevice) {
								return;
							}

							// When the session is ready, we start displaying the preview.
							mCaptureSession = cameraCaptureSession;
							try {
								// Auto focus should be continuous for camera preview.
								mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
										CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
								// Flash is automatically enabled when necessary.
								setFlashMode(mPreviewRequestBuilder);

								// Finally, we start displaying the camera preview.
								mPreviewRequest = mPreviewRequestBuilder.build();
								mCaptureSession.setRepeatingRequest(mPreviewRequest,
										mCaptureCallback, mBackgroundHandler);
							} catch (Exception e) {
								LOG.e(TAG, "Previewing camera session failed", e);
								showToast("Failed showing preview");
								surface.release();
							}
						}

						@Override
						public void onConfigureFailed(
								@NonNull CameraCaptureSession cameraCaptureSession) {
							LOG.e(TAG, "Configuration failed");
							showToast("Failed showing preview");
							surface.release();
							finish();
						}
					}, null
			);
		} catch (CameraAccessException e) {
			LOG.e(TAG, "Failed creating camera preview session", e);
		}
	}

	/**
	 * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
	 * This method should be called after the camera preview size is determined in
	 * setUpCameraOutputs and also the size of `mTextureView` is fixed.
	 *
	 * @param viewWidth  The width of `mTextureView`
	 * @param viewHeight The height of `mTextureView`
	 */
	private void configureTransform(int viewWidth, int viewHeight) {
		if (null == mCameraPreviewTexture || null == mPreviewSize) {
			return;
		}
		LOG.v(TAG, String.format("configureTransform: viewWidth %s, viewHeight %s", viewWidth, viewHeight));
		int rotation = getWindowManager().getDefaultDisplay().getRotation();
		Matrix matrix = new Matrix();
		RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
		RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
		float centerX = viewRect.centerX();
		float centerY = viewRect.centerY();
		if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
			bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
			matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
			float scale = Math.max(
					(float) viewHeight / mPreviewSize.getHeight(),
					(float) viewWidth / mPreviewSize.getWidth());
			matrix.postScale(scale, scale, centerX, centerY);
			matrix.postRotate(90 * (rotation - 2), centerX, centerY);
		} else if (Surface.ROTATION_180 == rotation) {
			matrix.postRotate(180, centerX, centerY);
		}
		LOG.v(TAG, String.format("configureTransform matrix: %s", matrix.toString()));
		mCameraPreviewTexture.setTransform(matrix);
	}

	/**
	 * Initiate a still image capture.
	 */
	private void takePicture() {
		lockFocus();
	}

	/**
	 * Lock the focus as the first step for a still image capture.
	 */
	private void lockFocus() {
		try {
			// This is how to tell the camera to lock focus.
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
					CameraMetadata.CONTROL_AF_TRIGGER_START);
			// Tell #mCaptureCallback to wait for the lock.
			mState = STATE_WAITING_LOCK;
			mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
					mBackgroundHandler);
		} catch (CameraAccessException e) {
			LOG.e(TAG, "Failed locking focus", e);
		}
	}

	/**
	 * Run the precapture sequence for capturing a still image. This method should be called when
	 * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
	 */
	private void runPrecaptureSequence() {
		try {
			// This is how to tell the camera to trigger.
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
					CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
			// Tell #mCaptureCallback to wait for the precapture sequence to be set.
			mState = STATE_WAITING_PRECAPTURE;
			mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
					mBackgroundHandler);
		} catch (CameraAccessException e) {
			LOG.e(TAG, "Precapturing failed", e);
		}
	}

	/**
	 * Capture a still picture. This method should be called when we get a response in
	 * {@link #mCaptureCallback} from both {@link #lockFocus()}.
	 */
	private void captureStillPicture() {
		try {
			if (null == mCameraDevice) {
				return;
			}
			// This is the CaptureRequest.Builder that we use to take a picture.
			final CaptureRequest.Builder captureBuilder =
					mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			captureBuilder.addTarget(mImageReader.getSurface());

			// Use the same AE and AF modes as the preview.
			captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
			setFlashMode(captureBuilder);

			// Orientation
			captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getImageVideoRotation());

			CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {

				@Override
				public void onCaptureStarted(@NonNull CameraCaptureSession session,
											 @NonNull CaptureRequest request,
											 long timestamp,
											 long frameNumber) {
					LOG.v(TAG, String.format("onCaptureStarted - Device ID: %s | timestamp: %s | frameNumber: %s",
							session.getDevice().getId(), timestamp, frameNumber));
				}

				@Override
				public void onCaptureProgressed(@NonNull CameraCaptureSession session,
												@NonNull CaptureRequest request,
												@NonNull CaptureResult partialResult) {
					LOG.v(TAG, String.format("onCaptureProgressed - Device ID: %s | partial frameNumber: %s",
							session.getDevice().getId(), partialResult.getFrameNumber()));
				}

				@Override
				public void onCaptureCompleted(@NonNull CameraCaptureSession session,
											   @NonNull CaptureRequest request,
											   @NonNull TotalCaptureResult result) {
					LOG.v(TAG, String.format("onCaptureCompleted - Device ID: %s | result frameNumber: %s",
							session.getDevice().getId(), result.getFrameNumber()));
					unlockFocus();
				}

				@Override
				public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
					super.onCaptureFailed(session, request, failure);
					LOG.e(TAG, String.valueOf(failure.getReason()));
				}
			};

			mCaptureSession.stopRepeating();
			mCaptureSession.abortCaptures();
			mCaptureSession.capture(captureBuilder.build(), captureCallback, null);
		} catch (CameraAccessException e) {
			LOG.e(TAG, "Capturing still picture failed", e);
		}
	}

	/**
	 * Retrieves the JPEG orientation from the specified screen rotation.
	 *
	 * @param rotation The screen rotation.
	 * @return The JPEG orientation (one of 0, 90, 270, and 360)
	 */
	private int getOrientation(int rotation) {
		// Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
		// We have to take that into account and rotate JPEG properly.
		// For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
		// For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
		return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
	}

	/**
	 * Unlock the focus. This method should be called when still image capture sequence is
	 * finished.
	 */
	private void unlockFocus() {
		try {
			// Reset the auto-focus trigger
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
					CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
			setFlashMode(mPreviewRequestBuilder);
			mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
					mBackgroundHandler);
			// After this, the camera will go back to the normal state of preview.
			mState = STATE_PREVIEW;
			mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
					mBackgroundHandler);
		} catch (CameraAccessException e) {
			LOG.e(TAG, "Unlocking focus failed", e);
		}
	}

	private void setFlashMode(CaptureRequest.Builder requestBuilder) {
		//TODO Add all flash modes (red eye, auto)
		if (mFlashSupported) {
			if (mUseFlash) {
				requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
				requestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
			} else {
				requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
				requestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
			}
		}

		// Adding FPS range to fix dark preview on some devices
		Range<Integer> fpsRange = getRange(mCameraInfo);
		if (fpsRange != null) {
			requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
		}
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);

		if(null != mCameraPreviewTexture)
			mCameraPreviewTexture.setAspectRatio(
					mPreviewSize.getWidth(), mPreviewSize.getHeight());
	}

	/**
	 * Compares two {@code Size}s based on their areas.
	 */
	static class CompareSizesByArea implements Comparator<Size> {

		@Override
		public int compare(Size lhs, Size rhs) {
			// We cast here to ensure the multiplications won't overflow
			return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
					(long) rhs.getWidth() * rhs.getHeight());
		}
	}

	/**
	 * Shows an error message dialog.
	 */
	private void showErrorDialog(String msg) {
		runOnUiThread(() -> new AlertDialog.Builder(CaptureImageActivity.this)
				.setMessage(msg)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
				.create()
				.show());
	}

	private void requestCameraPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
				new AlertDialog.Builder(CaptureImageActivity.this)
						.setMessage("Error requesting permission")
						.setPositiveButton(android.R.string.ok, (dialog, which) ->
								CaptureImageActivity.this.requestPermissions(new String[]{Manifest.permission.CAMERA},
										REQUEST_CAMERA_PERMISSION))
						.setNegativeButton(android.R.string.cancel,
								(dialog, which) -> CaptureImageActivity.this.finish())
						.create()
						.show();
			} else {
				requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
			}
		} else {
			//Source: https://developer.android.com/training/permissions/requesting.html#declare-by-api-level
			LOG.w(TAG, "Permission should be granted. Devices with SDK 22 and lower can't request permissions");
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
										   @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSION) {
			if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
				showErrorDialog("Error requesting permission");
			}
		} else {
			super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}

	private boolean mManualFocusEngaged = false;

	/**
	 * Called when a touch event is dispatched to a view. This allows listeners to
	 * get a chance to respond before the target view.
	 *
	 * @param view        The view the touch event has been dispatched to.
	 * @param motionEvent The MotionEvent object containing full information about
	 *                    the event.
	 * @return True if the listener has consumed the event, false otherwise.
	 */
	@Override
	public boolean onTouch(View view, MotionEvent motionEvent) {
		//Source: https://gist.github.com/royshil/8c760c2485257c85a11cafd958548482

		final int actionMasked = motionEvent.getActionMasked();
		if (actionMasked != MotionEvent.ACTION_DOWN) {
			return false;
		}
		if (mManualFocusEngaged) {
			LOG.d(TAG, "Manual focus already engaged");
			return true;
		}

		final Rect sensorArraySize = mCameraInfo.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

		//TODO: here I just flip x,y, but this needs to correspond with the sensor orientation (via SENSOR_ORIENTATION)
		final int y = (int) ((motionEvent.getX() / (float) view.getWidth()) * (float) sensorArraySize.height());
		final int x = (int) ((motionEvent.getY() / (float) view.getHeight()) * (float) sensorArraySize.width());
		final int halfTouchWidth = 150; //(int)motionEvent.getTouchMajor(); //TODO: this doesn't represent actual touch size in pixel. Values range in [3, 10]...
		final int halfTouchHeight = 150; //(int)motionEvent.getTouchMinor();
		MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
				Math.max(y - halfTouchHeight, 0),
				halfTouchWidth * 2,
				halfTouchHeight * 2,
				MeteringRectangle.METERING_WEIGHT_MAX - 1);

		CameraCaptureSession.CaptureCallback captureCallbackHandler = new CameraCaptureSession.CaptureCallback() {
			@Override
			public void onCaptureCompleted(@NotNull CameraCaptureSession session,
										   @NotNull CaptureRequest request,
										   @NotNull TotalCaptureResult result) {
				super.onCaptureCompleted(session, request, result);
				mManualFocusEngaged = false;

				if (request.getTag() == "FOCUS_TAG") {
					//the focus trigger is complete -
					//resume repeating (preview surface will get frames), clear AF trigger
					mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null);
					try {
						session.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
					} catch (CameraAccessException e) {
						LOG.e(TAG, "Failed triggering focus", e);
					}
				}
			}

			@Override
			public void onCaptureFailed(@NotNull CameraCaptureSession session,
										@NotNull CaptureRequest request,
										@NotNull CaptureFailure failure) {
				super.onCaptureFailed(session, request, failure);
				LOG.e(TAG, "Manual AF failure: " + failure);
				mManualFocusEngaged = false;
			}
		};

		try {
			//first stop the existing repeating request
			mCaptureSession.stopRepeating();

			//cancel any existing AF trigger (repeated touches, etc.)
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
			mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, mBackgroundHandler);

			//Now add a new AF trigger with focus region
			if (isMeteringAreaAFSupported()) {
				mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusAreaTouch});
			}
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
			mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
			mPreviewRequestBuilder.setTag("FOCUS_TAG"); //we'll capture this later for resuming the preview

			//then we ask for a single request (not repeating!)
			mCaptureSession.capture(mPreviewRequestBuilder.build(), captureCallbackHandler, mBackgroundHandler);
			mManualFocusEngaged = true;
		} catch (Exception e) {
			LOG.e(TAG, "Failed focusing", e);
		}

		return true;
	}

	private boolean isMeteringAreaAFSupported() {
		Integer afRegion = mCameraInfo.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
		return afRegion != null && afRegion >= 1;
	}

	/**
	 * Shows a {@link Toast} on the UI thread.
	 *
	 * @param text The message to show
	 */
	private void showToast(final String text) {
		runOnUiThread(() ->
				Toast.makeText(CaptureImageActivity.this, text, Toast.LENGTH_SHORT).show());
	}

	/**
	 * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
	 * is at least as large as the respective texture view size, and that is at most as large as the
	 * respective max size, and whose aspect ratio matches with the specified value. If such size
	 * doesn't exist, choose the largest one that is at most as large as the respective max size,
	 * and whose aspect ratio matches with the specified value.
	 *
	 * @param choices           The list of sizes that the camera supports for the intended output
	 *                          class
	 * @param textureViewWidth  The width of the texture view relative to sensor coordinate
	 * @param textureViewHeight The height of the texture view relative to sensor coordinate
	 * @param maxWidth          The maximum width that can be chosen
	 * @param maxHeight         The maximum height that can be chosen
	 * @param aspectRatio       The aspect ratio
	 * @return The optimal {@code Size}, or an arbitrary one if none were big enough
	 */
	private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
										  int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

		// Collect the supported resolutions that are at least as big as the preview Surface
		List<Size> bigEnough = new ArrayList<>();
		// Collect the supported resolutions that are smaller than the preview Surface
		List<Size> notBigEnough = new ArrayList<>();
		int w = aspectRatio.getWidth();
		int h = aspectRatio.getHeight();
		for (Size option : choices) {
			if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
					option.getHeight() == option.getWidth() * h / w) {
				if (option.getWidth() >= textureViewWidth &&
						option.getHeight() >= textureViewHeight) {
					bigEnough.add(option);
				} else {
					notBigEnough.add(option);
				}
			}
		}

		// Pick the smallest of those big enough. If there is no one big enough, pick the
		// largest of those not big enough.
		if (bigEnough.size() > 0) {
			return Collections.min(bigEnough, new CompareSizesByArea());
		} else if (notBigEnough.size() > 0) {
			return Collections.max(notBigEnough, new CompareSizesByArea());
		} else {
			LOG.e(TAG, "Couldn't find any suitable preview size");
			return choices[0];
		}
	}

	private Range<Integer> getRange(CameraCharacteristics characteristics) {
		Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

		Range<Integer> result = null;

		if (ranges == null) {
			return null;
		}

		for (Range<Integer> range : ranges) {
			int upper = range.getUpper();

			// 10 - min range upper for my needs
			if (upper >= 10) {
				if (result == null || upper < result.getUpper().intValue()) {
					result = range;
				}
			}
		}

		if (result == null) {
			result = ranges[0];
		}

		return result;
	}

	/* Returns the rotation (in degrees) to use for images/videos, taking the preference_lock_orientation into account.
	 */
	private int getImageVideoRotation() {
		LOG.d(TAG, "getImageVideoRotation() from currentRotation " + currentRotation);
		return this.currentRotation;
	}
}
