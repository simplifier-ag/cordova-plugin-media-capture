package org.apache.cordova.mediacapture;

import static org.apache.cordova.mediacapture.Capture.CAPTURE_AUDIO;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v7.widget.AppCompatImageButton;
import android.support.v7.widget.AppCompatTextView;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Toast;

import org.apache.cordova.BuildConfig;
import org.apache.cordova.LOG;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class AudioCaptureActivity extends Activity {
	private static final String TAG = AudioCaptureActivity.class.getSimpleName();

	public static final int REPEAT_INTERVAL = 40;

	private VisualizerView visualizerView;
	private AppCompatTextView recordTimer;
	private AppCompatImageButton toggleRecordButton;
	private AppCompatImageButton togglePauseButton;

	private MediaRecorder recorder;
	private Uri saveFileUri;
	private FileDescriptor audioFileDescriptor;

	private CountDownTimerWithPause durationTimer;

	private int duration = 0;
	private boolean isRecording = false;
	private boolean isPaused = false;

	private final Handler handler = new Handler(); // Handler for updating the visualizer

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		if (BuildConfig.DEBUG) {
			LOG.setLogLevel(LOG.VERBOSE);
		}

		setContentView(R.getLayout(this, "audio_recorder_layout"));

		visualizerView = findViewById(R.getId(this, "visualizer"));
		recordTimer = findViewById(R.getId(this, "recordTimer"));
		toggleRecordButton = findViewById(R.getId(this, "toggle_recording_button"));
		togglePauseButton = findViewById(R.getId(this, "toggle_pause_button"));

		toggleRecordButton.setOnClickListener(toggleRecordListener);
		togglePauseButton.setOnClickListener(togglePauseListener);

		visualizerView.setLineColor(Color.parseColor("#cc4285f4"));
		visualizerView.setLineWidth(10);

		Intent intent = getIntent();

		if (intent == null) {
			LOG.e(TAG, "no intent data");
			finish();
			return;
		}

		if (intent.getExtras() != null && intent.getExtras().get(MediaStore.EXTRA_OUTPUT) != null) {
			saveFileUri = (Uri) intent.getExtras().get(MediaStore.EXTRA_OUTPUT);
		} else {
			//should not happen
			try {
				saveFileUri = FileHelper.getDataUriForMediaFile(CAPTURE_AUDIO, this);
			} catch (IllegalArgumentException e) {
				LOG.e(TAG, "error creating data uri", e);
				showErrorDialog("Could not create target file");
				return;
			}
		}

		try {
			audioFileDescriptor = getContentResolver().openFileDescriptor(saveFileUri, "rw").getFileDescriptor();
		} catch (FileNotFoundException e) {
			LOG.e(TAG, "error getting file descriptor", e);
			showErrorDialog("Could not create target file");
			return;
		}

		duration = intent.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, duration);
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (!isRecording || isPaused || recorder == null) {
			return;
		}

		recorder.pause();
		durationTimer.pause();
		isPaused = true;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		releaseRecorder();
	}

	private final View.OnClickListener toggleRecordListener = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (!isRecording) {
				visualizerView.clear();

				recorder = new MediaRecorder();
				recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
				recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
				recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
				recorder.setOutputFile(audioFileDescriptor);

				if (duration != 0) {
					durationTimer = new CountDownTimerWithPause(duration * 1000L, 1000) {
						@Override
						public void onTick(long millisUntilFinished) {
							updateDurationView(duration - TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished));
						}

						@Override
						public void onFinish() {
							Toast.makeText(AudioCaptureActivity.this,
									"Capture limit reached",
									Toast.LENGTH_LONG)
									.show();
							toggleRecordListener.onClick(view);
							updateDurationView(0);
						}
					};
				} else {
					durationTimer = new CountDownTimerWithPause(Long.MAX_VALUE, 1000) {
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
					recorder.prepare();
					recorder.start();
					isRecording = true; // we are currently recording
					durationTimer.start();
					handler.post(updateVisualizer);
				} catch (IllegalStateException | IOException e) {
					LOG.e(TAG, e.getMessage(), e);
					showErrorDialog("Could not start audio recorder");
					return;
				}

				recordTimer.setVisibility(View.VISIBLE);
				toggleRecordButton.setImageResource(R.getDrawable(AudioCaptureActivity.this, "mediacap_stop"));
				togglePauseButton.setVisibility(View.VISIBLE);

			} else {
				recordTimer.setVisibility(View.INVISIBLE);
				toggleRecordButton.setImageResource(R.getDrawable(AudioCaptureActivity.this, "mediacap_microphone"));
				togglePauseButton.setVisibility(View.INVISIBLE);
				releaseRecorder();

				Intent resultIntent = new Intent();
				resultIntent.setData(saveFileUri);
				setResult(Activity.RESULT_OK, resultIntent);
				finish();
			}
		}
	};

	private final View.OnClickListener togglePauseListener = v -> {
		if (!isPaused) {
			pauseRecorder();
		} else {
			resumeRecorder();
		}
	};

	// updates the visualizer every 50 milliseconds
	private final Runnable updateVisualizer = new Runnable() {
		@Override
		public void run() {
			if (isRecording) // if we are already recording
			{
				if (!isPaused) {
					// get the current amplitude
					int x = recorder.getMaxAmplitude();
					visualizerView.addAmplitude(x); // update the VisualizeView
					visualizerView.invalidate(); // refresh the VisualizerView
				}

				// update in 40 milliseconds
				handler.postDelayed(this, REPEAT_INTERVAL);
			}
		}
	};

	private void releaseRecorder() {
		if (recorder != null) {
			durationTimer.cancel();
			isRecording = false; // stop recording
			isPaused = false;
			handler.removeCallbacks(updateVisualizer);
			recorder.stop();
			recorder.reset();
			recorder.release();
			recorder = null;
		}
	}

	private void pauseRecorder() {
		if (isRecording) {
			recorder.pause();
			durationTimer.pause();
			isPaused = true;
			startPauseButtonBlinking();
		}
	}

	private void resumeRecorder() {
		recorder.resume();
		durationTimer.resume();
		isPaused = false;
		stopPauseButtonBlinking();
	}

	/**
	 * Shows an error message dialog.
	 */
	private void showErrorDialog(String msg) {
		new AlertDialog.Builder(this)
				.setMessage(msg)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
				.create()
				.show();
	}

	private void updateDurationView(long elapsed) {
		runOnUiThread(() -> {
			long minutes = elapsed / 60;
			long seconds = elapsed % 60;

			if (duration > 0) {
				long durationMinutes = duration / 60;
				long durationSeconds = duration % 60;
				recordTimer.setText(String.format(Locale.getDefault(), "%d:%02d / %d:%02d",
						minutes, seconds, durationMinutes, durationSeconds));
			} else {
				recordTimer.setText(String.format(Locale.getDefault(), "%d:%02d",
						minutes, seconds));
			}
		});
	}

	private void startPauseButtonBlinking() {
		Animation pauseButtonAnim = new AlphaAnimation(0.5f, 1.0f);
		pauseButtonAnim.setDuration(200); //You can manage the blinking time with this parameter
		pauseButtonAnim.setStartOffset(200);
		pauseButtonAnim.setRepeatMode(Animation.REVERSE);
		pauseButtonAnim.setRepeatCount(Animation.INFINITE);
		togglePauseButton.startAnimation(pauseButtonAnim);
	}

	private void stopPauseButtonBlinking() {
		togglePauseButton.clearAnimation();
	}
}
