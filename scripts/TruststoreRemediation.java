import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

/** Offline, standard-cacerts-only remediation; no network access or extra libraries. */
public final class TruststoreRemediation {
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final List<String> COMMON_NAMES = List.of(
            "emSign ECC Root CA - G3", "AAA Certificate Services",
            "emSign Root CA - G1", "emSign Root CA - G2");

    private TruststoreRemediation() { }

    public static void main(String[] arguments) {
        try {
            System.exit(run(arguments));
        } catch (Exception exception) {
            System.err.println("ERROR: " + exception.getMessage());
            System.exit(2);
        }
    }

    private static int run(String[] arguments) throws Exception {
        boolean check = arguments.length == 2 && "--check".equals(arguments[0]);
        boolean apply = arguments.length == 3 && "--apply".equals(arguments[0]);
        if (!check && !apply) {
            throw new IllegalArgumentException("Use --check <store> or --apply <store> <backup>");
        }
        Path source = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Store must be a regular file, not a symbolic link");
        }
        source = source.toRealPath();
        KeyStore store = KeyStore.getInstance(source.toFile(), PASSWORD);
        List<String> targets = new ArrayList<>();
        var aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            // This utility is for trust anchors, never private or secret key material.
            if (store.isKeyEntry(alias)) {
                throw new IllegalArgumentException("Key entries are not allowed in a truststore");
            }
            if (store.getCertificate(alias) instanceof X509Certificate certificate && targeted(certificate)) {
                targets.add(alias);
            }
        }
        if (check) {
            audit(store, targets);
            return targets.isEmpty() ? 0 : 1;
        }

        Path requestedBackup = Path.of(arguments[2]).toAbsolutePath().normalize();
        Path backup = requestedBackup.getParent().toRealPath().resolve(requestedBackup.getFileName());
        if (source.equals(backup) || (Files.exists(backup) && Files.isSameFile(source, backup))) {
            throw new IllegalArgumentException("Backup must be different from the original store");
        }
        if (targets.isEmpty()) {
            audit(store, targets);
            return 0; // A clean store and any existing original backup remain byte-for-byte unchanged.
        }
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Backup already exists; refusing to overwrite it");
        }
        // No REPLACE_EXISTING: an existing or concurrently-created backup is never overwritten.
        Files.copy(source, backup, StandardCopyOption.COPY_ATTRIBUTES);
        List<String> fingerprints = fingerprints(store, targets);
        for (String alias : targets) {
            store.deleteEntry(alias);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.store(bytes, PASSWORD);
        try {
            // Updating the existing inode preserves its POSIX permissions and Windows ACL/owner.
            Files.write(source, bytes.toByteArray(), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception failure) {
            try {
                Files.write(source, Files.readAllBytes(backup), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception restoreFailure) {
                failure.addSuppressed(restoreFailure);
                throw new IllegalStateException("Write and restoration failed; preserve the original backup", failure);
            }
            throw failure;
        }
        System.out.println("targets=" + targets.size());
        fingerprints.forEach(value -> System.out.println("sha256=" + value));
        return 0;
    }

    private static boolean targeted(X509Certificate certificate) throws Exception {
        LdapName subject = new LdapName(certificate.getSubjectX500Principal().getName());
        for (Rdn rdn : subject.getRdns()) {
            var attributes = rdn.toAttributes().getAll();
            while (attributes.hasMore()) {
                var attribute = attributes.next();
                if ("CN".equalsIgnoreCase(attribute.getID())) {
                    var values = attribute.getAll();
                    while (values.hasMore()) {
                        Object value = values.next();
                        for (String commonName : COMMON_NAMES) {
                            if (commonName.equals(value)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void audit(KeyStore store, List<String> targets) throws Exception {
        System.out.println("targets=" + targets.size());
        fingerprints(store, targets).forEach(value -> System.out.println("sha256=" + value));
    }

    private static List<String> fingerprints(KeyStore store, List<String> targets) throws Exception {
        List<String> result = new ArrayList<>();
        for (String alias : targets) {
            result.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(store.getCertificate(alias).getEncoded())));
        }
        return result;
    }
}
