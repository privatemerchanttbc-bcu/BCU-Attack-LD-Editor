package manualcontrol.attackform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;

public final class BcuBuildInfo {

    private static final String ANCHOR = "main/MainBCU.class";

    public final String source;
    public final String anchorSha256;
    public final int versionCode;

    private BcuBuildInfo(String source, String anchorSha256, int versionCode) {
        this.source = source;
        this.anchorSha256 = anchorSha256;
        this.versionCode = versionCode;
    }

    public static BcuBuildInfo detect(ClassLoader loader) {
        if (loader == null) loader = ClassLoader.getSystemClassLoader();
        URL url = loader == null ? ClassLoader.getSystemResource(ANCHOR) : loader.getResource(ANCHOR);
        if (url == null) return new BcuBuildInfo("unknown", "missing", -1);

        String source = compactSource(url.toExternalForm());
        InputStream in = null;
        try {
            in = url.openStream();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    bytes.write(buffer, 0, read);
                }
            }
            int version = readVersionConstant(bytes.toByteArray());
            return new BcuBuildInfo(source, hex(digest.digest()).substring(0, 16), version);
        } catch (Throwable error) {
            return new BcuBuildInfo(source, "unavailable", -1);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    public String displayName() {
        return source + " ver=" + (versionCode < 0 ? "unknown" : versionCode)
                + " anchorSha256=" + anchorSha256;
    }

    private static int readVersionConstant(byte[] classBytes) {
        final int[] version = {-1};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if ("ver".equals(name) && "I".equals(descriptor) && value instanceof Integer)
                    version[0] = ((Integer) value).intValue();
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return version[0];
    }

    private static String compactSource(String external) {
        int bang = external.indexOf("!/");
        String value = bang >= 0 ? external.substring(0, bang) : external;
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
