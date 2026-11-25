package com.xlox.tests.apk;

import com.android.apksig.ApkVerifier;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/** Host-only release gate using the SDK's structured verifier, not its display text. */
public final class VerifyOriginalSigner {
    private VerifyOriginalSigner() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("original-certificate.der APK...");
        byte[] original = Files.readAllBytes(Paths.get(args[0]));
        String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(original));
        for (int i = 1; i < args.length; i++) {
            File apk = new File(args[i]);
            ApkVerifier.Result result = new ApkVerifier.Builder(apk).build().verify();
            if (!result.isVerified()) {
                fail(apk + ": cryptographic signature verification failed: " + result.getErrors());
            }
            List<X509Certificate> signers = result.getSignerCertificates();
            if (signers.isEmpty()) fail(apk + ": no verified signer certificate");
            for (X509Certificate signer : signers) {
                if (!Arrays.equals(original, signer.getEncoded())) {
                    fail(apk + ": signer is not the original keystore certificate; actual SHA-256 "
                            + hex(MessageDigest.getInstance("SHA-256").digest(signer.getEncoded())));
                }
            }
            System.out.println("Verified original signer: " + apk + " SHA-256 " + fingerprint);
            System.out.println("::notice title=Original APK signer::" + apk + " SHA-256 " + fingerprint);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void fail(String detail) {
        System.out.println("::error title=APK original-signer verification::" + detail
                .replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A"));
        throw new AssertionError(detail);
    }
}
