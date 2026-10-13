package ch.software_atelier.java_lab;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.util.*;

/** Resolves only Maven Central compile/runtime JARs, per notebook kernel. */
final class MavenDependencies {
    private final RepositorySystem system = new RepositorySystemSupplier().get();
    private final Path cache = Path.of(System.getProperty("user.home"), ".java-lab", "maven-cache");
    List<Path> resolve(List<String> coordinates) {
        if (coordinates.isEmpty()) return List.of();
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(cache.toFile())));
        session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false));
        session.setSystemProperties(System.getProperties());
        session.setDependencySelector(new AndDependencySelector(new ScopeDependencySelector("test", "provided", "system"), new OptionalDependencySelector()));
        session.setConfigProperty("aether.connector.connectTimeout", 10000);
        session.setConfigProperty("aether.connector.requestTimeout", 30000);
        session.setReadOnly();
        RemoteRepository central = new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build();
        CollectRequest request = new CollectRequest();
        request.setRepositories(List.of(central));
        for (String coordinate : coordinates) request.addDependency(new Dependency(new DefaultArtifact(coordinate), "compile"));
        try {
            DependencyNode root = system.collectDependencies(session, request).getRoot();
            if (countNodes(root, new int[]{0}) > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many transitive Maven dependencies (maximum 100)");
            var result = system.resolveDependencies(session, new DependencyRequest(root, DependencyFilterUtils.classpathFilter("compile", "runtime")));
            var artifacts = result.getArtifactResults().stream().map(r -> r.getArtifact()).filter(a -> "jar".equals(a.getExtension())).toList();
            if (artifacts.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many transitive Maven dependencies (maximum 100)");
            long bytes = artifacts.stream().mapToLong(a -> a.getFile().length()).sum();
            if (bytes > 500_000_000L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maven dependencies exceed 500 MB");
            return artifacts.stream().map(a -> a.getFile().toPath()).distinct().toList();
        } catch (DependencyResolutionException | DependencyCollectionException | IllegalArgumentException e) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not resolve Maven dependencies from Central: " + String.join(", ", coordinates));
        }
    }
    private static int countNodes(DependencyNode node, int[] count) {
        if (++count[0] > 100) return count[0];
        for (var child : node.getChildren()) if (countNodes(child, count) > 100) break;
        return count[0];
    }
}
