package de.qso.again;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {

    public static Runnable onDurationChanged;

    private SharedPreferences activityPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

    static int currentMaxMinutesByRam(SharedPreferences prefs) {
        int sampleRate = Integer.parseInt(prefs.getString("sample_rate", "44100"));
        int bitDepth = Integer.parseInt(prefs.getString("bit_depth", "16"));
        long bytesPerSecond = (long) sampleRate * (bitDepth / 8);
        if (bytesPerSecond <= 0) return 1;
        long usableBytes = AudioRecorderService.getUsableBytesForRecording();
        return Math.max(1, (int) (usableBytes / bytesPerSecond / 60));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        
        try {
            if (prefs.contains("max_duration")) {
                int storedDuration = prefs.getInt("max_duration", 5);
                prefs.edit().remove("max_duration").putString("max_duration", String.valueOf(storedDuration)).apply();
            }
        } catch (ClassCastException e) {
        }
        
        applyLanguage();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_layout);
        
        final SharedPreferences prefs2 = prefs;
        
        if (!prefs2.contains("max_duration")) {
            prefs2.edit().putString("max_duration", "5").apply();
        }
        
        TextView tvDuration = findViewById(R.id.tvDurationValue);
        Runnable updateDurationDisplay = () -> {
            int value = Integer.parseInt(prefs2.getString("max_duration", "5"));
            int maxByRam = currentMaxMinutesByRam(prefs2);
            tvDuration.setText(value + " / " + maxByRam + " min");
        };
        updateDurationDisplay.run();

        Button btnMinus = findViewById(R.id.btnDurationMinus);
        Button btnPlus = findViewById(R.id.btnDurationPlus);

        btnMinus.setOnClickListener(v -> {
            int current = Integer.parseInt(prefs2.getString("max_duration", "5"));
            int newValue = Math.max(1, current - 1);
            prefs2.edit().putString("max_duration", String.valueOf(newValue)).apply();
            updateDurationDisplay.run();
            if (onDurationChanged != null) onDurationChanged.run();
        });

        btnPlus.setOnClickListener(v -> {
            int current = Integer.parseInt(prefs2.getString("max_duration", "5"));
            int max = currentMaxMinutesByRam(prefs2);
            int newValue = Math.min(max, current + 1);
            prefs2.edit().putString("max_duration", String.valueOf(newValue)).apply();
            updateDurationDisplay.run();
            if (onDurationChanged != null) onDurationChanged.run();
        });

        View.OnLongClickListener accelListener = v -> {
            int current = Integer.parseInt(prefs2.getString("max_duration", "5"));
            int max = currentMaxMinutesByRam(prefs2);
            int step = v.getId() == R.id.btnDurationMinus ? -10 : 10;
            int newValue = Math.max(1, Math.min(max, current + step));
            prefs2.edit().putString("max_duration", String.valueOf(newValue)).apply();
            updateDurationDisplay.run();
            if (onDurationChanged != null) onDurationChanged.run();
            return true;
        };

        btnMinus.setOnLongClickListener(accelListener);
        btnPlus.setOnLongClickListener(accelListener);

        CheckBox cbAutoSave = findViewById(R.id.cbAutoSave);
        cbAutoSave.setChecked(prefs2.getBoolean("auto_save", false));
        cbAutoSave.setOnCheckedChangeListener((buttonView, isChecked) ->
            prefs2.edit().putBoolean("auto_save", isChecked).apply());

        // When sample rate or bit depth changes, clamp max_duration to the new RAM-derived cap
        // and refresh the duration display.
        activityPrefs = prefs2;
        prefsListener = (sp, key) -> {
            if ("sample_rate".equals(key) || "bit_depth".equals(key)) {
                int current = Integer.parseInt(sp.getString("max_duration", "5"));
                int max = currentMaxMinutesByRam(sp);
                if (current > max) {
                    sp.edit().putString("max_duration", String.valueOf(max)).apply();
                }
                updateDurationDisplay.run();
                if (onDurationChanged != null) onDurationChanged.run();
            }
        };
        prefs2.registerOnSharedPreferenceChangeListener(prefsListener);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
    }
    
    @Override
    protected void onDestroy() {
        if (activityPrefs != null && prefsListener != null) {
            activityPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        super.onDestroy();
    }

    private void applyLanguage() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String lang = prefs.getString("language", "system");
        
        Locale locale;
        if ("system".equals(lang)) {
            locale = Locale.getDefault();
        } else {
            locale = new Locale(lang);
        }
        
        Locale.setDefault(locale);
        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());
    }
    
    public static void applyLanguageStatic(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String lang = prefs.getString("language", "system");
        
        Locale locale;
        if ("system".equals(lang)) {
            locale = Locale.getDefault();
        } else {
            locale = new Locale(lang);
        }
        
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        context.getResources().updateConfiguration(config, context.getResources().getDisplayMetrics());
    }
    
    public static class SettingsFragment extends PreferenceFragmentCompat {
        private SharedPreferences prefs;
        private ListPreference sampleRatePref;
        private ListPreference bitDepthPref;
        private Preference memPref;
        private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            Preference versionPref = findPreference("version");
            if (versionPref != null) {
                versionPref.setSummary(BuildConfig.VERSION_NAME);
            }
            Preference buildDatePref = findPreference("build_date");
            if (buildDatePref != null) {
                buildDatePref.setSummary(BuildConfig.BUILD_TIMESTAMP);
            }

            ListPreference langPref = findPreference("language");
            if (langPref != null) {
                langPref.setOnPreferenceChangeListener((preference, newValue) -> {
                    SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getActivity());
                    p.edit().putString("language", (String) newValue).apply();
                    return true;
                });
            }

            sampleRatePref = findPreference("sample_rate");
            bitDepthPref = findPreference("bit_depth");
            memPref = findPreference("memory_usage");
            prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());

            if (sampleRatePref != null && memPref != null) {
                if (!prefs.contains("max_duration")) {
                    prefs.edit().putString("max_duration", "5").apply();
                }

                sampleRatePref.setOnPreferenceChangeListener((pref, newValue) -> {
                    updateMemoryDisplay(Integer.parseInt((String) newValue), readBitDepth());
                    return true;
                });

                if (bitDepthPref != null) {
                    bitDepthPref.setOnPreferenceChangeListener((pref, newValue) -> {
                        updateMemoryDisplay(readSampleRate(), Integer.parseInt((String) newValue));
                        return true;
                    });
                }

                prefsListener = (sharedPrefs, key) -> {
                    if ("max_duration".equals(key)) {
                        updateMemoryDisplay(readSampleRate(), readBitDepth());
                    }
                };
                prefs.registerOnSharedPreferenceChangeListener(prefsListener);

                updateMemoryDisplay(readSampleRate(), readBitDepth());
            }
        }

        private int readSampleRate() {
            return sampleRatePref != null ? Integer.parseInt(sampleRatePref.getValue()) : 22050;
        }

        private int readBitDepth() {
            return bitDepthPref != null ? Integer.parseInt(bitDepthPref.getValue()) : 16;
        }

        private void updateMemoryDisplay(int sampleRate, int bitDepth) {
            int maxDuration = Integer.parseInt(prefs.getString("max_duration", "5"));
            long bytesPerSecond = (long) sampleRate * (bitDepth / 8);
            long bytesPerDuration = bytesPerSecond * maxDuration * 60;
            double mbPerDuration = bytesPerDuration / (1024.0 * 1024.0);
            String summary = String.format(Locale.getDefault(), "%.1f MB", mbPerDuration);
            memPref.setTitle("Audio Memory consumption (RAM / File): " + summary);
        }

        @Override
        public void onDestroy() {
            if (prefs != null && prefsListener != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
            }
            super.onDestroy();
        }
    }
}
