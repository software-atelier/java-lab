package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.display.Images;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ImageStore {
    private final NotebookStore files;
    public ImageStore(NotebookStore files){this.files=files;}
    public record Info(String path,String mime,int width,int height,long size,String revision,String dataUri) {}
    public Images.Source read(String path) throws IOException {
        Path file=files.existingFile(path);
        if(!NotebookStore.fileType(path).equals("image"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a supported image filename");
        try(var input=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=input.readNBytes(Images.MAX_BYTES+1);return Images.inspect(bytes);
        }catch(IOException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
    }
    public Info info(String path,boolean includeData) throws IOException {
        var image=read(path);String revision;
        try{revision=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.bytes()));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return new Info(path,image.mime(),image.width(),image.height(),image.bytes().length,revision,
            includeData?"data:"+image.mime()+";base64,"+Base64.getEncoder().encodeToString(image.bytes()):null);
    }
}
