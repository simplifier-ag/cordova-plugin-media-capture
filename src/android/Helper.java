package org.apache.cordova.mediacapture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

public class Helper {
	/**
	 * Shows an error message dialog.
	 */
	static void showErrorDialog(String msg, Activity activity) {
		new AlertDialog.Builder(activity)
				.setMessage(msg)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {

					activity.finish();
				})
				.create()
				.show();
	}

	public static void lockOrientation(Activity activity) {
		int currentOrientation = activity.getResources().getConfiguration().orientation;
		if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
			activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
		} else {
			activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
		}
	}

	public static void unlockOrientation(Activity activity) {
		activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
	}
}
