package com.example.demo.support;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class WorkerTlsCertificates {

    private static final String STORE_PASSWORD = "test-only-generated-store-password";
    private final Path directory;
    private final Path authorityStore;
    private final Path authorityCertificate;
    private final SSLContext clientContext;

    private WorkerTlsCertificates(Path directory, Path store, Path certificate, SSLContext clientContext) {
        this.directory = directory;
        this.authorityStore = store;
        this.authorityCertificate = certificate;
        this.clientContext = clientContext;
    }

    public static WorkerTlsCertificates create(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path store = directory.resolve("authority.p12");
        Path certificate = directory.resolve("authority.crt");
        keytool(directory, "-genkeypair", "-alias", "authority", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=TASK08 Temporary Test CA", "-startdate", "-1d", "-validity", "30",
                "-ext", "bc:critical=ca:true", "-ext", "KU=keyCertSign,cRLSign",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", STORE_PASSWORD);
        keytool(directory, "-exportcert", "-alias", "authority", "-keystore", store.toString(),
                "-storepass", STORE_PASSWORD, "-file", certificate.toString());
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("authority", certificate(certificate));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return new WorkerTlsCertificates(directory, store, certificate, context);
    }

    public Material issue(String name, String subjectAlternativeNames, boolean expired) throws Exception {
        Path store = directory.resolve(name + ".p12");
        Path request = directory.resolve(name + ".csr");
        Path certificate = directory.resolve(name + ".crt");
        keytool(directory, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=TASK08 Temporary Server", "-keystore", store.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD);
        keytool(directory, "-certreq", "-alias", "server", "-keystore", store.toString(),
                "-storepass", STORE_PASSWORD, "-file", request.toString());
        keytool(directory, "-gencert", "-alias", "authority", "-keystore", authorityStore.toString(),
                "-storepass", STORE_PASSWORD, "-infile", request.toString(), "-outfile", certificate.toString(),
                "-startdate", expired ? "-10d" : "-1d", "-validity", "2", "-ext", "SAN=" + subjectAlternativeNames,
                "-ext", "EKU=serverAuth", "-ext", "KU=digitalSignature,keyEncipherment");
        keytool(directory, "-importcert", "-noprompt", "-alias", "authority", "-keystore", store.toString(),
                "-storepass", STORE_PASSWORD, "-file", authorityCertificate.toString());
        keytool(directory, "-importcert", "-alias", "server", "-keystore", store.toString(),
                "-storepass", STORE_PASSWORD, "-file", certificate.toString());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keys.load(input, STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, STORE_PASSWORD.toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);
        return new Material(serverContext, clientContext, certificate(certificate));
    }

    private static X509Certificate certificate(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static void keytool(Path directory, String... arguments) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(keytool)) {
            throw new IllegalStateException("JDK keytool is required for real TLS tests");
        }
        ArrayList<String> command = new ArrayList<>();
        command.add(keytool.toString());
        command.addAll(Arrays.asList(arguments));
        Path output = Files.createTempFile(directory, "keytool-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test certificate generation exceeded 30 seconds");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Test certificate generation failed: " + Files.readString(output));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test certificate generator did not terminate");
                }
            }
        }
    }

    public record Material(SSLContext serverContext, SSLContext clientContext, X509Certificate certificate) {
    }
}
