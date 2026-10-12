package dev.mars.lab.display;

import java.util.Arrays;

/** Notebook logging with sequential '%' placeholders, without printf formatting rules. */
public final class Logs {
    private Logs() {}

    public static void log(String msg, Object... args) {
        if (msg == null) { System.out.println("null");return; }
        StringBuilder output = new StringBuilder(msg.length());
        int argument = 0;
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if (c == '%' && args != null && argument < args.length) output.append(format(args[argument++]));
            else output.append(c);
        }
        System.out.println(output);
    }

    private static String format(Object value) {
        if(value!=null&&value.getClass().isArray()) {
            String array=Arrays.deepToString(new Object[]{value});return array.substring(1,array.length()-1);
        }
        return String.valueOf(value);
    }
}
