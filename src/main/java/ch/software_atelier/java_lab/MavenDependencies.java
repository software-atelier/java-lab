package ch.software_atelier.java_lab;

import org.apache.maven.settings.Activation;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.crypto.DefaultSettingsDecrypter;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.selector.ScopeDependencySelector;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Resolves compile/runtime JARs using the user's Maven repositories, per notebook kernel. */
final class MavenDependencies {
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private final RepositorySystem system = new RepositorySystemSupplier().get();
    private final Path userSettings;
    private final Path globalSettings;
    private final Path cache;

    MavenDependencies() {
        this(Path.of(System.getProperty("user.home"), ".m2", "settings.xml"), globalSettingsPath(),
                Path.of(System.getProperty("user.home"), ".java-lab", "maven-cache"));
    }

    /** Test seam: no process-wide Maven properties or credential changes are required. */
    MavenDependencies(Path userSettings, Path globalSettings, Path cache) {
        this.userSettings = userSettings;
        this.globalSettings = globalSettings;
        this.cache = cache;
    }

    List<Path> resolve(List<String> coordinates) {
        if (coordinates.isEmpty()) return List.of();
        try {
            Settings settings = loadSettings();
            DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
            Path local = settings.getLocalRepository() == null || settings.getLocalRepository().isBlank()
                    ? cache : Path.of(settings.getLocalRepository());
            session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(local.toFile())));
            session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false));
            session.setSystemProperties(System.getProperties());
            session.setOffline(settings.isOffline());
            session.setDependencySelector(new AndDependencySelector(
                    new ScopeDependencySelector("test", "provided", "system"), new OptionalDependencySelector()));
            session.setConfigProperty("aether.connector.connectTimeout", 10000);
            session.setConfigProperty("aether.connector.requestTimeout", 30000);
            configureSelectors(session, settings);
            session.setReadOnly();

            CollectRequest request = new CollectRequest();
            request.setRepositories(system.newResolutionRepositories(session, repositories(settings)));
            for (String coordinate : coordinates)
                request.addDependency(new Dependency(new DefaultArtifact(coordinate), "compile"));
            DependencyNode root = system.collectDependencies(session, request).getRoot();
            if (countNodes(root, new int[]{0}) > 100)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many transitive Maven dependencies (maximum 100)");
            var result = system.resolveDependencies(session,
                    new DependencyRequest(root, DependencyFilterUtils.classpathFilter("compile", "runtime")));
            var artifacts = result.getArtifactResults().stream().map(r -> r.getArtifact())
                    .filter(a -> "jar".equals(a.getExtension())).toList();
            if (artifacts.size() > 100)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many transitive Maven dependencies (maximum 100)");
            long bytes = artifacts.stream().mapToLong(a -> a.getFile().length()).sum();
            if (bytes > 500_000_000L)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maven dependencies exceed 500 MB");
            return artifacts.stream().map(a -> a.getFile().toPath()).distinct().toList();
        } catch (DependencyResolutionException | DependencyCollectionException | SettingsBuildingException
                 | IllegalArgumentException e) {
            // Never include exception text: repository URLs and authentication details may be sensitive.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not resolve Maven dependencies");
        }
    }

    private Settings loadSettings() throws SettingsBuildingException {
        var request = new DefaultSettingsBuildingRequest();
        if (userSettings != null) request.setUserSettingsFile(userSettings.toFile());
        if (globalSettings != null) request.setGlobalSettingsFile(globalSettings.toFile());
        request.setSystemProperties(System.getProperties());
        return new DefaultSettingsBuilderFactory().newInstance().build(request).getEffectiveSettings();
    }

    private void configureSelectors(DefaultRepositorySystemSession session, Settings settings) {
        var mirrors = new DefaultMirrorSelector();
        for (var mirror : settings.getMirrors())
            mirrors.add(mirror.getId(), mirror.getUrl(), mirror.getLayout(), false, mirror.isBlocked(),
                    mirror.getMirrorOf(), mirror.getMirrorOfLayouts());
        session.setMirrorSelector(mirrors);

        // Maven's own decrypter supports plain and settings-security.xml encrypted passwords.
        Path security = userSettings == null
                ? Path.of(System.getProperty("user.home"), ".m2", "settings-security.xml")
                : userSettings.resolveSibling("settings-security.xml");
        var decrypted = new DefaultSettingsDecrypter(
                new DefaultSecDispatcher(new DefaultPlexusCipher(), java.util.Map.of(), security.toString()))
                .decrypt(new DefaultSettingsDecryptionRequest(settings));
        if (!decrypted.getProblems().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not decrypt Maven settings credentials");
        var auth = new DefaultAuthenticationSelector();
        for (var server : decrypted.getServers()) {
            var builder = new AuthenticationBuilder();
            if (server.getUsername() != null) builder.addUsername(server.getUsername());
            if (server.getPassword() != null) builder.addPassword(server.getPassword());
            if (server.getPrivateKey() != null) builder.addPrivateKey(server.getPrivateKey(), server.getPassphrase());
            auth.add(server.getId(), builder.build());
        }
        session.setAuthenticationSelector(auth);
        var proxies = new DefaultProxySelector();
        for (var proxy : decrypted.getProxies()) {
            if (!proxy.isActive()) continue;
            var builder = new AuthenticationBuilder();
            if (proxy.getUsername() != null) builder.addUsername(proxy.getUsername());
            if (proxy.getPassword() != null) builder.addPassword(proxy.getPassword());
            proxies.add(new org.eclipse.aether.repository.Proxy(proxy.getProtocol(), proxy.getHost(),
                    proxy.getPort(), builder.build()), proxy.getNonProxyHosts());
        }
        session.setProxySelector(proxies);
    }

    private static List<RemoteRepository> repositories(Settings settings) {
        var result = new LinkedHashMap<String, RemoteRepository>();
        Set<String> explicit = new HashSet<>(settings.getActiveProfiles());
        boolean otherwiseActive = settings.getProfiles().stream().anyMatch(
                p -> explicit.contains(p.getId()) || activatedByProperty(p.getActivation()));
        for (Profile profile : settings.getProfiles()) {
            Activation activation = profile.getActivation();
            boolean active = explicit.contains(profile.getId()) || activatedByProperty(activation)
                    || (!otherwiseActive && activation != null && activation.isActiveByDefault());
            if (!active) continue;
            for (var repository : profile.getRepositories()) {
                if (repository.getId() == null || repository.getUrl() == null) continue;
                var builder = new RemoteRepository.Builder(repository.getId(), repository.getLayout(), repository.getUrl());
                builder.setReleasePolicy(policy(repository.getReleases()));
                builder.setSnapshotPolicy(policy(repository.getSnapshots()));
                result.putIfAbsent(repository.getId(), builder.build());
            }
        }
        result.putIfAbsent("central", new RemoteRepository.Builder("central", "default", CENTRAL).build());
        return new ArrayList<>(result.values());
    }

    private static boolean activatedByProperty(Activation activation) {
        if (activation == null || activation.getProperty() == null) return false;
        var property = activation.getProperty();
        String name = property.getName();
        if (name == null || name.isBlank()) return false;
        boolean negateName = name.startsWith("!");
        if (negateName) name = name.substring(1);
        String actual = name.startsWith("env.") ? System.getenv(name.substring(4)) : System.getProperty(name);
        boolean present = actual != null;
        if (negateName) present = !present;
        String expected = property.getValue();
        if (expected == null) return present;
        return present && (expected.startsWith("!") ? !expected.substring(1).equals(actual) : expected.equals(actual));
    }

    private static org.eclipse.aether.repository.RepositoryPolicy policy(org.apache.maven.settings.RepositoryPolicy source) {
        if (source == null) return new org.eclipse.aether.repository.RepositoryPolicy();
        return new org.eclipse.aether.repository.RepositoryPolicy(source.isEnabled(),
                source.getUpdatePolicy(), source.getChecksumPolicy());
    }

    private static Path globalSettingsPath() {
        String home = System.getProperty("maven.home");
        if (home == null || home.isBlank()) home = System.getenv("MAVEN_HOME");
        if (home == null || home.isBlank()) home = System.getenv("M2_HOME");
        return home == null || home.isBlank() ? null : Path.of(home, "conf", "settings.xml");
    }

    private static int countNodes(DependencyNode node, int[] count) {
        if (++count[0] > 100) return count[0];
        for (var child : node.getChildren()) if (countNodes(child, count) > 100) break;
        return count[0];
    }

}
