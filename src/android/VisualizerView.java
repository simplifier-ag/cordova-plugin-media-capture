package org.apache.cordova.mediacapture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class VisualizerView extends View {
	private static final int LINE_SCALE = 60; // scales visualizer lines
	private final Paint linePaint; // specifies line drawing characteristics
	private int LINE_WIDTH = 1; // width of visualizer lines
	private List<Float> amplitudes = new ArrayList<>(); // amplitudes for line lengths
	private int width; // width of this View
	private int height; // height of this View
	private boolean drawAmplitude = true;

	// constructor
	public VisualizerView(Context context, AttributeSet attrs) {
		super(context, attrs); // call superclass constructor
		linePaint = new Paint(); // create Paint for lines
		linePaint.setColor(Color.GREEN); // set color to green
		linePaint.setStrokeWidth(LINE_WIDTH); // set stroke width
		linePaint.setStrokeCap(Paint.Cap.ROUND); // set the stroke cap
	}

	// called when the dimensions of the View change
	@Override
	protected void onSizeChanged(int w, int h, int oldW, int oldH) {
		width = w; // new width of this View
		height = h; // new height of this View
		int capacity = width / LINE_WIDTH; //new capacity of ArrayList amplitudes

		if (amplitudes.size() > capacity) {
			amplitudes = amplitudes.subList(amplitudes.size() - capacity, amplitudes.size());
		}
	}

	/**
	 * Clears all amplitudes to prepare for a new visualization
	 */
	public void clear() {
		amplitudes.clear();
	}

	/**
	 * Add the given amplitude to the amplitudes ArrayList
	 *
	 * @param amplitude Amplitude of MediaRecorder
	 */
	public void addAmplitude(float amplitude) {
		amplitudes.add(drawAmplitude ? amplitude : 0f);
		drawAmplitude = !drawAmplitude;

		// if the power lines completely fill the VisualizerView
		if (amplitudes.size() * LINE_WIDTH >= width) {
			amplitudes.remove(0); // remove oldest power value
		}
	}

	/**
	 * Returns the width of the drawn line
	 *
	 * @return Width of drawn line
	 */
	public int getLineWidth() {
		return LINE_WIDTH;
	}

	/**
	 * Sets the width of the drawn lines
	 *
	 * @param LINE_WIDTH Width of the drawn line
	 */
	public void setLineWidth(int LINE_WIDTH) {
		this.LINE_WIDTH = LINE_WIDTH;
		linePaint.setStrokeWidth(LINE_WIDTH);
	}

	/**
	 * Sets the color of the drawn line
	 *
	 * @param color The new color (including alpha) to set
	 */
	public void setLineColor(int color) {
		linePaint.setColor(color);
	}

	// draw the visualizer with scaled lines representing the amplitudes
	@Override
	public void onDraw(Canvas canvas) {
		int middle = height / 2; // get the middle of the View
		float curX = 0; // start curX at zero

		// for each item in the amplitudes ArrayList
		for (float power : amplitudes) {
			float scaledHeight = power / LINE_SCALE; // scale the power
			curX += LINE_WIDTH; // increase X by LINE_WIDTH

			// draw a line representing this item in the amplitudes ArrayList
			canvas.drawLine(curX, middle + scaledHeight / 2, curX, middle
					- scaledHeight / 2, linePaint);
		}
	}
}
