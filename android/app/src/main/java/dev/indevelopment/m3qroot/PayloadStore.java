package dev.indevelopment.m3qroot;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RMG-style payload registry: matches this device against the remote
 * targets-v3.json (model + kernel version) and downloads the matching
 * exploit payload. Falls back to the bundled pa1q payload when nothing
 * matches or the registry is unreachable. The tracefs vs physical-P0
 * route is selected at runtime by the engine, so one binary per device
 * profile covers both slide sources.
 */
final class PayloadStore {
    static final String BUNDLED_PAYLOAD_ID = "pa1q-S931BXXUCZZHL";
    private static final String REGISTRY_URL =
            "https://raw.githubusercontent.com/BuSung-dev/Root-My-Galaxy-Payloads/main/support/targets-v3.json";
    private static final String RAW_BASE =
            "https://raw.githubusercontent.com/BuSung-dev/Root-My-Galaxy-Payloads/main/";

    static final class Profile {
        final String payloadId;
        final String displayName;
        final List<String> models;
        final List<String> kernelVersions;
        final String exploitUrl;
        final long exploitSize;

        Profile(String payloadId, String displayName, List<String> models,
                List<String> kernelVersions, String exploitUrl, long exploitSize) {
            this.payloadId = payloadId;
            this.displayName = displayName;
            this.models = models;
            this.kernelVersions = kernelVersions;
            this.exploitUrl = exploitUrl;
            this.exploitSize = exploitSize;
        }
    }

    private PayloadStore() {
    }

    static String deviceModel() {
        return Build.MODEL == null ? "" : Build.MODEL.trim();
    }

    static String deviceKernel() {
        String kernel = System.getProperty("os.version");
        return kernel == null ? "" : kernel.trim();
    }

    static List<Profile> fetchRegistry() throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(REGISTRY_URL).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("registry HTTP " + code);
            String body = readAll(connection.getInputStream());
            List<Profile> profiles = new ArrayList<>();
            try {
                JSONObject root = new JSONObject(body);
                JSONArray payloads = root.getJSONArray("payloads");

                for (int i = 0; i < payloads.length(); i++) {
                    JSONObject entry = payloads.getJSONObject(i);
                    JSONObject exploit = entry.optJSONObject("exploit");
                    profiles.add(new Profile(
                            entry.getString("payloadId"),
                            entry.optString("displayName", ""),
                            jsonArray(entry.getJSONArray("models")),
                            jsonArray(entry.optJSONArray("kernelVersions")),
                            exploit == null ? "" : exploit.optString("url", ""),
                            exploit == null ? -1 : exploit.optLong("size", -1)));
                }
            } catch (org.json.JSONException error) {
                throw new IOException("registry parse error: " + error.getMessage());
            }
            return profiles;
        } finally {
            connection.disconnect();
        }
    }

    /** Remote profile matching this exact model and kernel, or null. */
    static Profile matchRemote(List<Profile> profiles) {
        String model = deviceModel();
        String kernel = deviceKernel();
        for (Profile profile : profiles) {
            if (!profile.models.contains(model)) continue;
            for (String version : profile.kernelVersions) {
                if (!version.isEmpty() && kernel.startsWith(version)) {
                    return profile;
                }
            }
        }
        return null;
    }

    static File cachedPayload(Context context, String payloadId) {
        return new File(context.getFilesDir(), "payload-" + payloadId + ".so");
    }

    static File downloadExploit(Context context, Profile profile)
            throws IOException {
        if (profile.exploitUrl == null || profile.exploitUrl.isEmpty()) {
            throw new IOException("profile has no exploit URL");
        }
        URL url = new URL(profile.exploitUrl.startsWith("http")
                ? profile.exploitUrl : RAW_BASE + profile.exploitUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("payload HTTP " + code);
            File target = cachedPayload(context, profile.payloadId);
            File temp = new File(target.getAbsolutePath() + ".part");
            long total;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                total = 0;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    out.write(buffer, 0, read);
                }
            }
            if (profile.exploitSize > 0 && total != profile.exploitSize) {
                temp.delete();
                throw new IOException("size mismatch: got " + total
                        + " bytes, expected " + profile.exploitSize);
            }
            if (!temp.renameTo(target)) {
                if (!target.delete() || !temp.renameTo(target)) {
                    throw new IOException("could not finalize payload file");
                }
            }
            return target;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[8 * 1024];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static List<String> jsonArray(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) return values;
        for (int i = 0; i < array.length(); i++) {
            values.add(array.optString(i, ""));
        }
        return values;
    }
}
