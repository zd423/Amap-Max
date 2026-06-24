package com.autonavi.companion;

/**
 * Registry for overlay UI styles.
 *
 * Add a new style here first, then implement the corresponding panel builder in
 * OverlayService.buildPanelForStyle(). Keeping ids and labels centralized makes
 * downstream UI forks less likely to miss preference, dialog, or preview wiring.
 */
public final class OverlayUiStyles {

    public static final String OLD = "old";
    public static final String NEW = "new";
    public static final String DYNAMIC_ISLAND_FULL = "dynamic_island_full";
    public static final String DYNAMIC_ISLAND_TEST = "dynamic_island";
    public static final String CARD = "card";

    public static final Style[] ALL = {
            new Style(OLD, "\u7ecf\u5178 UI\uff08\u9ed8\u8ba4\uff09", "\u7ecf\u5178 UI\uff08\u9ed8\u8ba4\uff09", false),
            new Style(CARD, "\u5361\u7247 UI", "\u5361\u7247 UI", true),
            new Style(DYNAMIC_ISLAND_FULL, "\u7075\u52a8\u5c9b", "\u7075\u52a8\u5c9b", true),
            new Style(DYNAMIC_ISLAND_TEST, "\u7075\u52a8\u5c9b\uff08\u6d4b\u8bd5\uff09", "\u7075\u52a8\u5c9b\uff08\u6d4b\u8bd5\uff09", true),
            new Style(NEW, "\u65b0 UI\uff08\u5361\u7247\u6837\u5f0f\uff0c\u6d4b\u8bd5\u4e2d\uff09", "\u65b0 UI\uff08\u6d4b\u8bd5\u4e2d\uff09", true)
    };

    private OverlayUiStyles() {
    }

    public static String normalize(String styleId) {
        return ALL[indexOf(styleId)].id;
    }

    public static boolean isCardLike(String styleId) {
        return ALL[indexOf(styleId)].cardLike;
    }

    public static int indexOf(String styleId) {
        if (styleId == null) return 0;
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(styleId)) {
                return i;
            }
        }
        return 0;
    }

    private static String[] cachedLabels;

    public static String[] labels() {
        if (cachedLabels != null) return cachedLabels;
        cachedLabels = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            cachedLabels[i] = ALL[i].dialogLabel;
        }
        return cachedLabels;
    }

    public static String displayName(String styleId) {
        return ALL[indexOf(styleId)].displayName;
    }

    public static final class Style {
        public final String id;
        public final String dialogLabel;
        public final String displayName;
        public final boolean cardLike;

        private Style(String id, String dialogLabel, String displayName, boolean cardLike) {
            this.id = id;
            this.dialogLabel = dialogLabel;
            this.displayName = displayName;
            this.cardLike = cardLike;
        }
    }
}
