/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tv.tuner;

import android.content.Context;
import android.support.annotation.IntDef;
import android.support.annotation.StringDef;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import com.android.tv.tuner.ChannelScanFileParser.ScanChannel.DeliverySystem;
/**
 * A base class to handle a hardware tuner device.
 */
public abstract class TunerHal implements AutoCloseable {
    protected static final String TAG = "TunerHal";
    protected static final boolean DEBUG = true;

    @IntDef({ FILTER_TYPE_OTHER, FILTER_TYPE_AUDIO, FILTER_TYPE_VIDEO, FILTER_TYPE_PCR })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterType {}
    public static final int FILTER_TYPE_OTHER = 0;
    public static final int FILTER_TYPE_AUDIO = 1;
    public static final int FILTER_TYPE_VIDEO = 2;
    public static final int FILTER_TYPE_PCR = 3;

    @StringDef({ MODULATION_8VSB, MODULATION_QAM256, MODULATION_QPSK, MODULATION_8PSK })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModulationType {}
    public static final String MODULATION_8VSB = "8VSB";
    public static final String MODULATION_QAM256 = "QAM256";
    public static final String MODULATION_QPSK = "QPSK";
    public static final String MODULATION_8PSK = "8PSK";
    

    public static final int TUNER_TYPE_BUILT_IN = 1;
    public static final int TUNER_TYPE_USB = 2;

    protected static final int PID_PAT = 0;
    protected static final int PID_SDT = 0x11;
	private static final byte TABLE_ID_PAT = (byte) 0x00;
    private static final byte TABLE_ID_CAT = (byte) 0x01;
    private static final byte TABLE_ID_PMT = (byte) 0x02;
    private static final byte TABLE_ID_MGT = (byte) 0xc7;
    private static final byte TABLE_ID_SDT_ACTUAL = (byte) 0x42;
    private static final byte TABLE_ID_SDT_OTHER = (byte) 0x46;

    protected static final int PID_ATSC_SI_BASE = 0x1ffb;
    protected static final int DEFAULT_VSB_TUNE_TIMEOUT_MS = 2000;
    protected static final int DEFAULT_QAM_TUNE_TIMEOUT_MS = 4000; // Some device takes time for
                                                                   // QAM256 tuning.
    private boolean mIsStreaming;
    private int mFrequency;
    private String mModulation;

    static {
        System.loadLibrary("tunertvinput_jni");
    }

    /**
     * Creates a TunerHal instance.
     * @param context context for creating the TunerHal instance
     * @return the TunerHal instance
     */
    public synchronized static TunerHal createInstance(Context context) {
        TunerHal tunerHal = null;
        if (getTunerType(context) == TUNER_TYPE_BUILT_IN) {
        }
        if (tunerHal == null) {
            tunerHal = new UsbTunerHal(context);
        }
        if (tunerHal.openFirstAvailable()) {
            return tunerHal;
        }
        return null;
    }

    /**
     * Gets the number of tuner devices currently present.
     */
    public static int getTunerCount(Context context) {
        if (getTunerType(context) == TUNER_TYPE_BUILT_IN) {
        }
        return UsbTunerHal.getNumberOfDevices(context);
    }

    /**
     * Gets the type of tuner devices currently used.
     */
    public static int getTunerType(Context context) {
        return TUNER_TYPE_USB;
    }

    protected TunerHal(Context context) {
        mIsStreaming = false;
        mFrequency = -1;
        mModulation = null;
    }

    protected boolean isStreaming() {
        return mIsStreaming;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    protected native void nativeFinalize(long deviceId);

    /**
     * Acquires the first available tuner device. If there is a tuner device that is available, the
     * tuner device will be locked to the current instance.
     *
     * @return {@code true} if the operation was successful, {@code false} otherwise
     */
    protected abstract boolean openFirstAvailable();

    protected abstract boolean isDeviceOpen();

    protected abstract long getDeviceId();

    /**
     * Sets the tuner channel. This should be called after acquiring a tuner device.
     *
     * @param frequency a frequency of the channel to tune to
     * @param modulation a modulation method of the channel to tune to
     * @return {@code true} if the operation was successful, {@code false} otherwise
     */
    public synchronized boolean tune(int frequency, @ModulationType String modulation) {
        if (!isDeviceOpen()) {
            Log.e(TAG, "There's no available device");
            return false;
        }
        if (mIsStreaming) {
            nativeCloseAllPidFilters(getDeviceId());
            mIsStreaming = false;
        }

        // When tuning to a new channel in the same frequency, there's no need to stop current tuner
        // device completely and the only thing necessary for tuning is reopening pid filters.
        if (mFrequency == frequency && Objects.equals(mModulation, modulation)) {
            addPidFilter(PID_PAT, FILTER_TYPE_OTHER);
            addPidFilter(PID_ATSC_SI_BASE, FILTER_TYPE_OTHER);
            mIsStreaming = true;
            return true;
        }
        int timeout_ms = modulation.equals(MODULATION_8VSB) ? DEFAULT_VSB_TUNE_TIMEOUT_MS
                : DEFAULT_QAM_TUNE_TIMEOUT_MS;
        if (nativeTune(getDeviceId(), frequency, modulation, timeout_ms)) {
            addPidFilter(PID_PAT, FILTER_TYPE_OTHER);
            addPidFilter(PID_ATSC_SI_BASE, FILTER_TYPE_OTHER);
            mFrequency = frequency;
            mModulation = modulation;
            mIsStreaming = true;
            return true;
        }
        return false;
    }
    
    public synchronized boolean tune(DeliverySystem deliverySystem, int frequency, 
				String polarization, int symbolRate, String fec, 
				double rolloff, @ModulationType String modulation) {
			
		if (!isDeviceOpen()) {
            Log.e(TAG, "There's no available device");
            return false;
        }
        if (mIsStreaming) {
            nativeCloseAllPidFilters(getDeviceId());
            mIsStreaming = false;
        }
        if (nativeTuneDVB(getDeviceId(), deliverySystem.ordinal(),  frequency, polarization, symbolRate, fec, rolloff, modulation, 8000)) {
			Log.d(TAG, "nativeTuneDVB Success");
            addPidFilter(PID_PAT, FILTER_TYPE_OTHER);
            addPidFilter(PID_SDT, FILTER_TYPE_OTHER);
            //addSectionFilter(PID_PAT, 0x00);
            //addSectionFilter(PID_SDT, 0x42);
            mFrequency = frequency;
            mModulation = modulation;
            mIsStreaming = true;
            return true;
        }
        Log.d(TAG, "nativeTuneDVB Fail");
        return false;

	
	}
				
    protected native boolean nativeTune(long deviceId, int frequency,
            @ModulationType String modulation, int timeout_ms);

	protected native boolean nativeTuneDVB(long deviceId, int deliverySystem, int frequency, 
				String polarization, int symbolRate, String fec, 
				double rolloff, String modulation,int timeout_ms);


    /**
     * Sets a pid filter. This should be set after setting a channel.
     *
     * @param pid a pid number to be added to filter list
     * @param filterType a type of pid. Must be one of (FILTER_TYPE_XXX)
     * @return {@code true} if the operation was successful, {@code false} otherwise
     */
    public synchronized boolean addPidFilter(int pid, @FilterType int filterType) {
        if (!isDeviceOpen()) {
            Log.e(TAG, "There's no available device");
            return false;
        }
        if (pid >= 0 && pid <= 0x1fff) {
            nativeAddPidFilter(getDeviceId(), pid, filterType);
            return true;
        }
        return false;
    }

    protected native void nativeAddPidFilter(long deviceId, int pid, @FilterType int filterType);
    protected native void nativeCloseAllPidFilters(long deviceId);
    protected native void nativeSetHasPendingTune(long deviceId, boolean hasPendingTune);

    public synchronized boolean addSectionFilter(int pid, int tid) {
        if (!isDeviceOpen()) {
            Log.e(TAG, "There's no available device");
            return false;
        }
        if (pid >= 0 && pid <= 0x1fff) {
			Log.d(TAG, "Calling nativeAddSectionFilter");
            nativeAddSectionFilter(getDeviceId(), pid, tid);
            return true;
        }
        return false;
    }
	protected native void nativeAddSectionFilter(long deviceId, int pid, int tid);


    /**
     * Stops current tuning. The tuner device and pid filters will be reset by this call and make
     * the tuner ready to accept another tune request.
     */
    public synchronized void stopTune() {
        if (isDeviceOpen()) {
            if (mIsStreaming) {
                nativeCloseAllPidFilters(getDeviceId());
            }
            nativeStopTune(getDeviceId());
        }
        mIsStreaming = false;
        mFrequency = -1;
        mModulation = null;
    }

    public void setHasPendingTune(boolean hasPendingTune) {
        nativeSetHasPendingTune(getDeviceId(), hasPendingTune);
    }

    protected native void nativeStopTune(long deviceId);

    /**
     * This method must be called after {@link TunerHal#tune} and before
     * {@link TunerHal#stopTune}. Writes at most maxSize TS frames in a buffer
     * provided by the user. The frames employ MPEG encoding.
     *
     * @param javaBuffer a buffer to write the video data in
     * @param javaBufferSize the max amount of bytes to write in this buffer. Usually this number
     *            should be equal to the length of the buffer.
     * @return the amount of bytes written in the buffer. Note that this value could be 0 if no new
     *         frames have been obtained since the last call.
     */
    public synchronized int readTsStream(byte[] javaBuffer, int javaBufferSize) {
        if (isDeviceOpen()) {
            return nativeWriteInBuffer(getDeviceId(), javaBuffer, javaBufferSize);
        } else {
            return 0;
        }
    }

    protected native int nativeWriteInBuffer(long deviceId, byte[] javaBuffer, int javaBufferSize);

    /**
     * Opens Linux DVB frontend device. This method is called from native JNI and used only for
     * UsbTunerHal.
     */
    protected int openDvbFrontEndFd() {
        return -1;
    }

    /**
     * Opens Linux DVB demux device. This method is called from native JNI and used only for
     * UsbTunerHal.
     */
    protected int openDvbDemuxFd() {
        return -1;
    }

    /**
     * Opens Linux DVB dvr device. This method is called from native JNI and used only for
     * UsbTunerHal.
     */
    protected int openDvbDvrFd() {
        return -1;
    }
}
