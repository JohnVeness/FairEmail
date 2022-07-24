package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2022 by Marcel Bokhorst (M66B)
*/

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.HttpsURLConnection;

public class VirusTotal {
    static final String URI_ENDPOINT = "https://www.virustotal.com/";
    static final String URI_PRIVACY = "https://support.virustotal.com/hc/en-us/articles/115002168385-Privacy-Policy";

    private static final int VT_TIMEOUT = 20; // seconds

    static Bundle scan(Context context, File file) throws NoSuchAlgorithmException, IOException, JSONException {
        String hash;
        try (InputStream is = new FileInputStream(file)) {
            hash = Helper.getHash(is, "SHA-256");
        }

        String uri = URI_ENDPOINT + "gui/file/" + hash;
        Log.i("VT uri=" + uri);

        Bundle result = new Bundle();
        result.putString("uri", uri);

        Pair<Integer, String> response = call(context, "api/v3/files/" + hash);

        if (response == null)
            return result;

        if (response.first != HttpsURLConnection.HTTP_OK &&
                response.first != HttpsURLConnection.HTTP_NOT_FOUND)
            throw new FileNotFoundException(response.second);

        if (response.first == HttpsURLConnection.HTTP_NOT_FOUND) {
            result.putInt("count", 0);
            result.putInt("malicious", 0);
        } else {
            // https://developers.virustotal.com/reference/files
            // Example: https://gist.github.com/M66B/4ea95fdb93fb10bf4047761fcc9ec21a
            JSONObject jroot = new JSONObject(response.second);
            JSONObject jdata = jroot.getJSONObject("data");
            JSONObject jattributes = jdata.getJSONObject("attributes");

            JSONObject jclassification = jattributes.optJSONObject("popular_threat_classification");
            String label = (jclassification == null ? null : jclassification.getString("suggested_threat_label"));

            int count = 0;
            int malicious = 0;
            JSONObject janalysis = jattributes.getJSONObject("last_analysis_results");
            JSONArray jnames = janalysis.names();
            for (int i = 0; i < jnames.length(); i++) {
                String name = jnames.getString(i);
                JSONObject jresult = janalysis.getJSONObject(name);
                String category = jresult.getString("category");
                Log.i("VT " + name + "=" + category);
                if (!"type-unsupported".equals(category))
                    count++;
                if ("malicious".equals(category))
                    malicious++;
            }

            Log.i("VT analysis=" + malicious + "/" + count + " label=" + label);

            result.putInt("count", count);
            result.putInt("malicious", malicious);
            result.putString("label", label);
        }

        return result;
    }

    static Pair<Integer, String> call(Context context, String api) throws IOException {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String apikey = prefs.getString("vt_apikey", null);
        if (TextUtils.isEmpty(apikey))
            return null;

        URL url = new URL(URI_ENDPOINT + api);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setReadTimeout(VT_TIMEOUT * 1000);
        connection.setConnectTimeout(VT_TIMEOUT * 1000);
        ConnectionHelper.setUserAgent(context, connection);
        connection.setRequestProperty("x-apikey", apikey);
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();

        try {
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK) {
                String error = "Error " + status + ": " + connection.getResponseMessage();
                try {
                    InputStream is = connection.getErrorStream();
                    if (is != null)
                        error += "\n" + Helper.readStream(is);
                } catch (Throwable ex) {
                    Log.w(ex);
                }
                return new Pair<>(status, error);
            }

            String response = Helper.readStream(connection.getInputStream());
            Log.i("VT response=" + response);
            return new Pair<>(status, response);

        } finally {
            connection.disconnect();
        }
    }
}
