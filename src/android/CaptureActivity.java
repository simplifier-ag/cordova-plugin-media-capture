package org.apache.cordova.mediacapture;

import static org.apache.cordova.mediacapture.Capture.CAPTURE_IMAGE;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.PopupMenu;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.otaliastudios.cameraview.BitmapCallback;
import com.otaliastudios.cameraview.CameraException;
import com.otaliastudios.cameraview.CameraListener;
import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.CameraOptions;
import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.PictureResult;
import com.otaliastudios.cameraview.VideoResult;
import com.otaliastudios.cameraview.controls.Facing;
import com.otaliastudios.cameraview.controls.Flash;
import com.otaliastudios.cameraview.controls.Mode;
import com.otaliastudios.cameraview.controls.PictureFormat;
import com.otaliastudios.cameraview.gesture.Gesture;
import com.otaliastudios.cameraview.gesture.GestureAction;
import com.otaliastudios.cameraview.size.SizeSelector;
import com.otaliastudios.cameraview.size.SizeSelectors;

import org.apache.cordova.BuildConfig;
import org.apache.cordova.LOG;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public class CaptureActivity extends AppCompatActivity {
	private final static String TAG = CaptureActivity.class.getSimpleName();

	//Views
	private CameraView mCameraView;
	private FrameLayout mCameraPreviewLayout;
	private AppCompatImageButton mChangeFlashModeMenu;
	private AppCompatImageButton mCaptureButton;

	private ConstraintLayout mPicturePreviewLayout;
	private AppCompatImageView mCapturedImageView;

	private AppCompatImageButton mPictureRotateRightButton;
	private AppCompatImageButton mPictureRotateLeftButton;
	private AppCompatImageButton mSwitchCameraButton;

	private ConstraintLayout mVideoPreviewLayout;
	private VideoView mVideoView;
	private LinearLayoutCompat mRecordStats;
	private AppCompatTextView mRecordingDurationView;
	private AppCompatImageView mRecordIcon;
	private Animation mBlink;
	private ConstraintLayout mProgressIndicator;
	private MenuItem mFlashOnMenu, mFlashOffMenu, mFlashAutoMenu, mFlashTorchMenu;

	private MediaController mMediaController;
	/**
	 * indicates if a video is recorded
	 * false = image
	 * true = video
	 */
	private boolean isVideo = false;
	/**
	 * recording limit in seconds, 0 = unlimited
	 */
	private int mDuration = 0;

	/**
	 * quality of recording. 0 = low, 1 (default) = high
	 */
	private int mQuality = 1;

	/**
	 * keeps track where the cam is facing
	 */
	private boolean mCamFacingBack = false;

	/**
	 * target file uri
	 */
	private Uri mSaveFileUri;

	/**
	 * used to display reconding duration
	 */
	private CountDownTimer mDurationTimer;

	/**
	 * indicates if the capture was canceled by back press
	 */
	private boolean mWasCanceled = false;

	/**
	 * fail counter, used to remove failed resolutions from capture sizes
	 */
	private int didFail = -1;
	/**
	 * A {@link Handler} for running tasks in the background.
	 */
	private Handler mBackgroundHandler;

	/**
	 * An additional thread for running tasks that shouldn't block the UI.
	 */
	private HandlerThread mBackgroundThread;

	private final CameraListener cameraListener = new CameraListener() {
		@Override
		public void onCameraOpened(@NonNull CameraOptions options) {
			LOG.v(TAG, "onCameraOpened: %s", options);

			if (options.getSupportedFacing().size() < 2) {
				mSwitchCameraButton.setVisibility(View.GONE);
			} else {
				mSwitchCameraButton.setVisibility(View.VISIBLE);
			}
			mCameraView.setFlash(Flash.OFF);
			mChangeFlashModeMenu.setImageResource(R.getDrawable(CaptureActivity.this, "mediacap_flash_off"));

			if (options.getSupportedFlash().isEmpty() ||
					(options.getSupportedFlash().size() == 1 && options.getSupportedFlash().contains(Flash.OFF))) {
				//none or Off is available
				mChangeFlashModeMenu.setVisibility(View.GONE);
			} else {
				mChangeFlashModeMenu.setVisibility(View.VISIBLE);
				mFlashAutoMenu.setVisible(!(mCameraView.getMode() == Mode.VIDEO) && options.getSupportedFlash().contains(Flash.AUTO));
				mFlashOnMenu.setVisible(!(mCameraView.getMode() == Mode.VIDEO) && options.getSupportedFlash().contains(Flash.ON));
				mFlashOffMenu.setVisible(options.getSupportedFlash().contains(Flash.OFF));
				mFlashTorchMenu.setVisible(options.getSupportedFlash().contains(Flash.TORCH));
			}
		}

		@Override
		public void onCameraClosed() {
			LOG.v(TAG, "onCameraClosed");
		}

		@Override
		public void onCameraError(@NonNull CameraException exception) {
			LOG.v(TAG, "onCameraError", exception);
			didFail++;

			//will cause the resolution selector to be called again
			mCameraView.close();
			mCameraView.open();

			//restart capturer until no valid resolutions are left to be tried
			if (mCameraView.getMode() == Mode.VIDEO) {
				captureVideo();
			} else {
				capturePhoto();
			}
		}

		@Override
		public void onPictureTaken(@NonNull PictureResult result) {
			LOG.v(TAG, "onPictureTaken - size %s", result.getSize());

			setLoadingIndicator(true);
			result.toBitmap(new BitmapCallback() {
				@Override
				public void onBitmapReady(@Nullable Bitmap bitmap) {
					ImageSaver.Callback callback = new ImageSaver.Callback() {
						@Override
						public void onSuccess(@NonNull final Bitmap bitmap) {
							runOnUiThread(() -> {

								mCameraPreviewLayout.setVisibility(View.GONE);
								mPicturePreviewLayout.setVisibility(View.VISIBLE);

								mCameraView.close();

								mCapturedImageView.setImageBitmap(bitmap);

								mPictureRotateRightButton.setOnClickListener(v -> {
									setLoadingIndicator(true);
									mBackgroundHandler.post(
											new ImageSaver(CaptureActivity.this, bitmap, mSaveFileUri, 90f, this)
									);
								});
								mPictureRotateLeftButton.setOnClickListener(v -> {
									setLoadingIndicator(true);
									mBackgroundHandler.post(
											new ImageSaver(CaptureActivity.this, bitmap, mSaveFileUri, -90f, this)
									);
								});
								setLoadingIndicator(false);
							});
						}

						@Override
						public void onFailure(Throwable t) {
							runOnUiThread(() -> {

								setLoadingIndicator(false);
								LOG.e(TAG, "Failed saving image", t);
								Helper.showErrorDialog(String.format("Failed saving image.\n%s", t.getMessage()), CaptureActivity.this);
							});
						}
					};

					if (bitmap == null) {
						Helper.showErrorDialog("could not create bitmap", CaptureActivity.this);
						return;
					}

					mBackgroundHandler.post(
							new ImageSaver(CaptureActivity.this, bitmap, mSaveFileUri, callback));
				}
			});
		}

		@Override
		public void onVideoTaken(@NonNull VideoResult result) {
			LOG.v(TAG, "onVideoTaken size: %s", result.getSize());

			if (mDurationTimer != null) {
				mDurationTimer.cancel();
			}

			mCameraView.close();

			mRecordStats.setVisibility(View.GONE);
			mRecordIcon.clearAnimation();

			if (!mWasCanceled) {
				mCameraPreviewLayout.setVisibility(View.GONE);
				mVideoPreviewLayout.setVisibility(View.VISIBLE);
				mVideoView.setVideoURI(mSaveFileUri);
				mCaptureButton.setImageResource(R.getDrawable(CaptureActivity.this, "mediacap_capture"));
			} else {
				deleteRecording();

				Intent resultIntent = new Intent();
				setResult(Activity.RESULT_CANCELED, resultIntent);
				finish();
			}
		}

		@Override
		public void onAutoFocusStart(@NonNull PointF point) {
			LOG.d(TAG, String.format("onAutoFocusStart %s", point));
		}

		@Override
		public void onAutoFocusEnd(boolean successful, @NonNull PointF point) {
			LOG.d(TAG, String.format("successful %s", successful));
		}

		@Override
		public void onZoomChanged(float newValue, @NonNull float[] bounds, @Nullable PointF[] fingers) {
			LOG.d(TAG, String.format(Locale.getDefault(),
					"onZoomChanged: newValue %f | bounds %s | fingers %s",
					newValue, Arrays.toString(bounds), Arrays.toString(fingers)));
		}

		@Override
		public void onExposureCorrectionChanged(float newValue, @NonNull float[] bounds, @Nullable PointF[] fingers) {
			LOG.d(TAG, String.format(Locale.getDefault(),
					"onExposureCorrectionChanged: newValue %f | bounds %s | fingers %s",
					newValue, Arrays.toString(bounds), Arrays.toString(fingers)));
		}

		@Override
		public void onVideoRecordingStart() {
			LOG.d(TAG, "onVideoRecordingStart");
			Helper.lockOrientation(CaptureActivity.this);
			mCaptureButton.setImageResource(
					R.getDrawable(CaptureActivity.this, "mediacap_stop"));
			mSwitchCameraButton.setVisibility(View.GONE);
			mDurationTimer = new CountDownTimer(mDuration > 0 ? (mDuration + 1) * 1000L : Integer.MAX_VALUE, 1000) {
				int time = 0;

				@Override
				public void onTick(long millisUntilFinished) {
					updateDurationView(time);
					time++;
				}

				@Override
				public void onFinish() {
					Toast.makeText(
							CaptureActivity.this,
							R.localize(
									CaptureActivity.this,
									"mediacap_capture_limit_reached"),
							Toast.LENGTH_LONG
					).show();
					stopCaptureVideo(false);
					updateDurationView(0);
				}
			};
			mDurationTimer.start();
			mRecordStats.setVisibility(View.VISIBLE);
			mRecordIcon.startAnimation(mBlink);
		}

		@Override
		public void onVideoRecordingEnd() {
			//is called, even if the capturer crashes
			LOG.d(TAG, "onVideoRecordingEnd");

			Helper.unlockOrientation(CaptureActivity.this);
			updateDurationView(0);
			if (mDurationTimer != null) {
				mDurationTimer.cancel();
			}
			mCaptureButton.setImageResource(R.getDrawable(CaptureActivity.this, "mediacap_capture"));
			mRecordIcon.clearAnimation();
		}

		@Override
		public void onPictureShutter() {
			LOG.d(TAG, "onPictureShutter");
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (BuildConfig.DEBUG) {
			LOG.setLogLevel(LOG.VERBOSE);
			CameraLogger.setLogLevel(CameraLogger.LEVEL_INFO);
		} else {
			CameraLogger.setLogLevel(CameraLogger.LEVEL_ERROR);
		}

		getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
				WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
		setContentView(getResources().getIdentifier("mediacap_layout", "layout", getPackageName()));

		Intent intent = getIntent();

		if (intent == null) {
			LOG.e(TAG, "no intent data");
			finish();
			return;
		} else if (intent.getExtras() != null
				&& intent.getExtras().get(MediaStore.EXTRA_OUTPUT) != null) {
			//Intent contain MediaStore.EXTRA_OUTPUT which tells us that the picture have to be saved in MediaStore using ContentResolver
			mSaveFileUri = (Uri) intent.getExtras().get(MediaStore.EXTRA_OUTPUT);
		} else {
			//should not happen
			try {
				mSaveFileUri = FileHelper.getDataUriForMediaFile(CAPTURE_IMAGE, this);
			} catch (IllegalArgumentException | IOException e) {
				LOG.e(TAG, "error creating data uri");
			}
		}

		switch (intent.getAction()) {
			case MediaStore.ACTION_IMAGE_CAPTURE:
				isVideo = false;
				break;
			case MediaStore.ACTION_VIDEO_CAPTURE:
				isVideo = true;

				if (intent.hasExtra(MediaStore.EXTRA_DURATION_LIMIT)) {
					mDuration = intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, mDuration);
				}

				mQuality = intent.getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, mQuality);
				break;
			default:
				break;
		}

		if (mSaveFileUri == null) {
			//should not happen twice
			Helper.showErrorDialog("could not create target file", this);
			return;
		}

		setupViews();
	}

	@Override
	protected void onResume() {
		super.onResume();
		startBackgroundThread();

		mCameraView.open();
	}

	@Override
	protected void onPause() {
		super.onPause();
		stopCaptureVideo(false);
		stopBackgroundThread();

		mCameraView.close();
	}


	@Override
	protected void onDestroy() {
		super.onDestroy();
		mCameraView.destroy();
	}

	@Override
	public void onBackPressed() {
		if (mCameraView.isTakingVideo()) {
			stopCaptureVideo(true);
		} else {
			super.onBackPressed();
		}
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		return super.onOptionsItemSelected(item);
	}


	private void setupViews() {
		mCameraPreviewLayout = findViewById(R.getId(this, "cameraPreview"));
		mChangeFlashModeMenu = findViewById(R.getId(this, "changeFlashMode"));
		mProgressIndicator = findViewById(R.getId(this, "progressIndicator"));

		mCaptureButton = findViewById(R.getId(this, "takePicture"));
		mSwitchCameraButton = findViewById(R.getId(this, "switchCamera"));

		mPicturePreviewLayout = findViewById(R.getId(this, "picturePreview"));
		mCapturedImageView = findViewById(R.getId(this, "capturedImageView"));
		AppCompatImageButton pictureAcceptButton = findViewById(R.getId(this, "pictureAccept"));
		AppCompatImageButton pictureRepeatButton = findViewById(R.getId(this, "pictureRepeat"));
		mPictureRotateRightButton = findViewById(R.getId(this, "rotate_right"));
		mPictureRotateLeftButton = findViewById(R.getId(this, "rotate_left"));

		mVideoPreviewLayout = findViewById(R.getId(this, "videoPreview"));
		mVideoView = findViewById(R.getId(this, "capturedVideoView"));
		AppCompatImageButton videoAcceptButton = findViewById(R.getId(this, "videoAccept"));
		AppCompatImageButton videoRepeatButton = findViewById(R.getId(this, "videoRepeat"));
		AppCompatImageButton videoPlayButton = findViewById(R.getId(this, "videoPlay"));
		mRecordIcon = findViewById(R.getId(this, "record_icon"));
		mRecordStats = findViewById(R.getId(this, "record_stats"));
		mRecordingDurationView = findViewById(R.getId(this, "record_duration"));
		mBlink = AnimationUtils.loadAnimation(this, R.getAnimation(this, "mediacap_blink"));

		final PopupMenu popupMenu = new PopupMenu(this, mChangeFlashModeMenu);
		popupMenu.inflate(R.getMenu(this, "mediacap_flashmodes"));
		popupMenu.setForceShowIcon(true);

		mFlashAutoMenu = popupMenu.getMenu().findItem(R.getId(this, "mediacap_flash_auto"));
		mFlashOnMenu = popupMenu.getMenu().findItem(R.getId(this, "mediacap_flash_on"));
		mFlashOffMenu = popupMenu.getMenu().findItem(R.getId(this, "mediacap_flash_off"));
		mFlashTorchMenu = popupMenu.getMenu().findItem(R.getId(this, "mediacap_flash_torch"));

		mChangeFlashModeMenu.setOnClickListener(v -> popupMenu.show());

		mFlashAutoMenu.setOnMenuItemClickListener(item -> {
			mCameraView.setFlash(Flash.AUTO);
			mChangeFlashModeMenu.setImageDrawable(mFlashAutoMenu.getIcon());
			return true;
		});

		mFlashOnMenu.setOnMenuItemClickListener(item -> {
			mCameraView.setFlash(Flash.ON);
			mChangeFlashModeMenu.setImageDrawable(mFlashOnMenu.getIcon());
			return true;
		});

		mFlashOffMenu.setOnMenuItemClickListener(item -> {
			mCameraView.setFlash(Flash.OFF);
			mChangeFlashModeMenu.setImageDrawable(mFlashOffMenu.getIcon());
			return true;
		});

		mFlashTorchMenu.setOnMenuItemClickListener(item -> {
			mCameraView.setFlash(Flash.TORCH);
			mChangeFlashModeMenu.setImageDrawable(mFlashTorchMenu.getIcon());
			return true;
		});

		mCaptureButton.setOnClickListener(v -> {
			if (mCameraView.getMode() == Mode.VIDEO) {
				mSwitchCameraButton.setVisibility(View.GONE);
				if (!mCameraView.isTakingVideo())
					captureVideo();
				else
					stopCaptureVideo(false);
			} else {
				capturePhoto();
			}
		});

		mSwitchCameraButton.setOnClickListener(v -> {
			mCamFacingBack = !mCamFacingBack;
			mCameraView.setFacing(mCamFacingBack ? Facing.FRONT : Facing.BACK);
		});

		pictureAcceptButton.setOnClickListener(v -> {
			// note, we reference the string directly rather than via Camera.ACTION_NEW_PICTURE,
			// as the latter class is now deprecated - but we still need to broadcast the string for other apps
			sendBroadcast(new Intent("android.hardware.action.NEW_PICTURE", mSaveFileUri));
			// for compatibility with some apps - apparently this is what used to be broadcast on Android?
			sendBroadcast(new Intent("com.android.camera.NEW_PICTURE", mSaveFileUri));

			Intent resultIntent = new Intent();
			resultIntent.setData(mSaveFileUri);
			setResult(Activity.RESULT_OK, resultIntent);
			finish();
		});

		pictureRepeatButton.setOnClickListener(v -> {
			mPicturePreviewLayout.setVisibility(View.GONE);
			mCameraPreviewLayout.setVisibility(View.VISIBLE);

			mCapturedImageView.setImageDrawable(null);
			mCameraView.open();
		});

		videoAcceptButton.setOnClickListener(v -> {
			Intent resultIntent = new Intent();
			resultIntent.setData(mSaveFileUri);
			setResult(Activity.RESULT_OK, resultIntent);
			finish();
		});

		videoRepeatButton.setOnClickListener(v -> {
			mVideoView.suspend();
			mCameraView.open();
			mVideoPreviewLayout.setVisibility(View.GONE);
			mCameraPreviewLayout.setVisibility(View.VISIBLE);
			mSwitchCameraButton.setVisibility(View.VISIBLE);
			if (mSaveFileUri != null) {
				try {
					getContentResolver().delete(mSaveFileUri, null, null);
				} catch (Exception e) {
					LOG.w("error removing file before closing", e);
				}
			}
		});

		mMediaController = new MediaController(this) {
			@Override
			public void show() {
				videoRepeatButton.setVisibility(GONE);
				videoAcceptButton.setVisibility(GONE);
				videoPlayButton.setVisibility(GONE);
				super.show();
			}

			@Override
			public void hide() {
				videoRepeatButton.setVisibility(VISIBLE);
				videoAcceptButton.setVisibility(VISIBLE);
				videoPlayButton.setVisibility(VISIBLE);
				super.hide();
			}
		};

		mVideoView.setMediaController(mMediaController);

		mVideoView.setOnCompletionListener(mp -> {
			if (mp.isPlaying()) {
				videoPlayButton.setImageResource(R.getDrawable(this, "mediacap_pause"));
			} else {
				mp.seekTo(1);
				videoPlayButton.setImageResource(R.getDrawable(this, "mediacap_play"));
			}
		});

		mVideoView.setOnPreparedListener(mp -> {
			videoPlayButton.setImageResource(R.getDrawable(this, "mediacap_play"));
			videoPlayButton.setVisibility(View.VISIBLE);
			mVideoView.seekTo(1); // show first frame
			mMediaController.show(1);

			//scale to video dimensions
			final ConstraintLayout.LayoutParams layoutParams = (ConstraintLayout.LayoutParams) mVideoView.getLayoutParams();
			layoutParams.dimensionRatio = mp.getVideoWidth() + ":" + mp.getVideoHeight();
			mVideoView.setLayoutParams(layoutParams);
		});

		videoPlayButton.setOnClickListener(v -> {
			if (mVideoView.isPlaying()) {
				mVideoView.pause();
				videoPlayButton.setImageResource(R.getDrawable(v.getContext(), "mediacap_play"));
			} else {
				mVideoView.start();
				videoPlayButton.setImageResource(R.getDrawable(v.getContext(), "mediacap_pause"));
			}
		});

		mCameraView = findViewById(R.getId(this, "cameraview"));
		mCameraView.setLifecycleOwner(this);
		mCameraView.addCameraListener(cameraListener);
		//mCameraView.setVideoMaxDuration(mDuration * 1000);

		mCameraView.mapGesture(Gesture.PINCH, GestureAction.ZOOM); // Pinch to zoom!
		mCameraView.mapGesture(Gesture.TAP, GestureAction.AUTO_FOCUS);

		if (isVideo) {
			mCameraView.setMode(Mode.VIDEO);
			mCameraView.setVideoSize(getSize());
		} else {
			mCameraView.setMode(Mode.PICTURE);
			mCameraView.setPictureFormat(PictureFormat.JPEG);
			mCameraView.setPictureSize(SizeSelectors.biggest());
			mCameraView.setPictureSize(getSize());
		}
	}

	private void capturePhoto() {
		LOG.d(TAG, "Capturing picture...");
		mCameraView.takePicture();
	}

	private void captureVideo() {
		if (mCameraView.getMode() != Mode.VIDEO || mCameraView.isTakingVideo()) {
			LOG.d(TAG, "Can't record video while session type is 'picture' or recording.");
			return;
		}

		try {
			FileDescriptor videoFileDescriptor = getContentResolver().openFileDescriptor(mSaveFileUri, "rw").getFileDescriptor();
			mCameraView.takeVideo(videoFileDescriptor);
		} catch (FileNotFoundException e) {
			Helper.showErrorDialog("could not create target file", this);
		}
	}

	private void updateDurationView(long elapsed) {
		runOnUiThread(() -> {
			String current = String.format(Locale.getDefault(), "%d:%02d", (elapsed / 60), (elapsed % 60));
			if (mDuration > 0) {
				String max = String.format(Locale.getDefault(), "%d:%02d", (mDuration / 60), (mDuration % 60));
				mRecordingDurationView.setText(String.format(Locale.getDefault(), "%s / %s",
						current, max));

			} else {
				mRecordingDurationView.setText(String.format(Locale.getDefault(), "%s",
						current));
			}
		});
	}

	private void stopCaptureVideo(boolean wasCanceled) {
		mWasCanceled = wasCanceled;
		if (mCameraView.isTakingVideo())
			mCameraView.stopVideo();
	}

	private void deleteRecording() {
		if (mSaveFileUri != null) {
			try {
				getContentResolver().delete(mSaveFileUri, null, null);
			} catch (Exception e) {
				LOG.w("error removing file before closing", e);
			}
		}
	}

	/**
	 * sorts available resolutions depending on selected quality level
	 *
	 * @return sorted size array depending on quality
	 */
	private SizeSelector getSize() {
		return source -> {
			//sort smallest to highest res
			source.sort(Comparator.comparingInt(o -> (o.getWidth() * o.getWidth())));

			//remove resolutions that might have failed
			if (didFail >= 0) {
				if (mQuality == 0) {
					source = source.subList(didFail + 1, source.size());
				} else {
					source = source.subList(0, source.size() - 1 - didFail);
				}
			}
			if (mQuality == 1)
				source.sort(Comparator.reverseOrder());
			if (source.isEmpty()) {
				Helper.showErrorDialog("could not start capturer", this);
			}

			return source;
		};
	}


	private void setLoadingIndicator(boolean visible) {
		if (mProgressIndicator != null) {
			mProgressIndicator.setVisibility(visible ? View.VISIBLE : View.GONE);
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

}
