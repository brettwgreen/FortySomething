/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.nervii.fortysomething;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class FortySomethingWatchFace extends CanvasWatchFaceService {

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<FortySomethingWatchFace.Engine> mWeakReference;

        public EngineHandler(FortySomethingWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            FortySomethingWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        //final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaintHour, mTextPaintMinute, mTextPaintSecond,
                mTextPaintColon, mTextPaintDate;

        boolean mAmbient;
        Calendar mCalendar;
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler
                                    .sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(FortySomethingWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = FortySomethingWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaintHour = new Paint();
            mTextPaintHour = createTextPaint(resources.getColor(R.color.digital_text), FortySomethingWatchFace.this);
            mTextPaintMinute = new Paint();
            mTextPaintMinute = createTextPaint(resources.getColor(R.color.digital_text_grey), FortySomethingWatchFace.this);
            mTextPaintSecond = new Paint();
            mTextPaintSecond = createTextPaint(resources.getColor(R.color.digital_text_dark_grey), FortySomethingWatchFace.this);
            mTextPaintColon = new Paint();
            mTextPaintColon = createTextPaint(resources.getColor(R.color.digital_text_grey), FortySomethingWatchFace.this);

            mTextPaintDate = new Paint();
            mTextPaintDate = createTextPaint(resources.getColor(R.color.digital_text_grey), FortySomethingWatchFace.this);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Typeface getTypeFace(Context context) {
            return FontCache.get("digital-7.ttf", context);
        }
        private Paint createTextPaint(int textColor, Context context) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(getTypeFace(context));
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            FortySomethingWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            FortySomethingWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = FortySomethingWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float textSizeMedium = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round_medium : R.dimen.digital_text_size_medium);
            float textSizeSmall = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round_small : R.dimen.digital_text_size_small);

            mTextPaintHour.setTextSize(textSize);
            mTextPaintMinute.setTextSize(textSize);
            mTextPaintSecond.setTextSize(textSizeMedium);
            mTextPaintDate.setTextSize(textSizeMedium);
            mTextPaintColon.setTextSize(textSize);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintHour.setAntiAlias(!inAmbientMode);
                    mTextPaintMinute.setAntiAlias(!inAmbientMode);
                    mTextPaintSecond.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            String hour = String.format("%02d", mCalendar.get(Calendar.HOUR));
            String minute = String.format("%02d", mCalendar.get(Calendar.MINUTE));
            String second = String.format("%02d", mCalendar.get(Calendar.SECOND));
            String colon = ":";

            float hourWidth = mTextPaintHour.measureText(hour);
            float minuteWidth = mTextPaintHour.measureText(minute);
            float colonWidth = mTextPaintHour.measureText(colon);
            int center_horizontal = (canvas.getWidth() / 2);
            int xPos = Math.round((canvas.getWidth() - (hourWidth+colonWidth+minuteWidth)) / 2);
            Rect r = new Rect();
            mTextPaintHour.getTextBounds(hour, 0, hour.length(), r);
            int yPos = (int) ((canvas.getHeight() / 2) - ((mTextPaintHour.descent() + mTextPaintHour.ascent()) / 2)) ;
            yPos -= (Math.abs(r.height()))/2;
            yPos += 20;
            mTextPaintHour.setTextAlign(Paint.Align.LEFT);
            mTextPaintMinute.setTextAlign(Paint.Align.LEFT);
            mTextPaintSecond.setTextAlign(Paint.Align.LEFT);
            mTextPaintDate.setTextAlign(Paint.Align.CENTER);

            String day = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US);
            String month = mCalendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.US);
            String date = String.valueOf(mCalendar.get(Calendar.DAY_OF_MONTH));

            String dateString = day + ", " + month + " " + date;

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            canvas.drawText(hour, xPos, yPos, mTextPaintHour);
            canvas.drawText(colon, xPos+hourWidth, yPos, mTextPaintColon);
            canvas.drawText(minute, xPos+hourWidth+colonWidth, yPos, mTextPaintMinute);
            canvas.drawText(dateString, center_horizontal, (canvas.getHeight() / 2)-45, mTextPaintDate);

            if (!mAmbient) {
                canvas.drawText(second, xPos+hourWidth+colonWidth+minuteWidth, (canvas.getHeight() / 2), mTextPaintSecond);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
