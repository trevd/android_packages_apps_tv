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

package com.android.usbtuner;

import android.util.Log;

import com.android.usbtuner.data.Channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses plain text formatted scan files, which contain the list of channels.
 */
public class ChannelScanFileParser {
    private static final String TAG = "ChannelScanFileParser";

    public static final class ScanChannel {
        public final int type;
        public final int frequency;
        public final String modulation;
        public final String polarization;
        public final int symbolrate;
        public final String fec;
        public final String filename;

        public enum DeliverySystem {
            DELIVERY_SYSTEM_FILE,
            DELIVERY_SYSTEM_ATSC,
            DELIVERY_SYSTEM_DVBS
        };
        public final DeliverySystem deliverysystem;
        public static ScanChannel forTuner(int frequency, String modulation) {
            return new ScanChannel(Channel.TYPE_TUNER, frequency, modulation, null, 0, null, null, DeliverySystem.DELIVERY_SYSTEM_ATSC);
        }
        public static ScanChannel forSatellite(int frequency, String polarization, int symbolrate, String fec) {
            return new ScanChannel(Channel.TYPE_TUNER, frequency, "QPSK", polarization, symbolrate, fec, null, DeliverySystem.DELIVERY_SYSTEM_DVBS);
        }

        public static ScanChannel forFile(int frequency, String filename) {
            return new ScanChannel(Channel.TYPE_FILE, frequency, "file:", null, 0, null, filename, DeliverySystem.DELIVERY_SYSTEM_FILE);
        }

        private ScanChannel(int type, int frequency, String modulation, String polarization, int symbolrate, String fec,  String filename, DeliverySystem deliverysystem) {
            this.type = type;
            this.frequency = frequency;
            this.modulation = modulation;
            this.polarization = polarization;
            this.symbolrate = symbolrate;
            this.fec = fec;
            this.filename = filename;
            this.deliverysystem = deliverysystem;
        }
    }

    /**
     * Parses a given scan file and returns the list of {@link ScanChannel} objects.
     *
     * @param is {@link InputStream} of a scan file. Each line matches one channel.
     *           The line format of the scan file is as follows:<br>
     *           "A &lt;frequency&gt; &lt;modulation&gt;".
     * @return a list of {@link ScanChannel} objects parsed
     */
    public static List<ScanChannel> parseScanFile(InputStream is) {
        BufferedReader in = new BufferedReader(new InputStreamReader(is));
        String line;
        List<ScanChannel> scanChannelList = new ArrayList<>();
        try {
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == '#') {
                    // Skip comment line
                    continue;
                }
                String[] tokens = line.split("\\s+");
                if (tokens.length == 3 && tokens[0].equals("A")) {
                    scanChannelList.add(ScanChannel.forTuner(Integer.parseInt(tokens[1]), tokens[2]));
                } else if (tokens.length == 5 && tokens[0].equals("S")) {
                    Log.i(TAG, "Adding Transponder Freq:" + tokens[1]);
                    scanChannelList.add(ScanChannel.forSatellite(Integer.parseInt(tokens[1]), tokens[2], Integer.parseInt(tokens[3]), tokens[4]));
                } else {
                    Log.i(TAG, "Token Length Incorrect:" + tokens.length);
                    continue;
                }

            }
        } catch (IOException e) {
            Log.e(TAG, "error on parseScanFile()", e);
        }
        return scanChannelList;
    }
}
