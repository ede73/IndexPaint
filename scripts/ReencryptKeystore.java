import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Enumeration;

/** Re-encrypts one signing-key entry in a copied keystore without exposing passwords in argv. */
public final class ReencryptKeystore {
    private ReencryptKeystore() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected source, destination, and alias.");
        String type = System.getenv().getOrDefault("INDEXPAINT_KEYSTORE_TYPE", "JKS");
        char[] oldStorePassword = required("OLD_STORE_PASSWORD").toCharArray();
        char[] oldKeyPassword = required("OLD_KEY_PASSWORD").toCharArray();
        char[] newStorePassword = required("NEW_STORE_PASSWORD").toCharArray();
        char[] newKeyPassword = required("NEW_KEY_PASSWORD").toCharArray();

        KeyStore store = KeyStore.getInstance(type);
        try (FileInputStream input = new FileInputStream(args[0])) {
            store.load(input, oldStorePassword);
        }
        String alias = args[2];
        if (!store.containsAlias(alias) || !store.isKeyEntry(alias)) {
            throw new IllegalArgumentException("Alias '" + alias + "' is not a key entry in the source keystore.");
        }
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String entryAlias = aliases.nextElement();
            if (!entryAlias.equals(alias) && store.isKeyEntry(entryAlias)) {
                throw new IllegalArgumentException("Keystore has another private-key entry ('" + entryAlias + "'); refusing to change only one entry.");
            }
        }

        Key key = store.getKey(alias, oldKeyPassword);
        if (key == null || key.getEncoded() == null) {
            throw new IllegalArgumentException("Alias '" + alias + "' does not contain an exportable private key.");
        }
        Certificate[] chain = store.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            throw new IllegalArgumentException("Alias '" + alias + "' has no certificate chain.");
        }
        byte[] originalCertificate = chain[0].getEncoded();
        store.setKeyEntry(alias, key, newKeyPassword, chain);
        try (FileOutputStream output = new FileOutputStream(args[1])) {
            store.store(output, newStorePassword);
        }

        KeyStore verify = KeyStore.getInstance(type);
        try (FileInputStream input = new FileInputStream(args[1])) {
            verify.load(input, newStorePassword);
        }
        Key verifiedKey = verify.getKey(alias, newKeyPassword);
        if (verifiedKey == null || !MessageDigest.isEqual(key.getEncoded(), verifiedKey.getEncoded())) {
            throw new IllegalStateException("Copied keystore private-key verification failed.");
        }
        if (!MessageDigest.isEqual(originalCertificate, verify.getCertificate(alias).getEncoded())) {
            throw new IllegalStateException("Copied keystore certificate differs from source.");
        }
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(originalCertificate);
        StringBuilder fingerprint = new StringBuilder();
        for (byte value : digest) {
            if (fingerprint.length() > 0) fingerprint.append(':');
            fingerprint.append(String.format("%02X", value));
        }
        System.out.println(fingerprint);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing environment value " + name + ".");
        return value;
    }
}
