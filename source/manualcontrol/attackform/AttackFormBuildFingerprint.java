package manualcontrol.attackform;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class AttackFormBuildFingerprint {
    private static final String[] TARGETS = {
            "common/battle/data/CustomEntity", "common/pack/PackData$UserPack",
            "common/pack/Source$Workspace", "common/pack/UserProfile",
            "common/util/anim/AnimCE", "common/pack/Source$SourceAnimSaver",
            "page/anim/MaAnimEditPage", "page/info/edit/EntityEditPage",
            "page/info/edit/FormEditPage", "page/info/edit/EnemyEditPage",
            "common/battle/entity/Entity", "common/battle/entity/Entity$AnimManager",
            "common/battle/entity/Entity$AtkManager", "common/battle/attack/AtkModelEntity",
            "common/util/anim/EAnimD"
    };

    static String read(ClassLoader loader) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (String target : TARGETS) {
                String name = target + ".class";
                digest.update((name + "\n").getBytes(StandardCharsets.UTF_8));
                InputStream input = loader.getResourceAsStream(name);
                if (input == null) return "missing";
                try {
                    for (int count; (count = input.read(buffer)) >= 0; )
                        if (count > 0) digest.update(buffer, 0, count);
                } finally { input.close(); }
            }
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest.digest()) out.append(String.format("%02x", value & 255));
            return out.toString();
        } catch (Throwable failure) {
            return "unavailable";
        }
    }

    static boolean verified(int version, String digest) {
        return (version == 50808 && "2a993f0da2ebd1a809d98b45160d8fe1c3ba8aab24729ebcafa802dc68d1f67a".equals(digest))
                || (version == 50302 && "685677a196e5e98819a12613db31ba584b0449c728d1aae7ecd099526055bcdd".equals(digest));
    }

    private AttackFormBuildFingerprint() {}
}
