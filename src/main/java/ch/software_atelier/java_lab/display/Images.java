package ch.software_atelier.java_lab.display;

import ch.software_atelier.java_lab.data.XmlDocuments;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/** Image inspection and display, available both in the server and each remote notebook kernel. */
public final class Images {
    public static final int MAX_BYTES=10000000;
    private Images() {}
    public record Source(String mime,int width,int height,byte[] bytes) {}
    public static Source load(Path path) throws IOException {
        try(var input=Files.newInputStream(path)){
            byte[] bytes=input.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IOException("Image exceeds 10 MB");return inspect(bytes);
        }
    }
    public static Source inspect(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("Provide an image of 1 byte–10 MB");
        if(bytes.length>=25&&ascii(bytes,0,4).equals("RIFF")&&ascii(bytes,8,4).equals("WEBP")){
            String chunk=ascii(bytes,12,4);int width,height;
            if(chunk.equals("VP8X")&&bytes.length>=30){width=1+little(bytes,24,3);height=1+little(bytes,27,3);}
            else if(chunk.equals("VP8L")&&(bytes[20]&255)==47){width=1+((bytes[21]&255)|((bytes[22]&63)<<8));height=1+((bytes[22]&255)>>6|((bytes[23]&255)<<2)|((bytes[24]&15)<<10));}
            else if(chunk.equals("VP8 ")&&bytes.length>=30&&(bytes[23]&255)==157&&(bytes[24]&255)==1&&(bytes[25]&255)==42){width=little(bytes,26,2)&16383;height=little(bytes,28,2)&16383;}
            else throw new IOException("Unsupported WebP header");
            return source("image/webp",width,height,bytes);
        }
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);
            if(readers.hasNext()){
                var reader=readers.next();
                try{
                    reader.setInput(input,true,true);
                    String mime=switch(reader.getFormatName().toLowerCase(Locale.ROOT)){case "png"->"image/png";case "jpeg","jpg"->"image/jpeg";case "gif"->"image/gif";case "bmp"->"image/bmp";default->null;};
                    if(mime!=null)return source(mime,reader.getWidth(0),reader.getHeight(0),bytes);
                }finally{reader.dispose();}
            }
        }
        try{
            var svg=XmlDocuments.parse(bytes).getDocumentElement();
            if(!"svg".equals(svg.getLocalName())||!"http://www.w3.org/2000/svg".equals(svg.getNamespaceURI()))throw new IOException("Not an SVG image");
            int width=dimension(svg.getAttribute("width")),height=dimension(svg.getAttribute("height"));
            String[] box=svg.getAttribute("viewBox").trim().split("[ ,]+");
            if(box.length==4){if(width==0)width=dimension(box[2]);if(height==0)height=dimension(box[3]);}
            return source("image/svg+xml",width==0?300:width,height==0?150:height,bytes);
        }catch(org.xml.sax.SAXException|IllegalArgumentException e){throw new IOException("Invalid or unsupported image (PNG, JPEG, GIF, BMP, WebP or SVG required)",e);}
    }
    private static Source source(String mime,int width,int height,byte[] bytes) throws IOException {
        if(width<1||height<1||width>10000||height>10000||(long)width*height>40000000)throw new IOException("Image dimensions exceed 10,000 pixels per side / 40 million pixels");
        return new Source(mime,width,height,bytes);
    }
    private static String ascii(byte[] bytes,int offset,int length){return new String(bytes,offset,length,StandardCharsets.US_ASCII);}
    private static int little(byte[] bytes,int offset,int count){int value=0;for(int i=0;i<count;i++)value|=(bytes[offset+i]&255)<<(i*8);return value;}
    private static int dimension(String text){
        if(!text.matches("[0-9]+(?:\\.[0-9]+)?(?:px)?"))return 0;
        double value=Double.parseDouble(text.replace("px",""));return value>10000?10001:(int)Math.ceil(value);
    }
    public static void show(String path) throws IOException {show(Path.of(path));}
    public static void show(Path path) throws IOException {var source=load(path);Display.emitImage(source.mime(),path.getFileName().toString(),source.width(),source.height(),source.bytes());}
    public static void show(String path,int width,int height) throws IOException {
        show(Path.of(path),width,height);
    }
    public static void show(Path path,int width,int height) throws IOException {
        if(width<1||height<1||width>10000||height>10000)throw new IllegalArgumentException("Display bounds must be between 1 and 10,000 pixels");
        var source=load(path);double scale=Math.min((double)width/source.width(),(double)height/source.height());
        Display.emitImage(source.mime(),path.getFileName().toString(),Math.max(1,(int)Math.round(source.width()*scale)),Math.max(1,(int)Math.round(source.height()*scale)),source.bytes());
    }
}
