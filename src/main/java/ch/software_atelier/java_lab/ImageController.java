package ch.software_atelier.java_lab;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/files")
public class ImageController {
    private final ImageStore images;
    public ImageController(ImageStore images){this.images=images;}
    @GetMapping("/image-info") public ResponseEntity<ImageStore.Info> info(@RequestParam String path) throws IOException {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.info(path,false));}
    @GetMapping("/image-data") public ResponseEntity<ImageStore.Info> data(@RequestParam String path) throws IOException {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(images.info(path,true));}
    @GetMapping("/image") public ResponseEntity<byte[]> image(@RequestParam String path) throws IOException {
        var source=images.read(path);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(source.mime())).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:").body(source.bytes());
    }
}
