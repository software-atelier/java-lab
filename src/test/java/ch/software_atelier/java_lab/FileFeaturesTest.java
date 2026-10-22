package ch.software_atelier.java_lab;

import ch.software_atelier.java_lab.display.Images;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FileFeaturesTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper();
    @Test void duplicatesFilesInTheirFolderWithoutChangingBytesOrReplacingExistingEntries() throws Exception {
        var store=new NotebookStore(mapper,directory);
        var controller=new FileController(new TextFileStore(store),store);
        Files.createDirectory(directory.resolve("nested"));
        byte[] content={0,(byte)255,13,10,42};
        for(String name:List.of("data.csv","notes.txt","query.sql","photo.png","archive.bin","no-extension","book.jnb","legacy.ipynb")){
            Files.write(directory.resolve("nested").resolve(name),content);
            var copy=controller.duplicate(new FileController.DuplicateRequest("nested/"+name));
            assertThat(copy.path()).startsWith("nested/");
            assertThat(copy.name()).isNotEqualTo(name).contains("-copy");
            assertThat(Files.readAllBytes(directory.resolve(copy.path()))).isEqualTo(content);
            var second=controller.duplicate(new FileController.DuplicateRequest("nested/"+name));
            assertThat(second.name()).contains("-copy-1");
            assertThat(Files.readAllBytes(directory.resolve(copy.path()))).isEqualTo(content);
            assertThat(Files.readAllBytes(directory.resolve("nested").resolve(name))).isEqualTo(content);
        }
        Files.createDirectory(directory.resolve("nested/blocked-copy.txt"));
        Files.writeString(directory.resolve("nested/blocked.txt"),"source");
        assertThat(store.duplicateFile("nested/blocked.txt").name()).isEqualTo("blocked-copy-1.txt");
        for(String path:List.of("../outside.txt",".hidden","nested","/absolute.txt","nested/missing.txt"))
            assertThatThrownBy(()->store.duplicateFile(path)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        Files.createSymbolicLink(directory.resolve("alias.txt"),directory.resolve("nested/blocked.txt"));
        assertThatThrownBy(()->store.duplicateFile("alias.txt")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void notebookCopiesDoNotInheritLocalVariableValues() throws Exception {
        var store=new NotebookStore(mapper,directory);String name=store.create("Original");var doc=store.read(name);
        doc.withObject("metadata").withObject("java_lab").putArray("variables").addObject().put("name","TOKEN").put("secret",true);
        store.save(name,doc);store.updateValues(name,Map.of("TOKEN","local-only"),List.of());
        var copy=store.duplicateFile(name);
        assertThat(store.read(copy.path())).isEqualTo(store.read(name));
        assertThat(store.values().read(directory.resolve(copy.path()))).isEmpty();
        assertThat(store.effectiveValues(name)).containsEntry("TOKEN","local-only");
    }
    @Test void deletesAllFileKindsInSubfoldersThroughTheFileController() throws Exception {
        var store=new NotebookStore(mapper,directory);
        var controller=new FileController(new TextFileStore(store),store);
        Files.createDirectory(directory.resolve("nested"));
        for(String name:List.of("data.csv","data.json","data.xml","notes.txt","notes.md","config.yaml","Main.java","query.sql","config.properties","app.log","style.css","app.js","index.html","photo.png","photo.svg","archive.bin","no-extension","book.jnb","legacy.ipynb")){
            Path file=directory.resolve("nested").resolve(name);Files.writeString(file,"content");
            controller.delete("nested/"+name);assertThat(file).doesNotExist();
        }
        assertThat(directory.resolve("nested")).isDirectory();
        assertThatThrownBy(()->controller.delete("nested/missing.txt")).hasMessageContaining("404");
        for(String path:List.of("../outside.txt",".hidden","nested","/absolute.txt"))
            assertThatThrownBy(()->controller.delete(path)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        Path target=Files.writeString(directory.resolve("keep.txt"),"keep");
        Files.createSymbolicLink(directory.resolve("alias.txt"),target);
        assertThatThrownBy(()->controller.delete("alias.txt")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(target).hasContent("keep");
    }
    @Test void yamlValidationChecksAllDocumentsAndReportsLocationsWithoutConstructingTaggedObjects() throws Exception {
        var store=new TextFileStore(new NotebookStore(mapper,directory));
        assertThat(store.validateYaml("# empty document\n").valid()).isTrue();
        assertThat(store.validateYaml("name: Ada\nactive: true\ntext: |\n  # literal\n---\nitems: [1, 2]\n").valid()).isTrue();
        assertThat(store.validateYaml("value: !application/custom tagged\n").valid()).isTrue();
        var invalid=store.validateYaml("name: Ada\nitems: [1, 2\n");
        assertThat(invalid.valid()).isFalse();assertThat(invalid.line()).isGreaterThanOrEqualTo(2);assertThat(invalid.column()).isPositive();
        assertThat(store.validateYaml("ok: true\n---\nitems: [\n").valid()).isFalse();
        assertThat(store.validateYaml("name: Ada\n\tbroken: indent").valid()).isFalse();
        assertThat(store.validateYaml("[".repeat(110)+"0"+"]".repeat(110)).valid()).isFalse();
        assertThatThrownBy(()->store.validateYaml(null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->store.validateYaml("x".repeat(TextFileStore.MAX_BYTES+1))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var controller=new FileController(store,new NotebookStore(mapper,directory));
        assertThat(controller.validateYaml(new FileController.YamlRequest("key: value")).valid()).isTrue();
        try(var paths=Files.list(directory)){assertThat(paths).isEmpty();}
    }
    @Test void xmlValidationUsesDraftContentAndReportsLineColumnWithoutChangingFiles() throws Exception {
        var store=new TextFileStore(new NotebookStore(mapper,directory));
        assertThat(store.validateXml("<?xml version=\"1.0\"?><root xmlns=\"urn:test\"><value/></root>").valid()).isTrue();
        assertThat(store.validateXml("<root><parsererror>ordinary data</parsererror></root>").valid()).isTrue();
        var invalid=store.validateXml("<root>\n<value></root>");assertThat(invalid.valid()).isFalse();assertThat(invalid.line()).isEqualTo(2);assertThat(invalid.column()).isPositive();
        assertThat(store.validateXml("<x:root/>").valid()).isFalse();
        assertThat(store.validateXml("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///private/no-read'>]><root>&x;</root>").valid()).isFalse();
        try(var paths=Files.list(directory)){assertThat(paths).isEmpty();}
    }
    @Test void createsEveryEditableFileKindWithoutOverwritingAndRenamesAllFilesSafely() throws Exception {
        var store=new NotebookStore(mapper,directory);
        for(String extension:List.of("csv","json","xml","txt","md","yaml","java","sql","properties","log","css","js","html")){
            var file=store.createFile("","example."+extension,extension);assertThat(Files.exists(directory.resolve(file.path()))).isTrue();
            byte[] before=Files.readAllBytes(directory.resolve(file.path()));
            var renamed=store.renameFile(file.path(),"renamed."+extension);
            assertThat(Files.readAllBytes(directory.resolve(renamed.path()))).isEqualTo(before);
        }
        assertThat(store.createFile("","renamed.json","json").name()).isEqualTo("renamed-1.json");
        assertThat(store.createFile("","alias.yml","yaml").format()).isEqualTo("yaml");assertThat(store.createFile("","alias.htm","html").format()).isEqualTo("html");
        assertThat(new TextFileStore(store).validateXml(Files.readString(directory.resolve("renamed.xml"))).valid()).isTrue();
        assertThatThrownBy(()->store.renameFile("renamed.txt","renamed.json")).hasMessageContaining("already exists");
        for(String name:List.of("../outside.txt",".hidden.txt","sub/file.txt"))assertThatThrownBy(()->store.renameFile("renamed.txt",name)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        Files.write(directory.resolve("binary.bin"),new byte[]{0,1,2});store.renameFile("binary.bin","new.bin");assertThat(Files.readAllBytes(directory.resolve("new.bin"))).isEqualTo(new byte[]{0,1,2});
        Files.writeString(directory.resolve("Case.txt"),"case only");store.renameFile("Case.txt","CASE.TXT");
        assertThat(store.list()).extracting(NotebookStore.Entry::name).contains("CASE.TXT").doesNotContain("Case.txt");
    }
    @Test void genericNotebookRenameKeepsLocalValues() throws Exception {
        var store=new NotebookStore(mapper,directory);String name=store.create("Original");var doc=store.read(name);
        doc.withObject("metadata").withObject("java_lab").putArray("variables").addObject().put("name","TOKEN").put("secret",true);
        store.save(name,doc);store.updateValues(name,Map.of("TOKEN","local-only"),List.of());
        var result=store.renameFile(name,"Renamed.jnb");assertThat(store.effectiveValues(result.path())).containsEntry("TOKEN","local-only");
        assertThat(Files.readString(directory.resolve(result.path()))).doesNotContain("local-only");
    }
    @Test void imagePreviewMetadataAndJavaOutputSupportRasterAndSvgWithEmbeddedSnapshots() throws Exception {
        var store=new NotebookStore(mapper,directory);var images=new ImageStore(store);
        ImageIO.write(new BufferedImage(40,30,BufferedImage.TYPE_INT_RGB),"png",directory.resolve("small.png").toFile());
        Files.writeString(directory.resolve("icon.svg"),"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 10\"><rect width=\"20\" height=\"10\"/></svg>");
        var png=images.info("small.png",true);assertThat(png.width()).isEqualTo(40);assertThat(png.dataUri()).startsWith("data:image/png;base64,");
        var svg=images.info("icon.svg",true);assertThat(svg.mime()).isEqualTo("image/svg+xml");assertThat(svg.width()).isEqualTo(20);
        var response=new ImageController(images).image("icon.svg");assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox","default-src 'none'");
        try(var kernels=new KernelService(store)){
            String id=kernels.create();var shown=kernels.execute(id,"Images.show(\"small.png\"); Images.show(\"icon.svg\");");
            assertThat(shown.success()).as(shown.outputs().toString()).isTrue();assertThat(shown.outputs()).hasSize(2);
            assertThat(shown.outputs().toString()).contains("image/png","image/svg+xml");
        }
        assertThatThrownBy(()->Images.inspect("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///no'>]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>".getBytes())).isInstanceOf(java.io.IOException.class);
        Files.createSymbolicLink(directory.resolve("alias.png"),directory.resolve("small.png"));assertThatThrownBy(()->images.read("alias.png")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
