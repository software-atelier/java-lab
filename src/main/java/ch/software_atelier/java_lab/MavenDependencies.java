package ch.software_atelier.java_lab;

import org.apache.maven.settings.Activation;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.StringSettingsSource;
import org.apache.maven.settings.crypto.DefaultSettingsDecrypter;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.commons.io.input.XmlStreamReader;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.transfer.ArtifactNotFoundException;
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

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves compile/runtime JARs using the user's Maven repositories, per notebook kernel. */
final class MavenDependencies {
    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern XML_ENCODING = Pattern.compile(
            "(?i)(<\\?xml\\s+[^?]*?\\bencoding\\s*=\\s*)(['\"])[^'\"]*\\2");
    private final RepositorySystem system = new RepositorySystemSupplier().get();
    private final Path userSettings;
    private final Path globalSettings;
    private final Path cache;
    private final Map<String, String> environment;

    MavenDependencies() {
        this(Path.of(System.getProperty("user.home"), ".m2", "settings.xml"), globalSettingsPath(),
                Path.of(System.getProperty("user.home"), ".java-lab", "maven-cache"));
    }

    /** Test seam: no process-wide Maven properties or credential changes are required. */
    MavenDependencies(Path userSettings, Path globalSettings, Path cache) {
        this(userSettings, globalSettings, cache, System.getenv());
    }

    MavenDependencies(Path userSettings, Path globalSettings, Path cache, Map<String, String> environment) {
        this.userSettings = userSettings;
        this.globalSettings = globalSettings;
        this.cache = cache;
        this.environment = environment;
    }

    List<Path> resolve(List<String> coordinates) {
        if (coordinates.isEmpty()) return List.of();
        Settings settings = null;
        try {
            settings = loadSettings();
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
            List<RemoteRepository> resolutionRepositories = system.newResolutionRepositories(session, repositories(settings));
            Set<String> missingEnv = missingEnvironmentVariables(settings, resolutionRepositories);
            if (!missingEnv.isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Maven settings reference unset environment variable(s): "
                                + String.join(", ", missingEnv) + ". Set them before starting Java Lab.");
            session.setReadOnly();

            CollectRequest request = new CollectRequest();
            request.setRepositories(resolutionRepositories);
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
        } catch (DependencyResolutionException | DependencyCollectionException e) {
            // Never include exception text: repository URLs and authentication details may be sensitive.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, resolutionFailure(e, settings));
        } catch (SettingsBuildingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read Maven settings. Check ~/.m2/settings.xml and its active profiles.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not resolve Maven dependencies. Check the coordinates and Maven settings.");
        }
    }

    private static Set<String> missingEnvironmentVariables(Settings settings, List<RemoteRepository> repositories) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> repositoryIds = new HashSet<>();
        for (RemoteRepository repository : repositories) {
            repositoryIds.add(repository.getId());
            findMissingEnv(repository.getUrl(), missing);
        }
        // Only credentials belonging to repositories actually used by this resolution matter.
        for (var server : settings.getServers()) {
            if (!repositoryIds.contains(server.getId())) continue;
            findMissingEnv(server.getUsername(), missing);
            findMissingEnv(server.getPassword(), missing);
            findMissingEnv(server.getPrivateKey(), missing);
            findMissingEnv(server.getPassphrase(), missing);
        }
        return missing;
    }

    private static void findMissingEnv(String value, Set<String> missing) {
        if (value == null) return;
        var matcher = ENV_REFERENCE.matcher(value);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (System.getenv(name) == null) missing.add(name);
        }
    }

    private static String resolutionFailure(Exception failure, Settings settings) {
        if (settings != null && settings.isOffline())
            return "Maven is offline. Disable offline mode in ~/.m2/settings.xml or use cached dependencies.";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        FailureSignals signals = new FailureSignals();
        inspectFailure(failure, seen, signals);
        if (signals.authentication)
            return "Maven repository authentication failed. Check the matching server ID and credentials in ~/.m2/settings.xml.";
        if (signals.network)
            return "Could not connect to a Maven repository. Check the network, proxy, and repository settings.";
        if (signals.notFound)
            return "Maven artifact not found. Check the coordinates, active repository profiles, and mirrors.";
        return "Could not resolve Maven dependencies. Check the coordinates, ~/.m2/settings.xml, and repository access.";
    }

    private static void inspectFailure(Throwable failure, Set<Throwable> seen, FailureSignals signals) {
        if (failure == null || !seen.add(failure)) return;
        if (failure instanceof ArtifactNotFoundException) signals.notFound = true;
        if (failure instanceof UnknownHostException || failure instanceof ConnectException
                || failure instanceof SocketTimeoutException) signals.network = true;
        // Resolver's HTTP transport uses HttpClient 4. Inspect the status, never its message or URL.
        if ("org.apache.http.client.HttpResponseException".equals(failure.getClass().getName())) {
            try {
                int status = (int) failure.getClass().getMethod("getStatusCode").invoke(failure);
                if (status == 401 || status == 403 || status == 407) signals.authentication = true;
            } catch (ReflectiveOperationException ignored) {
                // Unknown transport implementation: retain the generic, redacted diagnostic.
            }
        }
        if (failure instanceof DependencyResolutionException resolution && resolution.getResult() != null) {
            for (Exception cause : resolution.getResult().getCollectExceptions()) inspectFailure(cause, seen, signals);
            for (var artifact : resolution.getResult().getArtifactResults())
                for (Exception cause : artifact.getExceptions()) inspectFailure(cause, seen, signals);
        }
        if (failure instanceof DependencyCollectionException collection && collection.getResult() != null)
            for (Exception cause : collection.getResult().getExceptions()) inspectFailure(cause, seen, signals);
        for (Throwable suppressed : failure.getSuppressed()) inspectFailure(suppressed, seen, signals);
        inspectFailure(failure.getCause(), seen, signals);
    }

    private static final class FailureSignals {
        private boolean authentication;
        private boolean network;
        private boolean notFound;
    }

    private Settings loadSettings() throws SettingsBuildingException {
        var request = new DefaultSettingsBuildingRequest();
        configureSettingsSource(request, userSettings, true);
        configureSettingsSource(request, globalSettings, false);
        request.setSystemProperties(System.getProperties());
        return new DefaultSettingsBuilderFactory().newInstance().build(request).getEffectiveSettings();
    }

    private void configureSettingsSource(DefaultSettingsBuildingRequest request, Path path, boolean user) {
        if (path == null) return;
        if (!Files.isRegularFile(path)) {
            if (user) request.setUserSettingsFile(path.toFile());
            else request.setGlobalSettingsFile(path.toFile());
            return;
        }
        try {
            // Maven's settings parser converts <port> to int before its own interpolation.
            // Resolve values in the XML source first, escaping replacements as XML text.
            String xml;
            try (var reader = new XmlStreamReader(path)) {
                var writer = new java.io.StringWriter();
                reader.transferTo(writer);
                // StringSettingsSource serializes its content as UTF-8 bytes, so its XML
                // declaration must describe that encoding after the original file is decoded.
                xml = XML_ENCODING.matcher(interpolateSettingsXml(writer.toString(), environment,
                        System.getProperties())).replaceFirst("$1$2UTF-8$2");
            }
            var source = new StringSettingsSource(xml, path.toString());
            if (user) request.setUserSettingsSource(source);
            else request.setGlobalSettingsSource(source);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read Maven settings. Check ~/.m2/settings.xml and its active profiles.");
        }
    }

    static String interpolateSettingsXml(String xml, Map<String, String> environment,
                                          java.util.Properties systemProperties) {
        Matcher matcher = PROPERTY_REFERENCE.matcher(xml);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = key.startsWith("env.") ? environment.get(key.substring(4))
                    : systemProperties.getProperty(key);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    value == null ? matcher.group() : xmlEscape(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private void configureSelectors(DefaultRepositorySystemSession session, Settings settings) {
        var mirrors = new DefaultMirrorSelector();
        for (var mirror : settings.getMirrors())
            mirrors.add(mirror.getId(), mirror.getUrl(), mirror.getLayout(), false, mirror.isBlocked(),
                    mirror.getMirrorOf(), mirror.getMirrorOfLayouts());
        session.setMirrorSelector(mirrors);

        // A security file is only needed for encrypted credentials. Maven's decrypter
        // reports a missing file as an error even when no decryption is required.
        var servers = settings.getServers();
        var configuredProxies = settings.getProxies();
        boolean encrypted = servers.stream().anyMatch(server -> isEncrypted(server.getPassword())
                        || isEncrypted(server.getPassphrase()))
                || configuredProxies.stream().anyMatch(proxy -> isEncrypted(proxy.getPassword()));
        if (encrypted) {
            Path security = userSettings == null
                    ? Path.of(System.getProperty("user.home"), ".m2", "settings-security.xml")
                    : userSettings.resolveSibling("settings-security.xml");
            var decrypted = new DefaultSettingsDecrypter(
                    new DefaultSecDispatcher(new DefaultPlexusCipher(), java.util.Map.of(), security.toString()))
                    .decrypt(new DefaultSettingsDecryptionRequest(settings));
            if (!decrypted.getProblems().isEmpty())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not decrypt Maven settings credentials");
            servers = decrypted.getServers();
            configuredProxies = decrypted.getProxies();
        }
        var auth = new DefaultAuthenticationSelector();
        for (var server : servers) {
            var builder = new AuthenticationBuilder();
            if (server.getUsername() != null) builder.addUsername(server.getUsername());
            if (server.getPassword() != null) builder.addPassword(server.getPassword());
            if (server.getPrivateKey() != null) builder.addPrivateKey(server.getPrivateKey(), server.getPassphrase());
            auth.add(server.getId(), builder.build());
        }
        session.setAuthenticationSelector(auth);
        var proxies = new DefaultProxySelector();
        for (var proxy : configuredProxies) {
            if (!proxy.isActive()) continue;
            if (proxy.getPort() < 1 || proxy.getPort() > 65535
                    || unresolved(proxy.getHost()) || unresolved(proxy.getProtocol())
                    || unresolved(proxy.getNonProxyHosts()) || unresolved(proxy.getUsername())
                    || unresolved(proxy.getPassword()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid active Maven proxy settings: check host, protocol and port (1-65535), "
                                + "and resolve any settings placeholders.");
            var builder = new AuthenticationBuilder();
            if (proxy.getUsername() != null) builder.addUsername(proxy.getUsername());
            if (proxy.getPassword() != null) builder.addPassword(proxy.getPassword());
            proxies.add(new org.eclipse.aether.repository.Proxy(proxy.getProtocol(), proxy.getHost(),
                    proxy.getPort(), builder.build()), proxy.getNonProxyHosts());
        }
        session.setProxySelector(proxies);
    }

    private static boolean unresolved(String value) {
        return value != null && PROPERTY_REFERENCE.matcher(value).find();
    }

    private static boolean isEncrypted(String value) {
        return value != null && value.startsWith("{") && value.endsWith("}");
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
