package org.apache.cordova.mediacapture;

import static org.apache.cordova.mediacapture.Capture.CAPTURE_IMAGE;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.PointF;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.constraint.ConstraintLayout;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.AppCompatImageButton;
import android.support.v7.widget.AppCompatImageView;
import android.support.v7.widget.AppCompatTextView;
import android.support.v7.widget.LinearLayoutCompat;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.VideoView;

import com.otaliastudios.cameraview.CameraException;
import com.otaliastudios.cameraview.CameraListener;
import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.CameraOptions;
import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.Facing;
import com.otaliastudios.cameraview.Flash;
import com.otaliastudios.cameraview.SessionType;
import com.otaliastudios.cameraview.Size;
import com.otaliastudios.cameraview.VideoQuality;

import org.apache.cordova.BuildConfig;
import org.apache.cordova.LOG;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class CaptureActivity2 extends AppCompatActivity {
	private final static String TAG = CaptureActivity2.class.getSimpleName();

	private CameraView mCameraView;
	//Views
	private FrameLayout mCameraPreviewLayout;
	private AppCompatImageButton mChangeFlashModeButton;
	private AppCompatImageButton mCaptureButton;

	private ConstraintLayout mPicturePreviewLayout;
	private AppCompatImageView mCapturedImageView;

	private AppCompatImageButton mPictureRotateRightButton;
	private AppCompatImageButton mPictureRotateLeftButton;

	private ConstraintLayout mVideoPreviewLayout;
	private VideoView mVideoView;
	private LinearLayoutCompat mRecordStats;
	private AppCompatTextView mRecordingDurationView;
	private AppCompatImageView mRecordIcon;
	private Animation mBlink;
	private ConstraintLayout mProgressIndicator;


	private boolean mCapturingPicture;

	// To show stuff in the callback
	private Size mCaptureNativeSize;
	private long mCaptureTime;

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
	private boolean mUseFlash = false;
	private boolean mShowBackCamera = false;
	private Uri mSaveFileUri;
	private CountDownTimer mDurationTimer;
	private boolean mWasCanceled = false;

	// For activities
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (BuildConfig.DEBUG) {
			LOG.setLogLevel(LOG.VERBOSE);

			//will still log error twice, since it logs itself AND passes messages to logger receiver
			CameraLogger.setLogLevel(CameraLogger.LEVEL_VERBOSE);
		}

		getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
				WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
		setContentView(getResources().getIdentifier("mediacap_layout", "layout", getPackageName()));

		mCameraPreviewLayout = findViewById(R.getId(this, "cameraPreview"));
		mChangeFlashModeButton = findViewById(R.getId(this, "changeFlashMode"));
		mProgressIndicator = findViewById(R.getId(this, "progressIndicator"));

		mCaptureButton = findViewById(R.getId(this, "takePicture"));
		AppCompatImageButton switchCameraButton = findViewById(R.getId(this, "switchCamera"));

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

		mChangeFlashModeButton.setOnClickListener(v -> {
			mUseFlash = !mUseFlash;

			mChangeFlashModeButton.setImageResource(R.getDrawable(v.getContext(),
					mUseFlash ? "mediacap_flash" : "mediacap_flash_off"));

			if (mCameraView.getSessionType() != SessionType.PICTURE) {
				mCameraView.setFlash(mUseFlash ? Flash.TORCH : Flash.OFF);
			} else {
				//TODO - test
				mCameraView.setFlash(mUseFlash ? Flash.ON : Flash.OFF);
			}
		});

		mCaptureButton.setOnClickListener(v -> {
			if (isVideo) {
				switchCameraButton.setVisibility(View.GONE);
				if (!mCameraView.isCapturingVideo())
					captureVideo();
				else
					stopCaptureVideo(false);
			} else {
				capturePhoto();
			}
		});

		switchCameraButton.setOnClickListener(v -> {
			mShowBackCamera = !mShowBackCamera;
			mCameraView.setFacing(mShowBackCamera ? Facing.FRONT : Facing.BACK);
		});

		pictureAcceptButton.setOnClickListener(v -> {
			// note, we reference the string directly rather than via Camera.ACTION_NEW_PICTURE, as the latter class is now deprecated - but we still need to broadcast the string for other apps
			sendBroadcast(new Intent("android.hardware.action.NEW_PICTURE", mSaveFileUri));
			// for compatibility with some apps - apparently this is what used to be broadcast on Android?
			sendBroadcast(new Intent("com.android.camera.NEW_PICTURE", mSaveFileUri));

			Intent resultIntent = new Intent();
			resultIntent.setData(mSaveFileUri);
			setResult(Activity.RESULT_OK, resultIntent);
			finish();
		});

		pictureRepeatButton.setOnClickListener(v -> {

			mCameraView.start();
			mPicturePreviewLayout.setVisibility(View.GONE);
			mCameraPreviewLayout.setVisibility(View.VISIBLE);

			mCapturedImageView.setImageDrawable(null);

			capturePhoto();
		});

		videoAcceptButton.setOnClickListener(v -> {
			Intent resultIntent = new Intent();
			resultIntent.setData(mSaveFileUri);
			setResult(Activity.RESULT_OK, resultIntent);
			finish();
		});

		videoRepeatButton.setOnClickListener(v -> {
			mVideoView.suspend();
			mCameraView.start();
			mVideoPreviewLayout.setVisibility(View.GONE);
			mCameraPreviewLayout.setVisibility(View.VISIBLE);
			switchCameraButton.setVisibility(View.VISIBLE);
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

		Intent intent = getIntent();

		if (intent == null) {
			LOG.e(TAG, "no intent data");
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
			showErrorDialog("could not create target file");
		}

		mCameraView = findViewById(R.getId(this, "cameraview"));
		mCameraView.setLifecycleOwner(this);
		mCameraView.addCameraListener(new CameraListener() {
			@Override
			public void onCameraOpened(CameraOptions options) {
				LOG.v(TAG, "onCameraOpened");

				mChangeFlashModeButton.setVisibility(options.getSupportedFlash().isEmpty() ? View.GONE : View.VISIBLE);
			}

			@Override
			public void onCameraClosed() {
				LOG.v(TAG, "onCameraClosed");
			}

			@Override
			public void onCameraError(@NonNull CameraException exception) {
				showErrorDialog(exception.getLocalizedMessage());
				LOG.v(TAG, "onCameraError");
			}

			@Override
			public void onPictureTaken(byte[] jpeg) {
				LOG.v(TAG, "onPictureTaken");
			}

			@Override
			public void onVideoTaken(File video) {
				LOG.v(TAG, "onVideoTaken");

				if (mDurationTimer != null) {
					mDurationTimer.cancel();
				}
				mCameraView.stop();

				// UI
				mRecordStats.setVisibility(View.GONE);
				mRecordIcon.clearAnimation();

				if (!mWasCanceled) {
					mCameraPreviewLayout.setVisibility(View.GONE);
					mVideoPreviewLayout.setVisibility(View.VISIBLE);
					//TODO: replace with uri
					mVideoView.setVideoURI(mSaveFileUri);
					mCaptureButton.setImageResource(R.getDrawable(CaptureActivity2.this, "mediacap_capture"));

				} else {
					deleteRecording();
				}

			}

			@Override
			public void onOrientationChanged(int orientation) {
				//TODO: orientation ain't working, too
				LOG.v(TAG, "onOrientationChanged %d", orientation);
			}

			@Override
			public void onFocusStart(PointF point) {
				LOG.v(TAG, "onFocusStart");
			}

			@Override
			public void onFocusEnd(boolean successful, PointF point) {
				LOG.v(TAG, "onFocusEnd");
			}

			@Override
			public void onZoomChanged(float newValue, float[] bounds, PointF[] fingers) {
				LOG.v(TAG, "onZoomChanged");
			}

			@Override
			public void onExposureCorrectionChanged(float newValue, float[] bounds, PointF[] fingers) {
				LOG.v(TAG, "onExposureCorrectionChanged");
			}
		});


		if (isVideo) {
			mCameraView.setSessionType(SessionType.VIDEO);
			if (mQuality == 0) {
				mCameraView.setVideoQuality(VideoQuality.LOWEST);
			}
			else {
			//Makes app crash?! seems like only 720p (default) works
				mCameraView.setVideoQuality(VideoQuality.HIGHEST);
			}
		} else {
			mCameraView.setSessionType(SessionType.PICTURE);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		mCameraView.start();
	}

	@Override
	protected void onPause() {
		super.onPause();
		mCameraView.stop();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		mCameraView.destroy();
	}

	@Override
	public void onBackPressed() {
		stopCaptureVideo(true);

		if (mSaveFileUri != null) {
			try {
				getContentResolver().delete(mSaveFileUri, null, null);
			} catch (Exception e) {
				LOG.w("error removing file before closing", e);
			}
		}
		Intent resultIntent = new Intent();
		setResult(Activity.RESULT_CANCELED, resultIntent);

		super.onBackPressed();
	}

	private void capturePhoto() {
		if (mCapturingPicture) return;
		mCapturingPicture = true;
		mCaptureTime = System.currentTimeMillis();
		mCaptureNativeSize = mCameraView.getPictureSize();
		LOG.d(TAG, "Capturing picture...");
		mCameraView.capturePicture();
	}

	private void captureVideo() {
		if (mCameraView.getSessionType() != SessionType.VIDEO || mCameraView.isCapturingVideo()) {
			LOG.d(TAG, "Can't record video while session type is 'picture' or recording.");
			return;
		}

		LOG.d(TAG, "Recording video...");
		mCaptureButton.setImageResource(R.getDrawable(CaptureActivity2.this, "mediacap_stop"));
		if (mDuration > 0) {
			mCameraView.setVideoMaxDuration(mDuration * 1000);

		}

		mDurationTimer = new CountDownTimer(Long.MAX_VALUE, 1000) {
			int time = 0;

			@Override
			public void onTick(long millisUntilFinished) {
				updateDurationView(time);
				time++;
			}

			@Override
			public void onFinish() {
				updateDurationView(0);
			}
		};

		mRecordStats.setVisibility(View.VISIBLE);
		mRecordIcon.startAnimation(mBlink);

		mDurationTimer.start();
		mCameraView.startCapturingVideo(getFileFromUri());
	}

	private void updateDurationView(long elapsed) {
		runOnUiThread(() -> {

			mRecordingDurationView.setText(String.format(Locale.getDefault(), "%d",
					elapsed));
			//reconding delays 1-3 secs and has no callback. can't accurately determine times
/*			if (mDuration > 0) {
				mRecordingDurationView.setText(String.format(Locale.getDefault(), "%d / %d",
						elapsed, mDuration));
			} else {
				mRecordingDurationView.setText(String.format(Locale.getDefault(), "%d",
						elapsed));
			}*/
		});
	}

	/**
	 * Workaround for file-api. will be removed as soon as capture plugin can use content-uris
	 */
	private File getFileFromUri() {
		File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
				Environment.DIRECTORY_MOVIES), getPackageName());
		return new File(mediaStorageDir +
				File.separator + mSaveFileUri.getLastPathSegment());
	}

	private void stopCaptureVideo(boolean wasCanceled) {
		mWasCanceled = wasCanceled;
		if (mCameraView.isCapturingVideo())
			mCameraView.stopCapturingVideo();

/*		if(!wasCanceled) {
			mCameraPreviewLayout.setVisibility(View.GONE);
			mVideoPreviewLayout.setVisibility(View.VISIBLE);
		} else {
			deleteRecording();
		}*/
	}

	private void deleteRecording() {
		if (mSaveFileUri != null) {
			try {
				getContentResolver().delete(mSaveFileUri, null, null);
			} catch (Exception e) {
				LOG.w("error removing file before closing", e);
			}
		}
		;
	}

	/**
	 * Shows an error message dialog.
	 */
	private void showErrorDialog(String msg) {
		new AlertDialog.Builder(CaptureActivity2.this)
				.setMessage(msg)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
				.create()
				.show();
	}

}
