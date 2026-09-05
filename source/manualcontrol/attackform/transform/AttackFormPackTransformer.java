package manualcontrol.attackform.transform;

import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class AttackFormPackTransformer implements ClassFileTransformer {

    public static final String USER_PACK = "common/pack/PackData$UserPack";
    public static final String WORKSPACE = "common/pack/Source$Workspace";
    public static final String USER_PROFILE = "common/pack/UserProfile";
    private static final String REPOSITORY =
            "manualcontrol/attackform/data/AttackFormRepository";
    private static final String SAVE_COORDINATOR =
            "manualcontrol/attackform/editor/AttackFormSaveCoordinator";
    private static final String SAVE_DESC =
            "(Lcommon/pack/PackData$UserPack;Z)V";
    private static final String BACKUP_DESC =
            "(Ljava/lang/String;Ljava/lang/String;)Lcommon/pack/PackData$UserPack;";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        Target target = Target.of(className);
        if (target == null || !AttackFormFeature.isReady()) return null;
        if (classBeingRedefined != null) {
            AttackFormFeature.disable("pack persistence class was loaded before its transformer", null);
            return null;
        }
        try {
            Scan before = scan(bytes, target);
            if (before.complete(target)) return null;
            if (before.repositoryCall) {
                AttackFormFeature.disable("partial attack-form sidecar hook detected in " + className, null);
                return null;
            }
            if (!before.anchor) {
                AttackFormFeature.disable("attack-form sidecar anchor missing in " + className, null);
                return null;
            }

            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            reader.accept(new Patcher(writer, target), ClassReader.EXPAND_FRAMES);
            byte[] transformed = writer.toByteArray();
            if (!scan(transformed, target).complete(target)) {
                AttackFormFeature.disable("attack-form sidecar transform verification failed in "
                        + className, null);
                return null;
            }
            AttackFormLog.info("sidecar hook installed on " + className.replace('/', '.'));
            return transformed;
        } catch (Throwable error) {
            AttackFormFeature.disable("attack-form sidecar transform " + className, error);
            return null;
        }
    }

    private static Scan scan(byte[] bytes, final Target target) {
        final Scan scan = new Scan();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                final boolean anchor = target.matches(name, descriptor);
                if (anchor) scan.anchor = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                                                String calledDescriptor, boolean isInterface) {
                        if (anchor && target == Target.WORKSPACE_SAVE
                                && SAVE_COORDINATOR.equals(owner)
                                && opcode == Opcodes.INVOKESTATIC
                                && "rerouteIfNeeded".equals(calledName)
                                && "(Ljava/lang/Object;Ljava/lang/Object;Z)Z"
                                .equals(calledDescriptor)) {
                            scan.edtRerouteHook = true;
                            return;
                        }
                        if (!REPOSITORY.equals(owner)) return;
                        scan.repositoryCall = true;
                        if (anchor && opcode == Opcodes.INVOKESTATIC) {
                            if (target.hookName.equals(calledName)
                                    && target.hookDescriptor.equals(calledDescriptor))
                                scan.hook = true;
                            if (target == Target.WORKSPACE_SAVE
                                    && "beforePackSave".equals(calledName)
                                    && "(Ljava/lang/Object;Ljava/lang/Object;Z)V"
                                    .equals(calledDescriptor))
                                scan.beforeSaveHook = true;
                            if (target == Target.WORKSPACE_SAVE
                                    && "onPackSaveAborted".equals(calledName)
                                    && "(Ljava/lang/Object;Ljava/lang/Object;Z)V"
                                    .equals(calledDescriptor))
                                scan.abortedSaveHook = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static final class Patcher extends ClassVisitor {
        private final Target target;

        Patcher(ClassVisitor next, Target target) {
            super(Opcodes.ASM9, next);
            this.target = target;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!target.matches(name, descriptor)) return base;
            if (target == Target.WORKSPACE_SAVE)
                return new WorkspaceSaveAdapter(base, access, name, descriptor);
            return new MethodVisitor(Opcodes.ASM9, base) {
                @Override
                public void visitInsn(int opcode) {
                    if (target == Target.USER_PACK_LOAD && opcode == Opcodes.RETURN) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        call(target);
                    } else if (target == Target.BACKUP_READ && opcode == Opcodes.ARETURN) {
                        super.visitInsn(Opcodes.DUP);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        call(target);
                    }
                    super.visitInsn(opcode);
                }

                private void call(Target value) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, REPOSITORY,
                            value.hookName, value.hookDescriptor, false);
                }
            };
        }
    }

    private static final class WorkspaceSaveAdapter extends AdviceAdapter {
        private final Label guardedStart = new Label();
        private final Label guardedEnd = new Label();
        private final Label abortHandler = new Label();
        private int reroutedLocal;

        WorkspaceSaveAdapter(MethodVisitor visitor, int access, String name, String descriptor) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            reroutedLocal = newLocal(Type.BOOLEAN_TYPE);
            push(false);
            storeLocal(reroutedLocal);

            loadThis();
            loadArg(0);
            loadArg(1);
            visitMethodInsn(INVOKESTATIC, SAVE_COORDINATOR, "rerouteIfNeeded",
                    "(Ljava/lang/Object;Ljava/lang/Object;Z)Z", false);
            storeLocal(reroutedLocal);
            Label stockPath = newLabel();
            loadLocal(reroutedLocal);
            ifZCmp(EQ, stockPath);

            mv.visitInsn(RETURN);
            mark(stockPath);

            loadArguments();
            visitMethodInsn(INVOKESTATIC, REPOSITORY, "beforePackSave",
                    "(Ljava/lang/Object;Ljava/lang/Object;Z)V", false);
            visitLabel(guardedStart);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != RETURN) return;
            Label done = newLabel();
            loadLocal(reroutedLocal);
            ifZCmp(NE, done);
            loadArguments();
            visitMethodInsn(INVOKESTATIC, REPOSITORY, "onPackSaved",
                    "(Ljava/lang/Object;Ljava/lang/Object;Z)V", false);
            mark(done);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            visitLabel(guardedEnd);
            visitTryCatchBlock(guardedStart, guardedEnd, abortHandler,
                    "java/lang/Throwable");
            visitLabel(abortHandler);

            loadArguments();
            visitMethodInsn(INVOKESTATIC, REPOSITORY, "onPackSaveAborted",
                    "(Ljava/lang/Object;Ljava/lang/Object;Z)V", false);
            visitInsn(ATHROW);
            super.visitMaxs(maxStack, maxLocals);
        }

        private void loadArguments() {
            loadThis();
            loadArg(0);
            loadArg(1);
        }
    }

    private enum Target {
        USER_PACK_LOAD(USER_PACK, "load", "()V", "onPackLoaded",
                "(Ljava/lang/Object;)V"),
        WORKSPACE_SAVE(WORKSPACE, "save", SAVE_DESC, "onPackSaved",
                "(Ljava/lang/Object;Ljava/lang/Object;Z)V"),
        BACKUP_READ(USER_PROFILE, "readBackupPack", BACKUP_DESC,
                "onBackupPackCreated", "(Ljava/lang/Object;Ljava/lang/String;)V");

        final String owner;
        final String methodName;
        final String methodDescriptor;
        final String hookName;
        final String hookDescriptor;

        Target(String owner, String methodName, String methodDescriptor,
               String hookName, String hookDescriptor) {
            this.owner = owner;
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
            this.hookName = hookName;
            this.hookDescriptor = hookDescriptor;
        }

        boolean matches(String name, String descriptor) {
            return methodName.equals(name) && methodDescriptor.equals(descriptor);
        }

        static Target of(String owner) {
            for (Target target : values()) if (target.owner.equals(owner)) return target;
            return null;
        }
    }

    private static final class Scan {
        boolean anchor;
        boolean repositoryCall;
        boolean hook;
        boolean beforeSaveHook;
        boolean abortedSaveHook;
        boolean edtRerouteHook;

        boolean complete(Target target) {
            return hook && (target != Target.WORKSPACE_SAVE
                    || (beforeSaveHook && abortedSaveHook && edtRerouteHook));
        }
    }
}
