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

import android.util.Log;

import com.android.tv.tuner.data.Channel;
import com.android.tv.tuner.TunerHal;

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
    
    private static final int TOKEN_DELIVERY_SYSTEM = 0; 
    private static final int TOKEN_FREQUENCY = 1; 
    private static final int TOKEN_POLARIZATION = 2;
    private static final int TOKEN_SYMBOL_RATE = 3;
    private static final int TOKEN_FEC = 4;
    private static final int TOKEN_ROLL_OFF = 5; 
    private static final int TOKEN_MODULATION = 6;
    
    
    public static final class ScanChannel {
				
		public enum DeliverySystem {
            DELIVERY_SYSTEM_FILE,
            DELIVERY_SYSTEM_ATSC,
            DELIVERY_SYSTEM_DVBS,
            DELIVERY_SYSTEM_DVBS2
        };
        
        public final int type;
        public final int frequency;
        public final String modulation;
        public final String filename;
		
		public final DeliverySystem deliverySystem;
		public final String polarization;
		public final int symbolRate;
		public final String fec;
        public final double rolloff;
        
        /**
         * Radio frequency (channel) number specified at
         * https://en.wikipedia.org/wiki/North_American_television_frequencies
         * This can be {@code null} for cases like cable signal.
         */
        public final Integer radioFrequencyNumber;

        public static ScanChannel forATSCTuner(int frequency, String modulation,
                Integer radioFrequencyNumber) {
            return new ScanChannel(Channel.TYPE_TUNER, DeliverySystem.DELIVERY_SYSTEM_ATSC, frequency, modulation, null,
                    radioFrequencyNumber);
        }

        public static ScanChannel forFile(int frequency, String filename) {
            return new ScanChannel(Channel.TYPE_FILE, DeliverySystem.DELIVERY_SYSTEM_FILE, frequency, "file:", filename, null);
        }

        public static ScanChannel forDVBTuner(DeliverySystem deliverySystem, int frequency, 
				String polarization, int symbolRate, String fec, 
				double rolloff, String modulation) {
            return new ScanChannel(deliverySystem, frequency, polarization, symbolRate, fec, rolloff, modulation);
        }

        private ScanChannel(DeliverySystem deliverySystem, int frequency, 
				String polarization, int symbolRate, String fec, 
				double rolloff, String modulation) {
					
            this.type = Channel.TYPE_TUNER;
            this.frequency = frequency;
            this.modulation = modulation;
            this.filename = null;
            this.radioFrequencyNumber = 0;
            
            this.deliverySystem = deliverySystem;
            this.polarization = polarization;
            this.symbolRate = symbolRate;
            this.fec = fec;
            this.rolloff = rolloff;
            
        }
        
        private ScanChannel(int type,  DeliverySystem deliverySystem, int frequency, String modulation, String filename,
                Integer radioFrequencyNumber) {
            this.type = type;
            this.frequency = frequency;
            this.modulation = modulation;
            this.filename = filename;
            this.radioFrequencyNumber = 0;
            
            this.deliverySystem = deliverySystem;
            this.polarization = null;
            this.symbolRate = 0;
            this.fec = null;
            this.rolloff = 0;
            
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
                Log.d(TAG, "Token Length=" + tokens.length);
                switch (tokens.length) {
					case 3:
					case 4:
						if (tokens[0].equals("A") ) {
							// Only support ATSC if token length is 3 or 4
							scanChannelList.add(ScanChannel.forATSCTuner(Integer.parseInt(tokens[1]), tokens[2],
								tokens.length == 4 ? Integer.parseInt(tokens[3]) : null));
						}
						break;
					case 7:
						if (tokens[0].equals("S") || !tokens[0].equals("S2")) {
							
							ScanChannel.DeliverySystem delsys = tokens[TOKEN_DELIVERY_SYSTEM].equals("S2") ? ScanChannel.DeliverySystem.DELIVERY_SYSTEM_DVBS2 : ScanChannel.DeliverySystem.DELIVERY_SYSTEM_DVBS;
							scanChannelList.add(
								ScanChannel.forDVBTuner(delsys, Integer.parseInt(tokens[TOKEN_FREQUENCY]), tokens[TOKEN_POLARIZATION],
								Integer.parseInt(tokens[TOKEN_SYMBOL_RATE]), tokens[TOKEN_FEC], Double.parseDouble(tokens[TOKEN_ROLL_OFF]), tokens[TOKEN_MODULATION]));
						} else  {
							Log.d(TAG, "Unsupported Delivery System" + tokens[0]);
						}
						break;
					default:
						break;
				}
                
                
                
            }
        } catch (IOException e) {
            Log.e(TAG, "error on parseScanFile()", e);
        }
        return scanChannelList;
    }
}
