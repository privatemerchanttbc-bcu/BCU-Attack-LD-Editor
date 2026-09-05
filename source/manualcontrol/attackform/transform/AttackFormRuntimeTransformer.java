package manualcontrol.attackform.transform;

import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;

public final class AttackFormRuntimeTransformer implements ClassFileTransformer {

    private static final String ENTITY = "common/battle/entity/Entity";
    private static final String ANIM_MANAGER = "common/battle/entity/Entity$AnimManager";
    private static final String ATK_MANAGER = "common/battle/entity/Entity$AtkManager";
    private static final String ATK_MODEL = "common/battle/attack/AtkModelEntity";
    private static final String EANIMD = "common/util/anim/EAnimD";
    private static final String HOOKS = "manualcontrol/attackform/runtime/AttackFormRuntimeHooks";
    private static final String ENTITY_CARRIER =
            "manualcontrol/attackform/runtime/AttackFormRuntimeCarrier";
    private static final String MANAGER_CARRIER =
            "manualcontrol/attackform/runtime/AttackFormManagerCarrier";
    private static final String TIMELINE_CARRIER =
            "manualcontrol/attackform/runtime/AttackFormTimelineCarrier";
    private static final String STATE_DESC =
            "Lmanualcontrol/attackform/runtime/AttackFormRuntimeState;";
    private static final String MASK_DESC = "Lcommon/battle/data/MaskEntity;";
    private static final String MODEL_DESC = "Lcommon/battle/attack/AtkModelEntity;";
    private static final String EANIMU_DESC = "Lcommon/util/anim/EAnimU;";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!target(className) || !AttackFormFeature.isReady()) return null;
        if (classBeingRedefined != null) {
            AttackFormFeature.disable("Part 4 runtime class was already loaded: " + className, null);
            return null;
        }
        try {
            Marker marker = marker(bytes, className);
            if (marker.complete) return null;
            if (marker.partial) {
                AttackFormFeature.disable("partial Part 4 runtime patch in " + className, null);
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor patcher;
            if (ENTITY.equals(className)) patcher = new EntityPatcher(writer);
            else if (ANIM_MANAGER.equals(className)) patcher = new AnimManagerPatcher(writer);
            else if (ATK_MANAGER.equals(className)) patcher = new ManagerPatcher(writer);
            else if (ATK_MODEL.equals(className)) patcher = new ModelPatcher(writer);
            else patcher = new TimelinePatcher(writer);
            reader.accept(patcher, ClassReader.EXPAND_FRAMES);
            byte[] transformed = writer.toByteArray();
            Marker verify = marker(transformed, className);
            if (!verify.complete) {
                AttackFormFeature.disable("Part 4 runtime verification failed in " + className, null);
                return null;
            }
            AttackFormLog.info("Part 4 battle hook installed in " + className);
            return transformed;
        } catch (Throwable error) {
            AttackFormFeature.disable("Part 4 runtime transform " + className, error);
            return null;
        }
    }

    private static boolean target(String name) {
        return ENTITY.equals(name) || ANIM_MANAGER.equals(name) || ATK_MANAGER.equals(name)
                || ATK_MODEL.equals(name) || EANIMD.equals(name);
    }

    private static Marker marker(byte[] bytes, final String target) {
        final Marker result = new Marker();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                String expected = ENTITY.equals(target) ? ENTITY_CARRIER
                        : ATK_MANAGER.equals(target) ? MANAGER_CARRIER
                        : EANIMD.equals(target) ? TIMELINE_CARRIER : null;
                if (expected != null && interfaces != null)
                    for (String value : interfaces) if (expected.equals(value)) result.itf = true;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (ENTITY.equals(target) && "attackFormRuntime$state".equals(name))
                    result.field = STATE_DESC.equals(descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("attackFormRuntime$marker".equals(name) && "()V".equals(descriptor))
                    result.method = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (ENTITY.equals(target)) {
            result.complete = result.itf && result.field && result.method;
            result.partial = !result.complete && (result.itf || result.field || result.method);
        } else if (ATK_MANAGER.equals(target) || EANIMD.equals(target)) {
            result.complete = result.itf && result.method;
            result.partial = !result.complete && (result.itf || result.method);
        } else {
            result.complete = result.method;
            result.partial = false;
        }
        return result;
    }

    private static String[] addInterface(String[] interfaces, String value) {
        if (interfaces == null) return new String[]{value};
        String[] copy = Arrays.copyOf(interfaces, interfaces.length + 1);
        copy[interfaces.length] = value;
        return copy;
    }

    private static void markerMethod(ClassVisitor visitor) {
        MethodVisitor mv = visitor.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "attackFormRuntime$marker", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
    }

    private static final class EntityPatcher extends ClassVisitor {
        private String owner;

        private EntityPatcher(ClassVisitor next) { super(Opcodes.ASM9, next); }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            owner = name;
            super.visit(version, access, name, signature, superName,
                    addInterface(interfaces, ENTITY_CARRIER));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (("data".equals(name) && MASK_DESC.equals(descriptor))
                    || ("aam".equals(name) && MODEL_DESC.equals(descriptor)))
                access &= ~Opcodes.ACC_FINAL;
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("checkTouch".equals(name) && "()Z".equals(descriptor))
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override protected void onMethodEnter() {
                        loadThis();
                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beforeTargetSearch",
                                "(Lcommon/battle/entity/Entity;)V", false);
                    }
                };
            if ("<init>".equals(name) && unitConstructor(descriptor))
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.RETURN) return;
                        loadThis();
                        loadArg(4);  
                        loadArg(3);  
                        loadArg(6);
                        loadArg(7);
                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "initializeUnit",
                                "(Lcommon/battle/entity/Entity;FFLcommon/battle/data/PCoin;"
                                        + "Lcommon/util/unit/Level;)V", false);
                    }
                };
            if ("<init>".equals(name) && enemyConstructor(descriptor))
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.RETURN) return;
                        loadThis();
                        loadArg(3);
                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "initializeEnemy",
                                "(Lcommon/battle/entity/Entity;F)V", false);
                    }
                };
            return base;
        }

        private static boolean unitConstructor(String descriptor) {
            Type[] args = Type.getArgumentTypes(descriptor);
            return args.length == 8
                    && "common/battle/StageBasis".equals(args[0].getInternalName())
                    && "common/util/anim/EAnimU".equals(args[2].getInternalName())
                    && args[3].getSort() == Type.FLOAT && args[4].getSort() == Type.FLOAT
                    && args[5].getSort() == Type.FLOAT
                    && "common/battle/data/PCoin".equals(args[6].getInternalName())
                    && "common/util/unit/Level".equals(args[7].getInternalName());
        }

        private static boolean enemyConstructor(String descriptor) {
            Type[] args = Type.getArgumentTypes(descriptor);
            return args.length == 5
                    && "common/battle/StageBasis".equals(args[0].getInternalName())
                    && "common/util/anim/EAnimU".equals(args[2].getInternalName())
                    && args[3].getSort() == Type.FLOAT && args[4].getSort() == Type.FLOAT;
        }

        @Override
        public void visitEnd() {
            FieldVisitor field = super.visitField(Opcodes.ACC_PRIVATE,
                    "attackFormRuntime$state", STATE_DESC, null, null);
            field.visitEnd();

            simpleGetter("attackFormRuntime$get", "()" + STATE_DESC,
                    "attackFormRuntime$state", STATE_DESC, Opcodes.ARETURN);
            simpleSetter("attackFormRuntime$set", "(" + STATE_DESC + ")V",
                    "attackFormRuntime$state", STATE_DESC);
            simpleGetter("attackFormRuntime$data", "()" + MASK_DESC,
                    "data", MASK_DESC, Opcodes.ARETURN);
            simpleGetter("attackFormRuntime$model", "()" + MODEL_DESC,
                    "aam", MODEL_DESC, Opcodes.ARETURN);
            simpleGetter("attackFormRuntime$manager", "()Ljava/lang/Object;",
                    "atkm", "Lcommon/battle/entity/Entity$AtkManager;", Opcodes.ARETURN);

            MethodVisitor animation = super.visitMethod(Opcodes.ACC_PUBLIC,
                    "attackFormRuntime$animation", "()" + EANIMU_DESC, null, null);
            animation.visitCode();
            animation.visitVarInsn(Opcodes.ALOAD, 0);
            animation.visitFieldInsn(Opcodes.GETFIELD, owner, "anim",
                    "Lcommon/battle/entity/Entity$AnimManager;");
            animation.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ANIM_MANAGER,
                    "attackFormRuntime$animation", "()" + EANIMU_DESC, false);
            animation.visitInsn(Opcodes.ARETURN);
            animation.visitMaxs(1, 1);
            animation.visitEnd();

            MethodVisitor activate = super.visitMethod(Opcodes.ACC_PUBLIC,
                    "attackFormRuntime$activate", "(" + MASK_DESC + MODEL_DESC + ")V", null, null);
            activate.visitCode();
            activate.visitVarInsn(Opcodes.ALOAD, 0);
            activate.visitVarInsn(Opcodes.ALOAD, 1);
            activate.visitFieldInsn(Opcodes.PUTFIELD, owner, "data", MASK_DESC);
            activate.visitVarInsn(Opcodes.ALOAD, 0);
            activate.visitVarInsn(Opcodes.ALOAD, 2);
            activate.visitFieldInsn(Opcodes.PUTFIELD, owner, "aam", MODEL_DESC);
            activate.visitInsn(Opcodes.RETURN);
            activate.visitMaxs(2, 3);
            activate.visitEnd();
            markerMethod(this);
            super.visitEnd();
        }

        private void simpleGetter(String name, String desc, String field, String fieldDesc, int ret) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, fieldDesc);
            mv.visitInsn(ret);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        private void simpleSetter(String name, String desc, String field, String fieldDesc) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, owner, field, fieldDesc);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }
    }

    private static final class AnimManagerPatcher extends ClassVisitor {
        private AnimManagerPatcher(ClassVisitor next) { super(Opcodes.ASM9, next); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("setAnim".equals(name)
                    && "(Lcommon/util/anim/AnimU$UType;Z)I".equals(descriptor)) {
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.IRETURN) return;
                        loadThis();
                        visitFieldInsn(Opcodes.GETFIELD, ANIM_MANAGER, "e",
                                "Lcommon/battle/entity/Entity;");
                        loadThis();
                        visitFieldInsn(Opcodes.GETFIELD, ANIM_MANAGER, "anim", EANIMU_DESC);
                        loadArg(0);
                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "animationLength",
                                "(ILcommon/battle/entity/Entity;Lcommon/util/anim/EAnimU;"
                                        + "Lcommon/util/anim/AnimU$UType;)I", false);
                    }
                };
            }
            return base;
        }

        @Override
        public void visitEnd() {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC,
                    "attackFormRuntime$animation", "()" + EANIMU_DESC, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ANIM_MANAGER, "anim", EANIMU_DESC);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            markerMethod(this);
            super.visitEnd();
        }
    }

    private static final class ManagerPatcher extends ClassVisitor {
        private ManagerPatcher(ClassVisitor next) { super(Opcodes.ASM9, next); }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName,
                    addInterface(interfaces, MANAGER_CARRIER));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            if (("multi".equals(name) && "I".equals(descriptor))
                    || ("pres".equals(name) && "[I".equals(descriptor)))
                access &= ~Opcodes.ACC_FINAL;
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("startAttack".equals(name) && "()V".equals(descriptor))
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override protected void onMethodEnter() {
                        loadThis();
                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beforeStartAttack",
                                "(Ljava/lang/Object;)V", false);
                    }
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode == Opcodes.ATHROW) return;
                        loadThis();
                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "afterStartAttack",
                                "(Ljava/lang/Object;)V", false);
                    }
                };
            if ("updateAttack".equals(name) && "()V".equals(descriptor))
                return exitHook(base, access, name, descriptor, "afterUpdateAttack");
            if ("stopAtk".equals(name) && "()V".equals(descriptor))
                return exitHook(base, access, name, descriptor, "afterStopAttack");
            return base;
        }

        private MethodVisitor exitHook(MethodVisitor base, int access, String name,
                                       String descriptor, final String hook) {
            return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                @Override protected void onMethodExit(int opcode) {
                    if (opcode == Opcodes.ATHROW) return;
                    loadThis();
                    visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, hook,
                            "(Ljava/lang/Object;)V", false);
                }
            };
        }

        @Override
        public void visitEnd() {
            getter("attackFormRuntime$owner", "()Lcommon/battle/entity/Entity;",
                    "e", "Lcommon/battle/entity/Entity;", Opcodes.ARETURN);
            getter("attackFormRuntime$attackTime", "()I", "atkTime", "I", Opcodes.IRETURN);
            setterInt("attackFormRuntime$attackTime", "atkTime");
            getter("attackFormRuntime$preTime", "()I", "preTime", "I", Opcodes.IRETURN);
            getter("attackFormRuntime$attacksLeft", "()I", "attacksLeft", "I", Opcodes.IRETURN);

            MethodVisitor configure = super.visitMethod(Opcodes.ACC_PUBLIC,
                    "attackFormRuntime$configure", "([II)V", null, null);
            configure.visitCode();
            configure.visitVarInsn(Opcodes.ALOAD, 0);
            configure.visitVarInsn(Opcodes.ALOAD, 1);
            configure.visitFieldInsn(Opcodes.PUTFIELD, ATK_MANAGER, "pres", "[I");
            configure.visitVarInsn(Opcodes.ALOAD, 0);
            configure.visitVarInsn(Opcodes.ALOAD, 1);
            configure.visitInsn(Opcodes.ARRAYLENGTH);
            configure.visitFieldInsn(Opcodes.PUTFIELD, ATK_MANAGER, "multi", "I");
            configure.visitVarInsn(Opcodes.ALOAD, 0);
            configure.visitVarInsn(Opcodes.ILOAD, 2);
            configure.visitFieldInsn(Opcodes.PUTFIELD, ATK_MANAGER, "attacksLeft", "I");
            configure.visitVarInsn(Opcodes.ALOAD, 0);
            configure.visitInsn(Opcodes.ICONST_0);
            configure.visitFieldInsn(Opcodes.PUTFIELD, ATK_MANAGER, "preID", "I");
            configure.visitVarInsn(Opcodes.ALOAD, 0);
            configure.visitInsn(Opcodes.ICONST_M1);
            configure.visitFieldInsn(Opcodes.PUTFIELD, ATK_MANAGER, "tempAtk", "I");
            configure.visitInsn(Opcodes.RETURN);
            configure.visitMaxs(2, 3);
            configure.visitEnd();
            markerMethod(this);
            super.visitEnd();
        }

        private void getter(String name, String desc, String field, String fieldDesc, int ret) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ATK_MANAGER, field, fieldDesc);
            mv.visitInsn(ret);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }

        private void setterInt(String name, String field) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, name, "(I)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, ATK_MANAGER, field, "I");
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
        }
    }

    private static final class ModelPatcher extends ClassVisitor {
        private ModelPatcher(ClassVisitor next) { super(Opcodes.ASM9, next); }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("getAttack".equals(name)
                    && "(I)Lcommon/battle/attack/AttackAb;".equals(descriptor)) {
                return new AdviceAdapter(Opcodes.ASM9, base, access, name, descriptor) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.ARETURN) return;
                        loadThis();
                        loadArg(0);
                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "attackCreated",
                                "(Lcommon/battle/attack/AttackAb;"
                                        + "Lcommon/battle/attack/AtkModelEntity;I)"
                                        + "Lcommon/battle/attack/AttackAb;", false);
                    }
                };
            }
            return base;
        }

        @Override public void visitEnd() {
            markerMethod(this);
            super.visitEnd();
        }
    }

    private static final class TimelinePatcher extends ClassVisitor {
        private TimelinePatcher(ClassVisitor next) { super(Opcodes.ASM9, next); }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName,
                    addInterface(interfaces, TIMELINE_CARRIER));
        }

        @Override public void visitEnd() {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC,
                    "attackFormRuntime$timeline", "(Lcommon/util/anim/MaAnim;)V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, EANIMD, "ma", "Lcommon/util/anim/MaAnim;");
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EANIMD, "setTime", "(F)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(2, 2);
            mv.visitEnd();
            markerMethod(this);
            super.visitEnd();
        }
    }

    private static final class Marker {
        boolean itf;
        boolean field;
        boolean method;
        boolean complete;
        boolean partial;
    }
}
