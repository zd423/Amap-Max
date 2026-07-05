package com.autonavi.companion;

/**
 * Registry for overlay UI styles.
 *
 * Four styles available, all showing traffic lights only.
 */
public final class OverlayUiStyles {

    public static final String CARD = "card";
    public static final String CLASSIC = "classic";
    public static final String DASHBOARD = "dashboard";
    public static final String DYNAMIC_ISLAND = "dynamic_island";

    public static final Style[] ALL = {
            new Style(CARD, "卡片 UI", "卡片 UI", true),
            new Style(CLASSIC, "经典 UI", "经典 UI", true),
            new Style(DASHBOARD, "仪表板 UI", "仪表板 UI", true),
            new Style(DYNAMIC_ISLAND, "灵动岛 UI", "灵动岛 UI", true)
    };

    private OverlayUiStyles() {
    }

    public static String normalize(String styleId) {
        if (CARD.equals(styleId)) return CARD;
        if (CLASSIC.equals(styleId)) return CLASSIC;
        if (DASHBOARD.equals(styleId)) return DASHBOARD;
        if (DYNAMIC_ISLAND.equals(styleId)) return DYNAMIC_ISLAND;
        return CARD;
    }

    public static boolean isCardLike(String styleId) {
        return true;
    }

    public static int indexOf(String styleId) {
        String normalized = normalize(styleId);
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(normalized)) return i;
        }
        return 0;
    }

    public static String[] labels() {
        String[] result = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            result[i] = ALL[i].dialogLabel;
        }
        return result;
    }

    public static String displayName(String styleId) {
        String normalized = normalize(styleId);
        for (Style style : ALL) {
            if (style.id.equals(normalized)) return style.displayName;
        }
        return ALL[0].displayName;
    }

    public static class Style {
        public final String id;
        public final String dialogLabel;
        public final String displayName;
        public final boolean cardLike;

        public Style(String id, String dialogLabel, String displayName, boolean cardLike) {
            this.id = id;
            this.dialogLabel = dialogLabel;
            this.displayName = displayName;
            this.cardLike = cardLike;
        }
    }
}
