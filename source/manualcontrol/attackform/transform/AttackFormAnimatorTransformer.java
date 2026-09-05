package manualcontrol.attackform.transform;

import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AttackFormAnimatorTransformer implements ClassFileTransformer {

    static final String PAGE = "page/anim/MaAnimEditPage";
    static final String ANIMATION = "common/util/anim/AnimCE";
    static final String SAVER = "common/pack/Source$SourceAnimSaver";
    static final String UI_HOOKS =
            "manualcontrol/attackform/animator/AttackFormAnimatorHooks";
    static final String STORE =
            "manualcontrol/attackform/animator/AttackAnimationStore";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!PAGE.equals(className) && !ANIMATION.equals(className) && !SAVER.equals(className))
            return null;
        if (!AttackFormFeature.isReady()) return null;
        if (classBeingRedefined != null) {
            AttackFormFeature.disable("Part 2 class was loaded before its transformer: "
                    + className, null);
            return null;
        }
        String hookOwner = PAGE.equals(className) ? UI_HOOKS : STORE;
        try {
            Scan before = scan(bytes, hookOwner, className);
            if (before.complete(className)) return null;
            if (before.anyHook) {
                AttackFormFeature.disable("partial Part 2 Animator hook detected in "
                        + className, null);
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            Patcher patcher = new Patcher(writer, className);
            reader.accept(patcher, ClassReader.EXPAND_FRAMES);
            patcher.verify();
            byte[] transformed = writer.toByteArray();
            if (!scan(transformed, hookOwner, className).complete(className)) {
                AttackFormFeature.disable("Part 2 transform verification failed in "
                        + className, null);
                return null;
            }
            AttackFormLog.info("Part 2 Animator hooks installed in " + className);
            return transformed;
        } catch (Throwable error) {
            AttackFormFeature.disable("Part 2 transform " + className, error);
            return null;
        }
    }

    private static Scan scan(byte[] bytes, final String owner, final String target) {
        final Scan scan = new Scan();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                final String sourceName = name;
                final String sourceDescriptor = descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String callOwner, String callName,
                                                String callDescriptor, boolean isInterface) {
                        if (owner.equals(callOwner)) {
                            scan.anyHook = true;
                            if (opcode != Opcodes.INVOKESTATIC || !expectedHook(target,
                                    sourceName, sourceDescriptor, callName, callDescriptor)) {
                                scan.invalidHook = true;
                            } else {
                                Integer count = scan.hooks.get(callName);
                                scan.hooks.put(callName, count == null ? 1 : count + 1);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static boolean expectedHook(String target, String sourceName,
                                        String sourceDescriptor, String hook,
                                        String hookDescriptor) {
        if (PAGE.equals(target)) {
            if ("onLdMousePressed".equals(hook) || "onLdMouseDragged".equals(hook)
                    || "onLdMouseReleased".equals(hook))
                return sourceName.equals("mouse" + hook.substring("onLdMouse".length()))
                        && "(Ljava/awt/event/MouseEvent;)V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;Ljava/lang/Object;)Z".equals(hookDescriptor);
            if ("onBuilt".equals(hook))
                return "<init>".equals(sourceName)
                        && ("(Lpage/Page;)V".equals(sourceDescriptor)
                        || "(Lpage/Page;Lpage/anim/EditHead;)V".equals(sourceDescriptor))
                        && "(Ljava/lang/Object;)V".equals(hookDescriptor);
            if ("onResized".equals(hook))
                return "resized".equals(sourceName) && "(II)V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;II)V".equals(hookDescriptor);
            if ("beforeSetAnimation".equals(hook) || "afterSetAnimation".equals(hook))
                return "setA".equals(sourceName)
                        && "(Lcommon/util/anim/AnimCE;)V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;Lcommon/util/anim/AnimCE;)V"
                        .equals(hookDescriptor);
            if ("selectAnimation".equals(hook))
                return "setB".equals(sourceName)
                        && "(Lcommon/util/anim/AnimCE;I)V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;Lcommon/util/anim/AnimCE;I)Z"
                        .equals(hookDescriptor);
            if ("callBack".equals(hook))
                return "callBack".equals(sourceName)
                        && "(Ljava/lang/Object;)V".equals(sourceDescriptor)
                        && "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                        .equals(hookDescriptor);
            return false;
        }
        if (ANIMATION.equals(target)) {
            if (("onLoaded".equals(hook) && "load".equals(sourceName)
                    && "()V".equals(sourceDescriptor))
                    || ("onUnsaved".equals(hook) && "unSave".equals(sourceName)
                    && "(Ljava/lang/String;)V".equals(sourceDescriptor))
                    || ("onRestored".equals(hook) && "restore".equals(sourceName)
                    && "()V".equals(sourceDescriptor)))
                return "(Ljava/lang/Object;)V".equals(hookDescriptor);
            return "onCopied".equals(hook) && "<init>".equals(sourceName)
                    && "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimD;)V"
                    .equals(sourceDescriptor)
                    && "(Ljava/lang/Object;Ljava/lang/Object;)V".equals(hookDescriptor);
        }
        if (!SAVER.equals(target) || !"saveData".equals(sourceName)
                || !"()V".equals(sourceDescriptor)) return false;
        return ("beforeSave".equals(hook) || "onSaveFailed".equals(hook)
                || "onSaveAborted".equals(hook) || "afterSave".equals(hook))
                && "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimCI;)V"
                .equals(hookDescriptor);
    }

    private static final class Patcher extends ClassVisitor {
        private final String target;
        private int constructors;
        private int resized;
        private int setA;
        private int setB;
        private int callbacks;
        private int mouseGates;
        private int loads;
        private int unsaves;
        private int restores;
        private int copies;
        private int saves;
        private int saveFailureHandlers;

        Patcher(ClassVisitor visitor, String target) {
            super(Opcodes.ASM9, visitor);
            this.target = target;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor,
                    signature, exceptions);
            if (PAGE.equals(target)) {
                if (("mousePressed".equals(name) || "mouseDragged".equals(name)
                        || "mouseReleased".equals(name))
                        && "(Ljava/awt/event/MouseEvent;)V".equals(descriptor)) {
                    mouseGates++;
                    return new MouseGate(visitor, access, name, descriptor);
                }
                if ("<init>".equals(name)
                        && ("(Lpage/Page;)V".equals(descriptor)
                        || "(Lpage/Page;Lpage/anim/EditHead;)V".equals(descriptor))) {
                    constructors++;
                    return new PageExit(visitor, access, name, descriptor,
                            "onBuilt", "(Ljava/lang/Object;)V", false);
                }
                if ("resized".equals(name) && "(II)V".equals(descriptor)) {
                    resized++;
                    return new PageExit(visitor, access, name, descriptor,
                            "onResized", "(Ljava/lang/Object;II)V", true);
                }
                if ("setA".equals(name)
                        && "(Lcommon/util/anim/AnimCE;)V".equals(descriptor)) {
                    setA++;
                    return new SetAAdapter(visitor, access, name, descriptor);
                }
                if ("setB".equals(name)
                        && "(Lcommon/util/anim/AnimCE;I)V".equals(descriptor)) {
                    setB++;
                    return new SetBGate(visitor, access, name, descriptor);
                }
                if ("callBack".equals(name) && "(Ljava/lang/Object;)V".equals(descriptor)) {
                    callbacks++;
                    return new CallbackGate(visitor, access, name, descriptor);
                }
            } else if (ANIMATION.equals(target)) {
                if ("load".equals(name) && "()V".equals(descriptor)) {
                    loads++;
                    return new StoreExit(visitor, access, name, descriptor,
                            "onLoaded", "(Ljava/lang/Object;)V", false);
                }
                if ("unSave".equals(name) && "(Ljava/lang/String;)V".equals(descriptor)) {
                    unsaves++;
                    return new StoreExit(visitor, access, name, descriptor,
                            "onUnsaved", "(Ljava/lang/Object;)V", false);
                }
                if ("restore".equals(name) && "()V".equals(descriptor)) {
                    restores++;
                    return new StoreExit(visitor, access, name, descriptor,
                            "onRestored", "(Ljava/lang/Object;)V", false);
                }
                if ("<init>".equals(name)
                        && "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimD;)V"
                        .equals(descriptor)) {
                    copies++;
                    return new StoreExit(visitor, access, name, descriptor,
                            "onCopied", "(Ljava/lang/Object;Ljava/lang/Object;)V", true);
                }
            } else if (SAVER.equals(target)
                    && "saveData".equals(name) && "()V".equals(descriptor)) {
                saves++;
                return new SaverAdapter(visitor, access, name, descriptor, this);
            }
            return visitor;
        }

        void verify() {
            if (PAGE.equals(target)) {
                if (constructors != 2 || resized != 1 || setA != 1 || setB != 1
                        || callbacks != 1 || mouseGates != 3)
                    throw new IllegalStateException("Animator hook anchors changed: constructors="
                            + constructors + " resized=" + resized + " setA=" + setA
                            + " setB=" + setB + " callbacks=" + callbacks
                            + " mouseGates=" + mouseGates);
            } else if (ANIMATION.equals(target)) {
                if (loads != 1 || unsaves != 1 || restores != 1 || copies != 1)
                    throw new IllegalStateException("AnimCE hook anchors changed: load=" + loads
                            + " unSave=" + unsaves + " restore=" + restores
                            + " copy=" + copies);
            } else if (SAVER.equals(target)
                    && (saves != 1 || saveFailureHandlers != 1)) {
                throw new IllegalStateException("SourceAnimSaver.saveData anchors changed: save="
                        + saves + " IOExceptionHandlers=" + saveFailureHandlers);
            }
        }
    }

    private static final class MouseGate extends AdviceAdapter {
        private final String hook;

        MouseGate(MethodVisitor visitor, int access, String name, String descriptor) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
            hook = "onLdMouse" + name.substring("mouse".length());
        }

        @Override protected void onMethodEnter() {
            loadThis();
            loadArg(0);
            visitMethodInsn(INVOKESTATIC, UI_HOOKS, hook,
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
            Label stock = new Label();
            visitJumpInsn(IFEQ, stock);
            visitInsn(RETURN);
            visitLabel(stock);
        }
    }

    private static final class PageExit extends AdviceAdapter {
        private final String hook;
        private final String hookDescriptor;
        private final boolean dimensions;

        PageExit(MethodVisitor visitor, int access, String name, String descriptor,
                 String hook, String hookDescriptor, boolean dimensions) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
            this.hook = hook;
            this.hookDescriptor = hookDescriptor;
            this.dimensions = dimensions;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            if (dimensions) {
                visitVarInsn(ILOAD, 1);
                visitVarInsn(ILOAD, 2);
            }
            visitMethodInsn(INVOKESTATIC, UI_HOOKS, hook, hookDescriptor, false);
        }
    }

    private static final class SetAAdapter extends AdviceAdapter {
        SetAAdapter(MethodVisitor visitor, int access, String name, String descriptor) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, UI_HOOKS, "beforeSetAnimation",
                    "(Ljava/lang/Object;Lcommon/util/anim/AnimCE;)V", false);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, UI_HOOKS, "afterSetAnimation",
                    "(Ljava/lang/Object;Lcommon/util/anim/AnimCE;)V", false);
        }
    }

    private static final class SetBGate extends AdviceAdapter {
        SetBGate(MethodVisitor visitor, int access, String name, String descriptor) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitVarInsn(ILOAD, 2);
            visitMethodInsn(INVOKESTATIC, UI_HOOKS, "selectAnimation",
                    "(Ljava/lang/Object;Lcommon/util/anim/AnimCE;I)Z", false);
            Label stock = new Label();
            visitJumpInsn(IFEQ, stock);
            visitInsn(RETURN);
            visitLabel(stock);
        }
    }

    private static final class CallbackGate extends AdviceAdapter {
        CallbackGate(MethodVisitor visitor, int access, String name, String descriptor) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
        }

        @Override
        protected void onMethodEnter() {
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, UI_HOOKS, "callBack",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
            Label stock = new Label();
            visitJumpInsn(IFEQ, stock);
            visitInsn(RETURN);
            visitLabel(stock);
        }
    }

    private static final class StoreExit extends AdviceAdapter {
        private final String hook;
        private final String hookDescriptor;
        private final boolean sourceArgument;

        StoreExit(MethodVisitor visitor, int access, String name, String descriptor,
                  String hook, String hookDescriptor, boolean sourceArgument) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
            this.hook = hook;
            this.hookDescriptor = hookDescriptor;
            this.sourceArgument = sourceArgument;
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            if (sourceArgument) visitVarInsn(ALOAD, 2);
            visitMethodInsn(INVOKESTATIC, STORE, hook, hookDescriptor, false);
        }
    }

    private static final class SaverAdapter extends AdviceAdapter {
        private final Set<Label> ioHandlers = new HashSet<Label>();
        private final Patcher patcher;
        private final Label guardedStart = new Label();
        private final Label guardedEnd = new Label();
        private final Label abortHandler = new Label();

        SaverAdapter(MethodVisitor visitor, int access, String name, String descriptor,
                     Patcher patcher) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
            this.patcher = patcher;
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if ("java/io/IOException".equals(type)) {
                ioHandlers.add(handler);
                patcher.saveFailureHandlers++;
            }
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        protected void onMethodEnter() {
            loadSaverFields();
            visitMethodInsn(INVOKESTATIC, STORE, "beforeSave",
                    "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimCI;)V",
                    false);
            visitLabel(guardedStart);
        }

        @Override
        public void visitLabel(Label label) {
            super.visitLabel(label);
            if (!ioHandlers.contains(label)) return;

            loadSaverFields();
            visitMethodInsn(INVOKESTATIC, STORE, "onSaveFailed",
                    "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimCI;)V",
                    false);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode != RETURN) return;
            loadSaverFields();
            visitMethodInsn(INVOKESTATIC, STORE, "afterSave",
                    "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimCI;)V",
                    false);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            visitLabel(guardedEnd);

            visitTryCatchBlock(guardedStart, guardedEnd, abortHandler,
                    "java/lang/Throwable");
            visitLabel(abortHandler);

            loadSaverFields();
            visitMethodInsn(INVOKESTATIC, STORE, "onSaveAborted",
                    "(Lcommon/pack/Source$ResourceLocation;Lcommon/util/anim/AnimCI;)V",
                    false);
            visitInsn(ATHROW);
            super.visitMaxs(maxStack, maxLocals);
        }

        private void loadSaverFields() {
            visitVarInsn(ALOAD, 0);
            visitFieldInsn(GETFIELD, SAVER, "id",
                    "Lcommon/pack/Source$ResourceLocation;");
            visitVarInsn(ALOAD, 0);
            visitFieldInsn(GETFIELD, SAVER, "anim", "Lcommon/util/anim/AnimCI;");
        }
    }

    private static final class Scan {
        boolean anyHook;
        boolean invalidHook;
        final Map<String, Integer> hooks = new HashMap<String, Integer>();

        boolean complete(String target) {
            if (invalidHook) return false;
            if (PAGE.equals(target))
                return count("onBuilt") == 2 && count("onResized") == 1
                        && count("beforeSetAnimation") == 1
                        && count("afterSetAnimation") == 1
                        && count("selectAnimation") == 1 && count("callBack") == 1
                        && count("onLdMousePressed") == 1
                        && count("onLdMouseDragged") == 1
                        && count("onLdMouseReleased") == 1
                        && hooks.size() == 9;
            if (ANIMATION.equals(target))
                return count("onLoaded") == 1 && count("onUnsaved") == 1
                        && count("onRestored") == 1 && count("onCopied") == 1
                        && hooks.size() == 4;
            return SAVER.equals(target) && count("beforeSave") == 1
                    && count("onSaveFailed") == 1 && count("onSaveAborted") == 1
                    && count("afterSave") == 1 && hooks.size() == 4;
        }

        private int count(String name) {
            Integer count = hooks.get(name);
            return count == null ? 0 : count;
        }
    }
}
