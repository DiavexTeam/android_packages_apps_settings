/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.widget;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.NetworkStatsHistory;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.settings.R;
import com.google.common.base.Preconditions;

/**
 * {@link NetworkStatsHistory} series to render inside a {@link ChartView},
 * using {@link ChartAxis} to map into screen coordinates.
 */
public class ChartNetworkSeriesView extends View {
    private static final String TAG = "ChartNetworkSeriesView";
    private static final boolean LOGD = false;

    private ChartAxis mHoriz;
    private ChartAxis mVert;

    private Paint mPaintStroke;
    private Paint mPaintFill;
    private Paint mPaintFillSecondary;
    private Paint mPaintEstimate;

    private NetworkStatsHistory mStats;

    private Path mPathStroke;
    private Path mPathFill;
    private Path mPathEstimate;

    private long mPrimaryLeft;
    private long mPrimaryRight;

    /** Series will be extended to reach this end time. */
    private long mEndTime = Long.MIN_VALUE;

    private boolean mEstimateVisible = false;

    private long mMax;
    private long mMaxEstimate;

    public ChartNetworkSeriesView(Context context) {
        this(context, null, 0);
    }

    public ChartNetworkSeriesView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ChartNetworkSeriesView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.ChartNetworkSeriesView, defStyle, 0);

        final int stroke = a.getColor(R.styleable.ChartNetworkSeriesView_strokeColor, Color.RED);
        final int fill = a.getColor(R.styleable.ChartNetworkSeriesView_fillColor, Color.RED);
        final int fillSecondary = a.getColor(
                R.styleable.ChartNetworkSeriesView_fillColorSecondary, Color.RED);

        setChartColor(stroke, fill, fillSecondary);
        setWillNotDraw(false);

        a.recycle();

        mPathStroke = new Path();
        mPathFill = new Path();
        mPathEstimate = new Path();
    }

    void init(ChartAxis horiz, ChartAxis vert) {
        mHoriz = Preconditions.checkNotNull(horiz, "missing horiz");
        mVert = Preconditions.checkNotNull(vert, "missing vert");
    }

    public void setChartColor(int stroke, int fill, int fillSecondary) {
        mPaintStroke = new Paint();
        mPaintStroke.setStrokeWidth(4.0f * getResources().getDisplayMetrics().density);
        mPaintStroke.setColor(stroke);
        mPaintStroke.setStyle(Style.STROKE);
        mPaintStroke.setAntiAlias(true);

        mPaintFill = new Paint();
        mPaintFill.setColor(fill);
        mPaintFill.setStyle(Style.FILL);
        mPaintFill.setAntiAlias(true);

        mPaintFillSecondary = new Paint();
        mPaintFillSecondary.setColor(fillSecondary);
        mPaintFillSecondary.setStyle(Style.FILL);
        mPaintFillSecondary.setAntiAlias(true);

        mPaintEstimate = new Paint();
        mPaintEstimate.setStrokeWidth(3.0f);
        mPaintEstimate.setColor(fillSecondary);
        mPaintEstimate.setStyle(Style.STROKE);
        mPaintEstimate.setAntiAlias(true);
        mPaintEstimate.setPathEffect(new DashPathEffect(new float[] { 10, 10 }, 1));
    }

    public void bindNetworkStats(NetworkStatsHistory stats) {
        mStats = stats;

        mPathStroke.reset();
        mPathFill.reset();
        mPathEstimate.reset();
        invalidate();
    }

    /**
     * Set the range to paint with {@link #mPaintFill}, leaving the remaining
     * area to be painted with {@link #mPaintFillSecondary}.
     */
    public void setPrimaryRange(long left, long right) {
        mPrimaryLeft = left;
        mPrimaryRight = right;
        invalidate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        generatePath();
    }

    /**
     * Erase any existing {@link Path} and generate series outline based on
     * currently bound {@link NetworkStatsHistory} data.
     */
    public void generatePath() {
        if (LOGD) Log.d(TAG, "generatePath()");

        mMax = 0;
        mPathStroke.reset();
        mPathFill.reset();
        mPathEstimate.reset();

        // bail when not enough stats to render
        if (mStats == null || mStats.size() < 2) {
            invalidate();
            return;
        }

        final int width = getWidth();
        final int height = getHeight();

        boolean started = false;
        float firstX = 0;
        float lastX = 0;
        float lastY = 0;
        long lastTime = Long.MIN_VALUE;

        // TODO: count fractional data from first bucket crossing start;
        // currently it only accepts first full bucket.

        long totalData = 0;

        NetworkStatsHistory.Entry entry = null;
        for (int i = 0; i < mStats.size(); i++) {
            entry = mStats.getValues(i, entry);

            lastTime = entry.bucketStart + entry.bucketDuration;
            final float x = mHoriz.convertToPoint(lastTime);
            final float y = mVert.convertToPoint(totalData);

            // skip until we find first stats on screen
            if (i > 0 && !started && x > 0) {
                mPathStroke.moveTo(lastX, lastY);
                mPathFill.moveTo(lastX, lastY);
                started = true;
                firstX = x;
            }

            if (started) {
                mPathStroke.lineTo(x, y);
                mPathFill.lineTo(x, y);
                totalData += entry.rxBytes + entry.txBytes;
            }

            // skip if beyond view
            if (x > width) break;

            lastX = x;
            lastY = y;
        }

        // when data falls short, extend to requested end time
        if (lastTime < mEndTime) {
            lastX = mHoriz.convertToPoint(mEndTime);

            if (started) {
                mPathStroke.lineTo(lastX, lastY);
                mPathFill.lineTo(lastX, lastY);
            }
        }

        if (LOGD) {
            final RectF bounds = new RectF();
            mPathFill.computeBounds(bounds, true);
            Log.d(TAG, "onLayout() rendered with bounds=" + bounds.toString() + " and totalData="
                    + totalData);
        }

        // drop to bottom of graph from current location
        mPathFill.lineTo(lastX, height);
        mPathFill.lineTo(firstX, height);

        mMax = totalData;

        // build estimated data
        mPathEstimate.moveTo(lastX, lastY);

        final long now = System.currentTimeMillis();
        final long bucketDuration = mStats.getBucketDuration();

        // long window is average over two weeks
        entry = mStats.getValues(lastTime - WEEK_IN_MILLIS * 2, lastTime, now, entry);
        final long longWindow = (entry.rxBytes + entry.txBytes) * bucketDuration
                / entry.bucketDuration;

        long futureTime = 0;
        while (lastX < width) {
            futureTime += bucketDuration;

            // short window is day average last week
            final long lastWeekTime = lastTime - WEEK_IN_MILLIS + (futureTime % WEEK_IN_MILLIS);
            entry = mStats.getValues(lastWeekTime - DAY_IN_MILLIS, lastWeekTime, now, entry);
            final long shortWindow = (entry.rxBytes + entry.txBytes) * bucketDuration
                    / entry.bucketDuration;

            totalData += (longWindow * 7 + shortWindow * 3) / 10;

            lastX = mHoriz.convertToPoint(lastTime + futureTime);
            lastY = mVert.convertToPoint(totalData);

            mPathEstimate.lineTo(lastX, lastY);
        }

        mMaxEstimate = totalData;

        invalidate();
    }

    public void setEndTime(long endTime) {
        mEndTime = endTime;
    }

    public void setEstimateVisible(boolean estimateVisible) {
        mEstimateVisible = estimateVisible;
        invalidate();
    }

    public long getMaxEstimate() {
        return mMaxEstimate;
    }

    public long getMaxVisible() {
        return mEstimateVisible ? mMaxEstimate : mMax;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save;

        final float primaryLeftPoint = mHoriz.convertToPoint(mPrimaryLeft);
        final float primaryRightPoint = mHoriz.convertToPoint(mPrimaryRight);

        if (mEstimateVisible) {
            save = canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            canvas.drawPath(mPathEstimate, mPaintEstimate);
            canvas.restoreToCount(save);
        }

        save = canvas.save();
        canvas.clipRect(0, 0, primaryLeftPoint, getHeight());
        canvas.drawPath(mPathFill, mPaintFillSecondary);
        canvas.restoreToCount(save);

        save = canvas.save();
        canvas.clipRect(primaryRightPoint, 0, getWidth(), getHeight());
        canvas.drawPath(mPathFill, mPaintFillSecondary);
        canvas.restoreToCount(save);

        save = canvas.save();
        canvas.clipRect(primaryLeftPoint, 0, primaryRightPoint, getHeight());
        canvas.drawPath(mPathFill, mPaintFill);
        canvas.drawPath(mPathStroke, mPaintStroke);
        canvas.restoreToCount(save);

    }
}