package com.limelight.foldcade;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Moonlight's own default preferences, shared with standalone Moonlight. */
@SuppressWarnings("deprecation")
final class PreferenceStore implements CompanionSettings.Store {
    private final SharedPreferences prefs;

    private PreferenceStore(SharedPreferences prefs) { this.prefs = prefs; }

    static PreferenceStore of(Context context) {
        return new PreferenceStore(PreferenceManager.getDefaultSharedPreferences(context));
    }

    @Override public boolean has(String key) { return prefs.contains(key); }

    @Override public String string(String key) {
        try { return prefs.getString(key, null); } catch (ClassCastException e) { return null; }
    }

    @Override public Integer integer(String key) {
        if (!prefs.contains(key)) return null;
        try { return prefs.getInt(key, 0); } catch (ClassCastException e) { return null; }
    }

    @Override public Boolean bool(String key) {
        if (!prefs.contains(key)) return null;
        try { return prefs.getBoolean(key, false); } catch (ClassCastException e) { return null; }
    }

    @Override public void put(String key, Object value) {
        SharedPreferences.Editor edit = prefs.edit();
        if (value == null) edit.remove(key);
        else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) edit.putInt(key, (Integer) value);
        else edit.putString(key, value.toString());
        // Moonlight still reads its older Mbps bitrate key when the kbps one is missing.
        if (CompanionSettings.BITRATE.equals(key)) edit.remove("seekbar_bitrate");
        edit.apply();
    }
}
