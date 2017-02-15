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

package com.android.tv.tuner.data;

import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.tv.tuner.data.Track.AtscAudioTrack;
import com.android.tv.tuner.data.Track.AtscCaptionTrack;
import com.android.tv.tuner.ts.SectionParser;
import com.android.tv.tuner.util.ConvertUtils;
import com.android.tv.tuner.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Collection of ATSC Si table items.
 */
public class SiData {

    private SiData() {
    }

    public static class SiSection {
        private final int mTableId;
        private final int mTableIdExtension;
        private final int mSectionNumber;
        private final boolean mCurrentNextIndicator;

        public static SiSection create(byte[] data) {
            if (data.length < 9) {
                return null;
            }
            int tableId = data[0] & 0xff;
            int tableIdExtension = (data[3] & 0xff) << 8 | (data[4] & 0xff);
            int sectionNumber = data[6] & 0xff;
            boolean currentNextIndicator = (data[5] & 0x01) != 0;
            return new SiSection(tableId, tableIdExtension, sectionNumber, currentNextIndicator);
        }

        private SiSection(int tableId, int tableIdExtension, int sectionNumber,
                boolean currentNextIndicator) {
            mTableId = tableId;
            mTableIdExtension = tableIdExtension;
            mSectionNumber = sectionNumber;
            mCurrentNextIndicator = currentNextIndicator;
        }

        public int getTableId() {
            return mTableId;
        }

        public int getTableIdExtension() {
            return mTableIdExtension;
        }

        public int getSectionNumber() {
            return mSectionNumber;
        }

        // This is for indicating that the section sent is applicable.
        // We only consider a situation where currentNextIndicator is expected to have a true value.
        // So, we are not going to compare this variable in hashCode() and equals() methods.
        public boolean getCurrentNextIndicator() {
            return mCurrentNextIndicator;
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + mTableId;
            result = 31 * result + mTableIdExtension;
            result = 31 * result + mSectionNumber;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof SiSection) {
                SiSection another = (SiSection) obj;
                return mTableId == another.getTableId()
                        && mTableIdExtension == another.getTableIdExtension()
                        && mSectionNumber == another.getSectionNumber();
            }
            return false;
        }
    }
    
    
}
