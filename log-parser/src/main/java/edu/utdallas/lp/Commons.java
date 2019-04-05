package edu.utdallas.lp;

public final class Commons {
    private Commons() {

    }

    public static String getValue(final String pair) {
        final int indexOfColon = pair.indexOf(':');
        return pair.substring(1 + indexOfColon).trim();
    }
}
