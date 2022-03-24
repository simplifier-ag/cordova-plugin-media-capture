package org.apache.cordova.mediacapture;

import static org.apache.cordova.mediacapture.Capture.CAPTURE_AUDIO;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.apache.cordova.BuildConfig;
import org.apache.cordova.LOG;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class AudioCaptureActivity extends Activity {
	/**
	 * Visualizer refresh rate in ms
	 */
	public static final int REPEAT_INTERVAL = 40;
	private static final String TAG = AudioCaptureActivity.class.getSimpleName();
	/**
	 * Handler for updating visualizer
	 */
	private final Handler mHandler = new Handler(); // Handler for updating the visualizer
	//Views
	private VisualizerView mVisualizerView;
	private AppCompatTextView mRecordTimer;
	private FloatingActionButton mToggleRecordButton;
	private FloatingActionButton mTogglePauseButton;
	/**
	 * records audio
	 */
	private MediaRecorder mMediaRecorder;
	/**
	 * This the output uri if MediaStore.EXTRA_OUTPUT Intent extra exists
	 */
	private Uri mSaveFileUri;
	/**
	 * timer for duration
	 */
	private PausableCountDownTimer mDurationTimer;
	/**
	 * recording limit in seconds, 0 = unlimited
	 */
	private int mDuration = 0;
	/**
	 * recording is currently running
	 */
	private boolean mIsRecording = false;
	/**
	 * recording is currently paused
	 */
	private boolean mIsPaused = false;
	private final View.OnClickListener togglePauseListener = view -> {
		if (!mIsPaused) {
			pauseRecorder();
		} else {
			resumeRecorder();
		}
	};
	/**
	 * updates the visualizer every 50 milliseconds
	 */
	private final Runnable updateVisualizer = new Runnable() {
		@Override
		public void run() {
			if (mIsRecording) // if we are already recording
			{
				if (!mIsPaused) {
					// get the current amplitude
					int x = mMediaRecorder.getMaxAmplitude();
					mVisualizerView.addAmplitude(x); // update the VisualizeView
					mVisualizerView.invalidate(); // refresh the VisualizerView
				}

				// update in 40 milliseconds
				mHandler.postDelayed(this, REPEAT_INTERVAL);
			}
		}
	};
	private final View.OnClickListener toggleRecordListener = view -> {
		if (!mIsRecording) {
			startRecorder();
		} else {
			stopRecorder();
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (BuildConfig.DEBUG) {
			LOG.setLogLevel(LOG.VERBOSE);
		}

		setContentView(R.getLayout(this, "mediacap_recorder_layout"));

		mVisualizerView = findViewById(R.getId(this, "visualizer"));
		mRecordTimer = findViewById(R.getId(this, "recordTimer"));
		mToggleRecordButton = findViewById(R.getId(this, "toggle_recording_button"));
		mTogglePauseButton = findViewById(R.getId(this, "toggle_pause_button"));

		mToggleRecordButton.setOnClickListener(toggleRecordListener);
		mTogglePauseButton.setOnClickListener(togglePauseListener);

		mVisualizerView.setLineColor(Color.parseColor("#cc4285f4"));
		mVisualizerView.setLineWidth(10);

		Intent intent = getIntent();

		if (intent == null) {
			LOG.e(TAG, "no intent data");
			finish();
			return;
		}

		if (intent.getExtras() != null && intent.getExtras().get(MediaStore.EXTRA_OUTPUT) != null) {
			mSaveFileUri = (Uri) intent.getExtras().get(MediaStore.EXTRA_OUTPUT);
		} else {
			//should not happen
			try {
				mSaveFileUri = FileHelper.getDataUriForMediaFile(CAPTURE_AUDIO, this);
			} catch (IllegalArgumentException | IOException e) {
				LOG.e(TAG, "error creating data uri", e);
				Helper.showErrorDialog(R.localize(this, "mediacap_error_file"), this);
				return;
			}
		}

		mDuration = intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, mDuration);
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (!mIsRecording || mIsPaused || mMediaRecorder == null) {
			return;
		}
		pauseRecorder();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		releaseRecorder();
	}

	@Override
	public void onBackPressed() {
		if (mIsRecording) {
			releaseRecorder();
			try {
				getContentResolver().delete(mSaveFileUri, null, null);
			} catch (Exception e) {
				LOG.w("error removing file before closing", e);
			}
		}

		super.onBackPressed();
	}

	/**
	 * Stops audio recorder and timer
	 */
	private void releaseRecorder() {
		if (mMediaRecorder == null) {
			return;
		}

		if (mIsPaused) {
			resumeRecorder();
		}

		mDurationTimer.cancel();
		mIsRecording = false; // stop recording
		mHandler.removeCallbacks(updateVisualizer);
		mMediaRecorder.stop();
		mMediaRecorder.reset();
		mMediaRecorder.release();
		mMediaRecorder = null;
	}

	/**
	 * Start audio recorder
	 */
	private void startRecorder() {
		if (!isMicAvailable()) {
			Toast.makeText(this,
					"Microphone is currently not available",
					Toast.LENGTH_LONG)
					.show();
			return;
		}

		mVisualizerView.clear();

		mMediaRecorder = new MediaRecorder();
		mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
			mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
		} else {
			mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		}

		FileDescriptor audioFileDescriptor;
		try {
			audioFileDescriptor = getContentResolver().openFileDescriptor(mSaveFileUri, "rw").getFileDescriptor();
		} catch (FileNotFoundException e) {
			LOG.e(TAG, "error getting file descriptor", e);
			Helper.showErrorDialog(R.localize(this, "mediacap_error_file"), this);
			return;
		}
		mMediaRecorder.setOutputFile(audioFileDescriptor);

		//Starting countdown timer
		if (mDuration != 0) {
			//Starting countdown with recording limit
			mDurationTimer = new PausableCountDownTimer(mDuration * 1000L, 1000) {
				@Override
				public void onTick(long millisUntilFinished) {
					updateDurationView(mDuration - TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished));
				}

				@Override
				public void onFinish() {
					Toast.makeText(AudioCaptureActivity.this,
							"Capture limit reached",
							Toast.LENGTH_LONG)
							.show();
					toggleRecordListener.onClick(mToggleRecordButton);
					updateDurationView(0);
				}
			};
		} else {
			mDurationTimer = new PausableCountDownTimer(Long.MAX_VALUE, 1000) {
				int time = 0;

				@Override
				public void onTick(long millisUntilFinished) {
					updateDurationView(time++);
				}

				@Override
				public void onFinish() {
					updateDurationView(0);
				}
			};
		}

		try {
			mMediaRecorder.prepare();
			mMediaRecorder.start();
			mIsRecording = true; // we are currently recording
			mDurationTimer.start();
			mHandler.post(updateVisualizer);
		} catch (IllegalStateException | IOException e) {
			LOG.e(TAG, e.getMessage(), e);
			Helper.showErrorDialog(R.localize(this, "mediacap_error_audio_recorder"), this);
			return;
		}

		mRecordTimer.setVisibility(View.VISIBLE);
		mToggleRecordButton.setImageResource(R.getDrawable(AudioCaptureActivity.this, "mediacap_stop"));
		mTogglePauseButton.show();
	}

	/**
	 * Stops audio recorder
	 */
	private void stopRecorder() {
		mRecordTimer.setVisibility(View.INVISIBLE);
		mToggleRecordButton.setImageResource(R.getDrawable(AudioCaptureActivity.this, "mediacap_microphone"));
		mTogglePauseButton.hide();

		releaseRecorder();

		//Finishing activity with result
		Intent resultIntent = new Intent();
		resultIntent.setData(mSaveFileUri);
		setResult(Activity.RESULT_OK, resultIntent);
		finish();
	}

	/**
	 * Pauses audio recorder
	 */
	private void pauseRecorder() {
		if (mIsRecording) {
			mMediaRecorder.pause();
			mDurationTimer.pause();
			mIsPaused = true;
			startPauseButtonBlinking();
		}
	}

	/**
	 * Resumes audio recorder
	 */
	private void resumeRecorder() {
		if (mIsRecording) {
			mMediaRecorder.resume();
			mDurationTimer.resume();
			mIsPaused = false;
			stopPauseButtonBlinking();
		}
	}

	/**
	 * Updates record timer view
	 *
	 * @param elapsed the recorded seconds
	 */
	private void updateDurationView(long elapsed) {
		runOnUiThread(() -> {
			long minutes = elapsed / 60;
			long seconds = elapsed % 60;

			if (mDuration > 0) {
				long durationMinutes = mDuration / 60;
				long durationSeconds = mDuration % 60;
				mRecordTimer.setText(String.format(Locale.getDefault(), "%d:%02d / %d:%02d",
						minutes, seconds, durationMinutes, durationSeconds));
			} else {
				mRecordTimer.setText(String.format(Locale.getDefault(), "%d:%02d",
						minutes, seconds));
			}
		});
	}

	/**
	 * Starts blinking animation of pause button
	 */
	private void startPauseButtonBlinking() {
		int animationId = R.getAnimation(this, "mediacap_blink_semitransparent");
		Animation blinkAnimation = AnimationUtils.loadAnimation(this, animationId);
		mTogglePauseButton.startAnimation(blinkAnimation);
	}

	/**
	 * Stops blinking animation of pause button
	 */
	private void stopPauseButtonBlinking() {
		mTogglePauseButton.clearAnimation();
	}

	/**
	 * Checks if microphone is available for recording
	 *
	 * @return Indicator if microphone is in use
	 */
	private boolean isMicAvailable() {
		boolean available = true;

		//permissions are requested in Capture.java
		@SuppressLint("MissingPermission")
		AudioRecord recorder =
				new AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
						AudioFormat.CHANNEL_IN_MONO,
						AudioFormat.ENCODING_DEFAULT, 44100);
		try {
			if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_STOPPED) {
				available = false;

			}

			recorder.startRecording();
			if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
				recorder.stop();
				available = false;

			}
			recorder.stop();
		} finally {
			recorder.release();
		}

		//Checking if phone is in a call
		if (available) {
			AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
			if (am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
				return false;
			}
		}

		return available;
	}
}
