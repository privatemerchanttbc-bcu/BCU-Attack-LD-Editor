package manualcontrol.attackform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AttackFormCompatibility {

    public static final String PROFILE = "classic-bcu-attack-pipeline-v3";
    private static final int[] VERIFIED_BCU_VERSIONS = {50302, 50808};

    private AttackFormCompatibility() {}

    public static Report probe(ClassLoader loader) {
        if (loader == null) loader = ClassLoader.getSystemClassLoader();
        BcuBuildInfo build = BcuBuildInfo.detect(loader);
        LinkedHashMap<String, GroupResult> groups = new LinkedHashMap<String, GroupResult>();

        groups.put("animator", check(loader, "animator",
                contract("page/anim/MaAnimEditPage")
                        .method("<init>", "(Lpage/Page;)V")
                        .method("<init>", "(Lpage/Page;Lpage/anim/EditHead;)V")
                        .method("ini", "()V")
                        .method("resized", "(II)V")
                        .method("callBack", "(Ljava/lang/Object;)V")
                        .method("setA", "(Lcommon/util/anim/AnimCE;)V")
                        .method("setB", "(Lcommon/util/anim/AnimCE;I)V")
                        .field("jlt", "Ljavax/swing/JList;")
                        .field("jspt", "Ljavax/swing/JScrollPane;")
                        .field("maet", "Lpage/anim/MaAnimEditTable;")
                        .field("advs", "Lpage/JBTN;")
                        .field("sort", "Lpage/JBTN;"),
                contract("common/util/anim/AnimU")
                        .method("getAtkLen", "()I")
                        .method("getEAnim", "(Lcommon/util/anim/AnimU$UType;)Lcommon/util/anim/EAnimU;")
                        .method("partial", "()V"),
                contract("common/util/anim/AnimCE")
                        .method("<init>", "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimD;)V")
                        .method("load", "()V")
                        .method("unSave", "(Ljava/lang/String;)V")
                        .method("restore", "()V")
                        .field("saved", "Z"),
                contract("common/util/anim/MaAnim")
                        .field("n", "I").field("parts", "[Lcommon/util/anim/Part;")
                        .method("<init>", "(Ljava/util/Queue;Z)V")
                        .method("clone", "()Lcommon/util/anim/MaAnim;")
                        .method("write", "(Ljava/io/PrintStream;)V"),
                contract("common/pack/Source$SourceAnimSaver")
                        .field("id", "Lcommon/pack/Source$ResourceLocation;")
                        .field("anim", "Lcommon/util/anim/AnimCI;")
                        .method("saveData", "()V"),
                contract("common/pack/Source$SourceAnimLoader")
                        .field("loader", "Lcommon/pack/Source$SourceLoader;"),
                contract("common/pack/Source$SourceLoader")
                        .method("loadFile", "(Lcommon/pack/Source$BasePath;Lcommon/pack/Source$ResourceLocation;Ljava/lang/String;)Lcommon/system/files/FileData;"),
                contract("common/system/files/FileData")
                        .method("getBytes", "()[B")
                        .method("readLine", "()Ljava/util/Queue;")
                        .method("size", "()I"),
                contract("page/Page")
                        .field("adjusting", "I")
                        .method("set", "(Ljava/awt/Component;IIIIII)V")));

        groups.put("editors", check(loader, "editors",
                contract("page/info/edit/EntityEditPage")
                        .field("ce", "Lcommon/battle/data/CustomEntity;")
                        .field("editable", "Z")
                        .method("setData", "(Lcommon/battle/data/CustomEntity;)V")
                        .method("renew", "()V"),
                contract("page/info/edit/FormEditPage")
                        .method("<init>", "(Lpage/Page;Lcommon/pack/PackData$UserPack;Lcommon/util/unit/Form;)V")
                        .method("ini", "()V")
                        .method("resized", "(II)V")
                        .method("setData", "(Lcommon/battle/data/CustomEntity;)V"),
                contract("page/info/edit/EnemyEditPage")
                        .method("<init>", "(Lpage/Page;Lcommon/util/unit/Enemy;Lcommon/pack/PackData$UserPack;)V")
                        .method("ini", "()V")
                        .method("resized", "(II)V")
                        .method("setData", "(Lcommon/battle/data/CustomEntity;)V"),
                contract("page/Page")
                        .method("leave", "()V")
                        .method("exit", "()V")));

        groups.put("data", check(loader, "data",
                contract("common/battle/data/CustomEntity")
                        .field("atks", "[Lcommon/battle/data/AtkDataModel;")
                        .field("tba", "I")
                        .field("base", "I")
                        .field("touch", "I")
                        .field("common", "Z")
                        .field("rep", "Lcommon/battle/data/AtkDataModel;")
                        .method("getAtkModel", "(I)Lcommon/battle/data/MaskAtk;")
                         .method("rawAtkData", "()[[I")
                         .method("getTBA", "()I")
                         .method("updateAllProc", "()V")
                         .method("importData", "(Lcommon/battle/data/MaskEntity;)V")
                         .method("onInjected", "()V"),
                contract("common/battle/data/DataEntity")
                        .field("range", "I").field("abi", "I").field("loop", "I"),
                contract("common/battle/data/AtkDataModel")
                        .field("str", "Ljava/lang/String;")
                        .field("atk", "I").field("pre", "I")
                        .field("ld0", "I").field("ld1", "I")
                        .field("targ", "I").field("count", "I")
                        .field("dire", "I").field("alt", "I").field("move", "I")
                         .field("range", "Z").field("specialTrait", "Z")
                         .field("proc", "Lcommon/util/Data$Proc;")
                         .method("<init>", "(Lcommon/battle/data/CustomEntity;)V")
                         .method("clone", "()Lcommon/battle/data/AtkDataModel;"),
                contract("common/util/Data$Proc")
                        .method("blank", "()Lcommon/util/Data$Proc;")
                        .method("clone", "()Lcommon/util/Data$Proc;"),
                contract("common/pack/PackData$UserPack")
                        .field("source", "Lcommon/pack/Source;")
                        .method("load", "()V").method("getSID", "()Ljava/lang/String;"),
                contract("common/pack/Source$Workspace")
                        .method("save", "(Lcommon/pack/PackData$UserPack;Z)V"),
                contract("common/pack/UserProfile")
                        .method("readBackupPack", "(Ljava/lang/String;Ljava/lang/String;)Lcommon/pack/PackData$UserPack;"),
                contract("common/pack/Source")
                        .field("id", "Ljava/lang/String;")
                        .method("listFile", "(Ljava/lang/String;)[Ljava/lang/String;")
                        .method("streamFile", "(Ljava/lang/String;)Ljava/io/InputStream;"),
                contract("common/pack/PackData")
                        .field("units", "Lcommon/pack/FixIndexList$FixIndexMap;")
                        .field("enemies", "Lcommon/pack/FixIndexList$FixIndexMap;"),
                contract("common/util/unit/Unit")
                        .field("id", "Lcommon/pack/Identifier;")
                        .field("forms", "[Lcommon/util/unit/Form;"),
                contract("common/util/unit/Form")
                        .field("du", "Lcommon/battle/data/MaskUnit;").field("fid", "I"),
                contract("common/util/unit/Enemy")
                        .field("id", "Lcommon/pack/Identifier;")
                        .field("de", "Lcommon/battle/data/MaskEnemy;"),
                contract("common/util/Animable")
                        .field("anim", "Lcommon/util/anim/AnimI;"),
                contract("common/util/anim/AnimCI")
                        .field("id", "Lcommon/pack/Source$ResourceLocation;"),
                contract("common/pack/Source$ResourceLocation")
                        .field("pack", "Ljava/lang/String;")
                        .field("id", "Ljava/lang/String;")
                        .field("base", "Lcommon/pack/Source$BasePath;")));

        Contract runtimeEntity = contract("common/battle/entity/Entity")
                .field("anim", "Lcommon/battle/entity/Entity$AnimManager;")
                .field("atkm", "Lcommon/battle/entity/Entity$AtkManager;")
                .field("basis", "Lcommon/battle/StageBasis;")
                .field("data", "Lcommon/battle/data/MaskEntity;")
                .field("aam", "Lcommon/battle/attack/AtkModelEntity;")
                .method("checkTouch", "()Z");
        if (build.versionCode == 50808) {
            runtimeEntity
                    .method("<init>", "(Lcommon/battle/StageBasis;Lcommon/battle/data/MaskEnemy;Lcommon/util/anim/EAnimU;FF)V")
                    .method("<init>", "(Lcommon/battle/StageBasis;Lcommon/battle/data/MaskUnit;Lcommon/util/anim/EAnimU;FFFLcommon/battle/data/PCoin;Lcommon/util/unit/Level;)V");
        } else if (build.versionCode == 50302) {
            runtimeEntity
                    .method("<init>", "(Lcommon/battle/StageBasis;Lcommon/battle/data/MaskEntity;Lcommon/util/anim/EAnimU;FF)V")
                    .method("<init>", "(Lcommon/battle/StageBasis;Lcommon/battle/data/MaskEntity;Lcommon/util/anim/EAnimU;FFFLcommon/battle/data/PCoin;Lcommon/util/unit/Level;)V");
        }

        groups.put("runtime", check(loader, "runtime",
                runtimeEntity,
                contract("common/battle/entity/Entity$AnimManager")
                        .field("e", "Lcommon/battle/entity/Entity;")
                        .field("anim", "Lcommon/util/anim/EAnimU;")
                        .method("setAnim", "(Lcommon/util/anim/AnimU$UType;Z)I"),
                contract("common/battle/entity/Entity$AtkManager")
                        .field("atkTime", "I").field("preTime", "I")
                        .field("attacksLeft", "I").field("tempAtk", "I")
                        .field("e", "Lcommon/battle/entity/Entity;")
                        .field("multi", "I").field("preID", "I").field("pres", "[I")
                        .method("startAttack", "()V")
                        .method("stopAtk", "()V")
                        .method("updateAttack", "()V"),
                contract("common/battle/attack/AtkModelEntity")
                        .field("e", "Lcommon/battle/entity/Entity;")
                        .method("getEnemyAtk", "(Lcommon/battle/entity/Entity;F)Lcommon/battle/attack/AtkModelEntity;")
                        .method("getUnitAtk", "(Lcommon/battle/entity/Entity;FFLcommon/battle/data/PCoin;Lcommon/util/unit/Level;)Lcommon/battle/attack/AtkModelEntity;")
                        .method("getAttack", "(I)Lcommon/battle/attack/AttackAb;")
                        .method("inRange", "(I)[F")
                        .method("extraAtk", "(I)V"),
                contract("common/battle/data/CustomUnit")
                        .method("clone", "()Lcommon/battle/data/CustomUnit;"),
                contract("common/battle/data/CustomEnemy")
                        .method("copy", "(Lcommon/util/unit/Enemy;)Lcommon/battle/data/CustomEnemy;"),
                contract("common/util/anim/EAnimD")
                        .field("ma", "Lcommon/util/anim/MaAnim;")
                        .field("f", "F")
                        .method("changeAnim", "(Ljava/lang/Enum;Z)V")
                        .method("setTime", "(F)V"),
                contract("common/util/BattleObj")
                        .method("clone", "()Lcommon/util/BattleObj;")
                        .method("performDeepCopy", "()V"),
                contract("common/util/CopRand")
                        .method("nextFloat", "()F")));

        boolean compatible = true;
        ArrayList<String> missing = new ArrayList<String>();
        if (!isVerifiedVersion(build.versionCode)) {
            compatible = false;
            missing.add("unsupported BCU ver=" + build.versionCode
                    + " (verified=" + verifiedVersions() + ")");
        }
        for (GroupResult group : groups.values()) {
            if (!group.compatible) {
                compatible = false;
                missing.addAll(group.missing);
            }
        }
        String fingerprint = AttackFormBuildFingerprint.read(loader);
        boolean fingerprintVerified = AttackFormBuildFingerprint.verified(build.versionCode, fingerprint);
        if (!fingerprintVerified && !AttackFormBuildFingerprint.readable(fingerprint)) {
            compatible = false;
            missing.add("unreadable patch-target fingerprint=" + fingerprint);
        }
        return new Report(PROFILE, build, compatible, groups, missing, fingerprint,
                fingerprintVerified);
    }

    private static boolean isVerifiedVersion(int version) {
        for (int verified : VERIFIED_BCU_VERSIONS)
            if (verified == version) return true;
        return false;
    }

    private static String verifiedVersions() {
        StringBuilder out = new StringBuilder();
        for (int version : VERIFIED_BCU_VERSIONS) {
            if (out.length() > 0) out.append(',');
            out.append(version);
        }
        return out.toString();
    }

    private static GroupResult check(ClassLoader loader, String name, Contract... contracts) {
        ArrayList<String> missing = new ArrayList<String>();
        for (Contract contract : contracts) contract.verify(loader, missing);
        return new GroupResult(name, missing.isEmpty(), missing);
    }

    private static Contract contract(String internalName) {
        return new Contract(internalName);
    }

    public static final class Report {
        public final String profile;
        public final BcuBuildInfo build;
        public final boolean compatible;
        public final Map<String, GroupResult> groups;
        public final List<String> missing;
        public final String targetSha256;
        public final boolean fingerprintVerified;

        private Report(String profile, BcuBuildInfo build, boolean compatible,
                       Map<String, GroupResult> groups, List<String> missing,
                       String targetSha256, boolean fingerprintVerified) {
            this.profile = profile;
            this.build = build;
            this.compatible = compatible;
            this.targetSha256 = targetSha256;
            this.fingerprintVerified = fingerprintVerified;
            this.groups = Collections.unmodifiableMap(
                    new LinkedHashMap<String, GroupResult>(groups));
            this.missing = Collections.unmodifiableList(new ArrayList<String>(missing));
        }

        public String summary() {
            if (compatible) return "compatible profile=" + profile + " targetSha256=" + targetSha256
                    + (fingerprintVerified ? " build=verified"
                    : " build=unverified-same-version (all contracts matched; patch anchors still checked)");
            return "incompatible profile=" + profile + " missing=" + join(missing);
        }
    }

    public static final class GroupResult {
        public final String name;
        public final boolean compatible;
        public final List<String> missing;

        private GroupResult(String name, boolean compatible, List<String> missing) {
            this.name = name;
            this.compatible = compatible;
            this.missing = Collections.unmodifiableList(new ArrayList<String>(missing));
        }
    }

    private static final class Contract {
        private final String internalName;
        private final Set<String> methods = new LinkedHashSet<String>();
        private final Set<String> fields = new LinkedHashSet<String>();
        private final Set<String> fieldTypes = new LinkedHashSet<String>();

        private Contract(String internalName) {
            this.internalName = internalName;
        }

        private Contract method(String name, String descriptor) {
            methods.add(name + descriptor);
            return this;
        }

        private Contract field(String name, String descriptor) {
            fields.add(name + ":" + descriptor);
            return this;
        }

        private Contract fieldType(String descriptor) {
            fieldTypes.add(descriptor);
            return this;
        }

        private void verify(ClassLoader loader, List<String> missing) {
            MemberIndex index = read(loader, internalName);
            if (index == null) {
                missing.add(internalName + ".class");
                return;
            }
            for (String method : methods) {
                if (!index.methods.contains(method))
                    missing.add(internalName + "#" + method);
            }
            for (String field : fields) {
                if (!index.fields.contains(field))
                    missing.add(internalName + "#" + field);
            }
            for (String type : fieldTypes) {
                if (!index.fieldTypes.contains(type))
                    missing.add(internalName + "#field-type:" + type);
            }
        }
    }

    private static MemberIndex read(ClassLoader loader, String internalName) {
        InputStream in = null;
        try {
            String resource = internalName + ".class";
            in = loader == null ? ClassLoader.getSystemResourceAsStream(resource)
                    : loader.getResourceAsStream(resource);
            if (in == null) return null;
            final MemberIndex index = new MemberIndex();
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    index.fields.add(name + ":" + descriptor);
                    index.fieldTypes.add(descriptor);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    index.methods.add(name + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return index;
        } catch (Throwable error) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }

    private static final class MemberIndex {
        private final Set<String> methods = new LinkedHashSet<String>();
        private final Set<String> fields = new LinkedHashSet<String>();
        private final Set<String> fieldTypes = new LinkedHashSet<String>();
    }
}
