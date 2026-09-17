package com.kooritea.fcmfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 配置兜底：system_server 中的 hook 可直接读模块应用的 config.json。
 * 远程 SharedPreferences 不可用时，勾选列表仍能生效。
 */
public class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        JSONObject config = new JSONObject();
        boolean loaded = false;
        try {
            FileInputStream fis = getContext().openFileInput("config.json");
            InputStreamReader inputStreamReader = new InputStreamReader(fis, StandardCharsets.UTF_8);
            StringBuilder stringBuilder = new StringBuilder();
            BufferedReader reader = new BufferedReader(inputStreamReader);
            String line = reader.readLine();
            while (line != null) {
                stringBuilder.append(line).append('\n');
                line = reader.readLine();
            }
            reader.close();
            config = new JSONObject(stringBuilder.toString());
            loaded = true;
        } catch (IOException | JSONException | NullPointerException e) {
            // config.json 不存在或损坏
        }
        String[] COLUMN_NAME = {"key", "value"};
        MatrixCursor data = new MatrixCursor(COLUMN_NAME);
        if (loaded) {
            data.addRow(new Object[]{"init", "1"});
            try {
                data.addRow(new Object[]{"disableAutoCleanNotification", config.isNull("disableAutoCleanNotification") ? "0" : (config.getBoolean("disableAutoCleanNotification") ? "1" : "0")});
                data.addRow(new Object[]{"includeIceBoxDisableApp", config.isNull("includeIceBoxDisableApp") ? "0" : (config.getBoolean("includeIceBoxDisableApp") ? "1" : "0")});
                data.addRow(new Object[]{"noResponseNotification", config.isNull("noResponseNotification") ? "0" : (config.getBoolean("noResponseNotification") ? "1" : "0")});
                JSONArray jsonAllowList = config.getJSONArray("allowList");
                for (int i = 0; i < jsonAllowList.length(); i++) {
                    data.addRow(new Object[]{"allowList", jsonAllowList.getString(i)});
                }
            } catch (JSONException ignored) {
            }
        }
        return data;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
