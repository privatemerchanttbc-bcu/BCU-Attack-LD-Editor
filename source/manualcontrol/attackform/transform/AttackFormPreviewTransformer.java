package manualcontrol.attackform.transform;

import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class AttackFormPreviewTransformer implements ClassFileTransformer {

    static final String BUFFERED = "page/anim/BufferedAnim";
    static final String GL = "page/anim/GLAnimBox";
    private static final String HOOKS =
            "manualcontrol/attackform/animator/AttackLdEditor";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!BUFFERED.equals(className) && !GL.equals(className)) return null;
        if (!AttackFormFeature.isReady()) return null;
        if (classBeingRedefined != null) {
            AttackFormFeature.disable("LD preview class was loaded before its transformer: "
                    + className, null);
            return null;
        }
        try {
            int before = hookCount(bytes);
            if (before == 1) return null;
            if (before != 0) {
                AttackFormFeature.disable("partial LD preview hook detected in " + className, null);
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            Patcher patcher = new Patcher(writer, className);
            reader.accept(patcher, ClassReader.EXPAND_FRAMES);
            patcher.verify();
            byte[] transformed = writer.toByteArray();
            if (hookCount(transformed) != 1) {
                AttackFormFeature.disable("LD preview transform verification failed in "
                        + className, null);
                return null;
            }
            AttackFormLog.info("Animator LD preview hook installed in " + className);
            return transformed;
        } catch (Throwable error) {
            AttackFormFeature.disable("LD preview transform " + className, error);
            return null;
        }
    }

    private static int hookCount(byte[] bytes) {
        final int[] count = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && HOOKS.equals(owner)
                                && ("onFakePreviewDraw".equals(name)
                                || "onBufferedPreviewDraw".equals(name))) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static final class Patcher extends ClassVisitor {
        private final String target;
        private int methods;
        private int anchors;

        Patcher(ClassVisitor visitor, String target) {
            super(Opcodes.ASM9, visitor);
            this.target = target;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor visitor = super.visitMethod(access, name, descriptor,
                    signature, exceptions);
            if (GL.equals(target) && "drawAnim".equals(name)
                    && "(Lcommon/system/fake/FakeGraphics;)V".equals(descriptor)) {
                methods++;
                return new GlExit(visitor, access, name, descriptor);
            }
            if (BUFFERED.equals(target) && "paint".equals(name)
                    && "(Ljava/awt/Graphics;)V".equals(descriptor)) {
                methods++;
                return new BufferedPaint(visitor, this);
            }
            return visitor;
        }

        void verify() {
            if (methods != 1 || (BUFFERED.equals(target) && anchors != 1))
                throw new IllegalStateException("LD preview anchors changed: target=" + target
                        + " methods=" + methods + " drawImage=" + anchors);
        }
    }

    private static final class GlExit extends AdviceAdapter {
        GlExit(MethodVisitor visitor, int access, String name, String descriptor) {
            super(Opcodes.ASM9, visitor, access, name, descriptor);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (opcode == ATHROW) return;
            visitVarInsn(ALOAD, 0);
            visitVarInsn(ALOAD, 1);
            visitMethodInsn(INVOKESTATIC, HOOKS, "onFakePreviewDraw",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        }
    }

    private static final class BufferedPaint extends MethodVisitor {
        private final Patcher patcher;

        BufferedPaint(MethodVisitor visitor, Patcher patcher) {
            super(Opcodes.ASM9, visitor);
            this.patcher = patcher;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (opcode == Opcodes.INVOKEVIRTUAL && "java/awt/Graphics".equals(owner)
                    && "drawImage".equals(name)
                    && "(Ljava/awt/Image;IILjava/awt/image/ImageObserver;)Z"
                    .equals(descriptor)) {
                patcher.anchors++;
                visitVarInsn(Opcodes.ALOAD, 0);
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "onBufferedPreviewDraw",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            }
        }
    }
}
