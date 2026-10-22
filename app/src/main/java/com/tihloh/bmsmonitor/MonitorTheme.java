package com.tihloh.bmsmonitor;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

/** Readable, persisted app palettes. Appearance changes never recreate the BLE activity. */
final class MonitorTheme {
    static final String[] LABELS = {
            "Follow system", "Light", "Dark", "AMOLED Black", "Ocean", "Forest"
    };
    private static final String PREFS = "bms_appearance";
    private static final String KEY = "theme_index";

    final int primary, muted, accent, background, border, surface, soft, input;
    final int found, ordinary, cell, systemBar, onAccent, warning;
    final boolean dark;

    private MonitorTheme(boolean dark, String primary, String muted, String accent,
                         String background, String border, String surface, String soft,
                         String input, String found, String ordinary, String cell,
                         String systemBar, String onAccent, String warning) {
        this.dark = dark;
        this.primary = Color.parseColor(primary);
        this.muted = Color.parseColor(muted);
        this.accent = Color.parseColor(accent);
        this.background = Color.parseColor(background);
        this.border = Color.parseColor(border);
        this.surface = Color.parseColor(surface);
        this.soft = Color.parseColor(soft);
        this.input = Color.parseColor(input);
        this.found = Color.parseColor(found);
        this.ordinary = Color.parseColor(ordinary);
        this.cell = Color.parseColor(cell);
        this.systemBar = Color.parseColor(systemBar);
        this.onAccent = Color.parseColor(onAccent);
        this.warning = Color.parseColor(warning);
    }

    static int selected(Context context) {
        int choice = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY, 0);
        return choice >= 0 && choice < LABELS.length ? choice : 0;
    }

    static void select(Context context, int choice) {
        if (choice >= 0 && choice < LABELS.length)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY, choice).apply();
    }

    static MonitorTheme resolve(Context context) {
        int choice = selected(context);
        boolean systemDark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (choice == 0) choice = systemDark ? 2 : 1;
        switch (choice) {
            case 2: // Dark
                return new MonitorTheme(true,
                        "#EDF5F7", "#B3C2CB", "#4BD4E4", "#121A21",
                        "#334550", "#202C35", "#33444D", "#2B3B46",
                        "#204743", "#283740", "#25414B", "#101820",
                        "#0B2228", "#FFC477");
            case 3: // AMOLED
                return new MonitorTheme(true,
                        "#F5F8FA", "#B9C5CB", "#62DCD2", "#000000",
                        "#30393D", "#111719", "#222E31", "#1A282A",
                        "#153A33", "#151E21", "#173330", "#000000",
                        "#072521", "#FFCE83");
            case 4: // Ocean
                return new MonitorTheme(false,
                        "#18314D", "#5D7388", "#0866AD", "#EFF6FD",
                        "#D8E6F3", "#FFFFFF", "#DCECFB", "#E7F2FC",
                        "#D7F0FC", "#F5FAFF", "#E5F4FE", "#102F52",
                        "#FFFFFF", "#A25300");
            case 5: // Forest
                return new MonitorTheme(false,
                        "#21382D", "#64766C", "#277B50", "#F0F6F0",
                        "#D8E8DC", "#FFFFFF", "#E0EFE3", "#EDF5EE",
                        "#DDF3E5", "#F7FAF7", "#E7F5E9", "#173C2B",
                        "#FFFFFF", "#A15A00");
            default: // Light
                return new MonitorTheme(false,
                        "#1B2B3A", "#637484", "#007C91", "#F2F7F9",
                        "#DEE7EC", "#FFFFFF", "#E8F0F4", "#E8F0F4",
                        "#E7F8F5", "#F8FAFC", "#EBF8FA", "#0F253A",
                        "#FFFFFF", "#AE6200");
        }
    }
}
