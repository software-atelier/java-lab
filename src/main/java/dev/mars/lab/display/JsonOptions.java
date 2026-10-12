package dev.mars.lab.display;

import java.lang.reflect.Array;
import java.util.Map;

/** Shared JDK-only JSON encoding for explicit notebook display wrappers. */
final class JsonOptions {
    private JsonOptions() {}
    static String encode(Object value) { var result=new StringBuilder();write(value,result,0);return result.toString(); }
    private static void write(Object value,StringBuilder out,int depth) {
        if(depth>64)throw new IllegalArgumentException("Display options are cyclic or nested too deeply");
        if(value==null)out.append("null");
        else if(value instanceof String text)string(text,out);
        else if(value instanceof Boolean)out.append(value);
        else if(value instanceof Number number) {
            if(number instanceof Double d&&!Double.isFinite(d)||number instanceof Float f&&!Float.isFinite(f))
                throw new IllegalArgumentException("Display numbers must be finite");
            String token=number.toString();
            if(!token.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("Invalid display number");
            out.append(token);
        } else if(value instanceof ECharts.Series series)write(series.toOption(),out,depth+1);
        else if(value instanceof ECharts.Chart chart)write(chart.toOption(),out,depth+1);
        else if(value instanceof Grid.Column column)write(column.toOption(),out,depth+1);
        else if(value instanceof Map<?,?> map) {
            out.append('{');boolean first=true;
            for(var entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("Display option keys must be strings");
                if(!first)out.append(',');first=false;string(key,out);out.append(':');write(entry.getValue(),out,depth+1);
            }
            out.append('}');
        } else if(value instanceof Iterable<?> items) {
            out.append('[');boolean first=true;
            for(Object item:items){if(!first)out.append(',');first=false;write(item,out,depth+1);}out.append(']');
        } else if(value.getClass().isArray()) {
            out.append('[');for(int i=0;i<Array.getLength(value);i++){if(i>0)out.append(',');write(Array.get(value,i),out,depth+1);}out.append(']');
        } else throw new IllegalArgumentException("Unsupported display option value: "+value.getClass().getName());
        if(out.length()>1_000_000)throw new IllegalArgumentException("Display options exceed 1,000,000 characters");
    }
    private static void string(String value,StringBuilder out) {
        out.append('"');
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\"");case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");case '\r' -> out.append("\\r");case '\t' -> out.append("\\t");
                default -> {if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}
            }
        }
        out.append('"');
    }
}
