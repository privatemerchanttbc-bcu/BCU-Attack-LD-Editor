package manualcontrol.attackform.transform;

import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;

public final class AttackFormEditorTransformer implements ClassFileTransformer {

    public static final String ENTITY_PAGE = "page/info/edit/EntityEditPage";
    public static final String FORM_PAGE = "page/info/edit/FormEditPage";
    public static final String ENEMY_PAGE = "page/info/edit/EnemyEditPage";
    private static final String PAGE = "page/Page";
    private static final String HOOKS =
            "manualcontrol/attackform/editor/AttackFormEditorHooks";
    private static final String ENTITY_DESC = "Lcommon/battle/data/CustomEntity;";
    private static final String SET_DATA_DESC = "(" + ENTITY_DESC + ")V";
    private static final String FORM_CTOR =
            "(Lpage/Page;Lcommon/pack/PackData$UserPack;Lcommon/util/unit/Form;)V";
    private static final String ENEMY_CTOR =
            "(Lpage/Page;Lcommon/util/unit/Enemy;Lcommon/pack/PackData$UserPack;)V";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!target(className) || !AttackFormFeature.isReady()) return null;
        if (classBeingRedefined != null) {
            AttackFormFeature.disable("Part 3 editor class was already loaded: " + className, null);
            return null;
        }
        try {
            Scan before = scan(bytes, className);
            if (before.complete(className)) return null;
            if (before.anyHook) {
                AttackFormFeature.disable("partial Part 3 editor hook detected in " + className, null);
                return null;
            }
            validateOriginal(before, className);

            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            Patcher patcher = new Patcher(writer, className);
            reader.accept(patcher, ClassReader.EXPAND_FRAMES);
            patcher.verify();
            byte[] transformed = writer.toByteArray();
            Scan after = scan(transformed, className);
            if (!after.complete(className)) {
                AttackFormFeature.disable("Part 3 editor transform verification failed in "
                        + className, null);
                return null;
            }
            AttackFormLog.info("Part 3 editor hooks installed in " + className);
            return transformed;
        } catch (Throwable error) {
            AttackFormFeature.disable("Part 3 editor transform " + className, error);
            return null;
        }
    }

    private static boolean target(String value) {
        return ENTITY_PAGE.equals(value) || FORM_PAGE.equals(value) || ENEMY_PAGE.equals(value);
    }

    private static void validateOriginal(Scan scan, String target) {
        if (ENTITY_PAGE.equals(target)) {
            if (!PAGE.equals(scan.superName) || !scan.entityField || !scan.editableField
                    || scan.setDataMethods != 1 || scan.renewMethods != 1
                    || scan.leaveMethods != 0 || scan.exitMethods != 0
                    || scan.setDataReturns < 1 || scan.renewReturns < 1)
                throw new IllegalStateException("EntityEditPage contract changed");
            return;
        }
        String ctor = FORM_PAGE.equals(target) ? FORM_CTOR : ENEMY_CTOR;
        if (!ENTITY_PAGE.equals(scan.superName) || scan.constructorDescriptorCount(ctor) != 1
                || scan.resizedMethods != 1 || scan.constructorReturns != 1
                || scan.resizedReturns != 1 || scan.leaveMethods != 0 || scan.exitMethods != 0)
            throw new IllegalStateException(target + " contract changed");
    }

    private static Scan scan(byte[] bytes, final String target) {
        final Scan scan = new Scan();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                scan.superName = superName;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if ("ce".equals(name) && ENTITY_DESC.equals(descriptor)) scan.entityField = true;
                if ("editable".equals(name) && "Z".equals(descriptor)) scan.editableField = true;
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, final String name,
                                             final String descriptor, String signature,
                                             String[] exceptions) {
                final boolean setData = "setData".equals(name) && SET_DATA_DESC.equals(descriptor);
                final boolean renew = "renew".equals(name) && "()V".equals(descriptor);
                final boolean leave = "leave".equals(name) && "()V".equals(descriptor);
                final boolean exit = "exit".equals(name) && "()V".equals(descriptor);
                final boolean resized = "resized".equals(name) && "(II)V".equals(descriptor);
                final boolean constructor = "<init>".equals(name)
                        && (FORM_CTOR.equals(descriptor) || ENEMY_CTOR.equals(descriptor));
                if (setData) scan.setDataMethods++;
                if (renew) scan.renewMethods++;
                if (leave) scan.leaveMethods++;
                if (exit) scan.exitMethods++;
                if (resized) scan.resizedMethods++;
                if (constructor) {
                    Integer count = scan.constructors.get(descriptor);
                    scan.constructors.put(descriptor, count == null ? 1 : count + 1);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            if (setData) scan.setDataReturns++;
                            if (renew) scan.renewReturns++;
                            if (resized) scan.resizedReturns++;
                            if (constructor) scan.constructorReturns++;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                                                String calledDescriptor, boolean isInterface) {
                        if (!HOOKS.equals(owner)) return;
                        scan.anyHook = true;
                        if (opcode != Opcodes.INVOKESTATIC || !expectedHook(target, name,
                                descriptor, calledName, calledDescriptor)) {
                            scan.invalidHook = true;
                            return;
                        }
                        Integer count = scan.hooks.get(calledName);
                        scan.hooks.put(calledName, count == null ? 1 : count + 1);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static boolean expectedHook(String target, String sourceName,
                                        String sourceDescriptor, String hook,
                                        String hookDescriptor) {
        if (ENTITY_PAGE.equals(target)) {
            if ("afterStockSetData".equals(hook))
                return "setData".equals(sourceName) && SET_DATA_DESC.equals(sourceDescriptor)
                        && "(Ljava/lang/Object;)V".equals(hookDescriptor);
            if ("afterRenew".equals(hook))
                return "renew".equals(sourceName) && "()V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;)V".equals(hookDescriptor);
            if ("onLeave".equals(hook))
                return "leave".equals(sourceName) && "()V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;)V".equals(hookDescriptor);
            if ("onExit".equals(hook))
                return "exit".equals(sourceName) && "()V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;)V".equals(hookDescriptor);
            return false;
        }
        if ("onBuilt".equals(hook))
            return "<init>".equals(sourceName)
                    && (FORM_PAGE.equals(target) ? FORM_CTOR : ENEMY_CTOR).equals(sourceDescriptor)
                    && "(Ljava/lang/Object;)V".equals(hookDescriptor);
        return "onResized".equals(hook) && "resized".equals(sourceName)
                && "(II)V".equals(sourceDescriptor)
                && "(Ljava/lang/Object;II)V".equals(hookDescriptor);
    }

    private static final class Patcher extends ClassVisitor {
        private final String target;
        private String superName;
        private int setData;
        private int renew;
        private int constructors;
        private int resized;
        private int leave;
        private int exit;

        Patcher(ClassVisitor next, String target) {
            super(Opcodes.ASM9, next);
            this.target = target;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.superName = superName;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (ENTITY_PAGE.equals(target)) {
                if ("setData".equals(name) && SET_DATA_DESC.equals(descriptor)) {
                    setData++;
                    return new ObjectExit(base, access, name, descriptor,
                            "afterStockSetData");
                }
                if ("renew".equals(name) && "()V".equals(descriptor)) {
                    renew++;
                    return new ObjectExit(base, access, name, descriptor, "afterRenew");
                }
                if ("leave".equals(name) && "()V".equals(descriptor)) leave++;
                if ("exit".equals(name) && "()V".equals(descriptor)) exit++;
                return base;
            }
            String ctor = FORM_PAGE.equals(target) ? FORM_CTOR : ENEMY_CTOR;
            if ("<init>".equals(name) && ctor.equals(descriptor)) {
                constructors++;
                return new ObjectExit(base, access, name, descriptor, "onBuilt");
            }
            if ("resized".equals(name) && "(II)V".equals(descriptor)) {
                resized++;
                return new ResizeExit(base, access, name, descriptor);
            }
            if ("leave".equals(name) && "()V".equals(descriptor)) leave++;
            if ("exit".equals(name) && "()V".equals(descriptor)) exit++;
            return base;
        }

        @Override
        public void visitEnd() {
            if (ENTITY_PAGE.equals(target)) {
                addLifecycle("leave", "onLeave");
                addLifecycle("exit", "onExit");
            }
            super.visitEnd();
        }

        private void addLifecycle(String methodName, String hook) {
            MethodVisitor method = super.visitMethod(Opcodes.ACC_PROTECTED, methodName,
                    "()V", null, null);
            method.visitCode();
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, hook,
                    "(Ljava/lang/Object;)V", false);
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESPECIAL, PAGE, methodName, "()V", false);
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }

        void verify() {
            if (ENTITY_PAGE.equals(target)) {
                if (!PAGE.equals(superName) || setData != 1 || renew != 1
                        || leave != 0 || exit != 0)
                    throw new IllegalStateException("EntityEditPage patch anchors changed");
            } else if (!ENTITY_PAGE.equals(superName) || constructors != 1 || resized != 1
                    || leave != 0 || exit != 0) {
                throw new IllegalStateException(target + " patch anchors changed");
            }
        }
    }

    private static final class ObjectExit extends AdviceAdapter {
        private final String hook;

        ObjectExit(MethodVisitor next, int access, String name, String descriptor, String hook) {
            super(Opcodes.ASM9, next, access, name, descriptor);
            this.hook = hook;
        }

        @Override protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            loadThis();
            visitMethodInsn(INVOKESTATIC, HOOKS, hook, "(Ljava/lang/Object;)V", false);
        }
    }

    private static final class ResizeExit extends AdviceAdapter {
        ResizeExit(MethodVisitor next, int access, String name, String descriptor) {
            super(Opcodes.ASM9, next, access, name, descriptor);
        }

        @Override protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            loadThis();
            loadArg(0);
            loadArg(1);
            visitMethodInsn(INVOKESTATIC, HOOKS, "onResized",
                    "(Ljava/lang/Object;II)V", false);
        }
    }

    private static final class Scan {
        String superName;
        boolean entityField;
        boolean editableField;
        boolean anyHook;
        boolean invalidHook;
        int setDataMethods;
        int renewMethods;
        int leaveMethods;
        int exitMethods;
        int resizedMethods;
        int setDataReturns;
        int renewReturns;
        int constructorReturns;
        int resizedReturns;
        final Map<String, Integer> constructors = new HashMap<String, Integer>();
        final Map<String, Integer> hooks = new HashMap<String, Integer>();

        int constructorDescriptorCount(String descriptor) {
            Integer count = constructors.get(descriptor);
            return count == null ? 0 : count;
        }

        int hook(String name) {
            Integer count = hooks.get(name);
            return count == null ? 0 : count;
        }

        boolean complete(String target) {
            if (invalidHook) return false;
            if (ENTITY_PAGE.equals(target))
                return setDataReturns > 0 && renewReturns > 0
                        && hook("afterStockSetData") == setDataReturns
                        && hook("afterRenew") == renewReturns
                        && leaveMethods == 1 && exitMethods == 1
                        && hook("onLeave") == 1 && hook("onExit") == 1;
            return constructorReturns == 1 && resizedReturns == 1
                    && hook("onBuilt") == 1 && hook("onResized") == 1;
        }
    }
}
