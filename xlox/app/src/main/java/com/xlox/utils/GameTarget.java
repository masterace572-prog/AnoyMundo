package com.xlox.utils;

/** Package/native-library identities shared by Home and the virtual process. */
public enum GameTarget {
    BGMI("com.pubg.imobile", "libbgmi.so", "BGMI"),
    BGMI_AIR("com.krafton.bgmilite", "libbgmilite.so", "BGMI Air");

    public final String packageName;
    public final String libraryName;
    public final String displayName;

    GameTarget(String packageName, String libraryName, String displayName) {
        this.packageName = packageName;
        this.libraryName = libraryName;
        this.displayName = displayName;
    }

    public static GameTarget fromPackage(String packageName) {
        for (GameTarget game : values()) {
            if (game.packageName.equals(packageName)) return game;
        }
        return null;
    }

    public static boolean isGameLibrary(String filename) {
        for (GameTarget game : values()) {
            if (game.libraryName.equalsIgnoreCase(filename)) return true;
        }
        return false;
    }

    /** Accept normal :service suffixes and bounded container-rewritten names. */
    private boolean matchesProcess(String name) {
        if (name == null) return false;
        return containsToken(name, packageName) || containsToken(name, packageName.substring(4));
    }

    private static boolean containsToken(String name, String token) {
        int at = name.indexOf(token);
        while (at >= 0) {
            int end = at + token.length();
            boolean before = at == 0 || !identifier(name.charAt(at - 1));
            boolean after = end == name.length() || !identifier(name.charAt(end));
            if (before && after) return true;
            at = name.indexOf(token, at + 1);
        }
        return false;
    }

    private static boolean identifier(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    public static GameTarget forProcess(String name) {
        GameTarget result = null;
        for (GameTarget game : values()) {
            if (game.matchesProcess(name)) {
                if (result != null) return null;
                result = game;
            }
        }
        return result;
    }

    /** Ambiguous callback arguments must never select the other game's ELF. */
    public static GameTarget forProcess(String first, String second) {
        GameTarget a = forProcess(first);
        GameTarget b = forProcess(second);
        return a != null && b != null && a != b ? null : a != null ? a : b;
    }
}
