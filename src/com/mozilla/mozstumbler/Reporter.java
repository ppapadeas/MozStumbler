package com.mozilla.mozstumbler;

import android.annotation.SuppressLint;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

class Reporter {
    private static final String LOGTAG = "Reporter";
    private static final String LOCATION_URL = "https://location.services.mozilla.com/v1/submit";

    // copied from
    // http://code.google.com/p/sensor-data-collection-library/source/browse/src/main/java/TextFileSensorLog.java#223,
    // which is apache licensed
    private static final Set<Character> AD_HOC_HEX_VALUES = new HashSet<Character>(
            Arrays.asList('2', '6', 'a', 'e', 'A', 'E'));

    private static final String OPTOUT_SSID_SUFFIX = "_nomap";

    private final MessageDigest mSHA1;

    Reporter() {
        try {
            mSHA1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressLint("SimpleDateFormat")
    void reportLocation(Location location, Collection<ScanResult> scanResults, int radioType, JSONArray cellInfo) {
        final JSONObject locInfo = new JSONObject();
        try {
            DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
            locInfo.put("time", df.format(new Date(location.getTime())));
            locInfo.put("lon", location.getLongitude());
            locInfo.put("lat", location.getLatitude());
            locInfo.put("accuracy", (int) location.getAccuracy());
            locInfo.put("altitude", (int) location.getAltitude());

            locInfo.put("cell", cellInfo);
            if (radioType == TelephonyManager.PHONE_TYPE_GSM)
                locInfo.put("radio", "gsm");

            JSONArray wifiInfo = new JSONArray();
            if (scanResults != null) {
                for (ScanResult ap : scanResults) {
                    if (!shouldLog(ap))
                        continue;
                    StringBuilder sb = new StringBuilder();
                    try {
                        byte[] result = mSHA1.digest((ap.BSSID + ap.SSID)
                                .getBytes("UTF-8"));
                        for (byte b : result)
                            sb.append(String.format("%02X", b));

                        JSONObject obj = new JSONObject();
                        obj.put("key", sb.toString());
                        obj.put("frequency", ap.frequency);
                        obj.put("signal", ap.level);
                        wifiInfo.put(obj);
                    } catch (UnsupportedEncodingException uee) {
                        Log.w(LOGTAG, "can't encode the key", uee);
                    }
                }
            }
            locInfo.put("wifi", wifiInfo);
        } catch (JSONException jsonex) {
            Log.w(LOGTAG, "json exception", jsonex);
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    URL url = new URL(LOCATION_URL);
                    HttpURLConnection urlConnection = (HttpURLConnection) url
                            .openConnection();
                    try {
                        urlConnection.setDoOutput(true);
                        JSONArray batch = new JSONArray();
                        batch.put(locInfo);
                        JSONObject wrapper = new JSONObject();
                        wrapper.put("items", batch);
                        byte[] bytes = wrapper.toString().getBytes();
                        urlConnection.setFixedLengthStreamingMode(bytes.length);
                        OutputStream out = new BufferedOutputStream(
                                urlConnection.getOutputStream());
                        out.write(bytes);
                        out.flush();
                    } catch (JSONException jsonex) {
                        Log.e(LOGTAG, "error wrapping data as a batch", jsonex);
                    } catch (Exception ex) {
                        Log.e(LOGTAG, "error submitting data", ex);
                    } finally {
                        urlConnection.disconnect();
                    }
                } catch (Exception ex) {
                    Log.e(LOGTAG, "error submitting data", ex);
                }
            }
        }).start();
    }

    private static boolean shouldLog(final ScanResult sr) {
        // We filter out any ad-hoc devices. Ad-hoc devices are identified by
        // having a
        // 2,6,a or e in the second nybble.
        // See http://en.wikipedia.org/wiki/MAC_address -- ad hoc networks
        // have the last two bits of the second nybble set to 10.
        // Only apply this test if we have exactly 17 character long BSSID which
        // should
        // be the case.
        final char secondNybble = sr.BSSID.length() == 17 ? sr.BSSID.charAt(1)
                : ' ';

        if (AD_HOC_HEX_VALUES.contains(secondNybble)) {
            return false;

        } else if (sr.SSID != null && sr.SSID.endsWith(OPTOUT_SSID_SUFFIX)) {
            return false;
        } else {
            return true;
        }
    }
}
