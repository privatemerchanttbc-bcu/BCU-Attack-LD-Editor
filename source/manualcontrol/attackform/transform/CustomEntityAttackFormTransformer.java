package manualcontrol.attackform.transform;

import manualcontrol.attackform.AttackFormFeature;
import manualcontrol.attackform.AttackFormLog;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;

public final class CustomEntityAttackFormTransformer implements ClassFileTransformer {

    static final String TARGET = "common/battle/data/CustomEntity";
    static final String FIELD = "attackForms";
    static final String FIELD_DESC = "Lmanualcontrol/attackform/data/AttackFormSet;";
    static final String CARRIER = "manualcontrol/attackform/data/AttackFormCarrier";
    static final String STORE = "manualcontrol/attackform/data/AttackFormStore";
    private static final String IMPORT_DESC = "(Lcommon/battle/data/MaskEntity;)V";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!TARGET.equals(className) || !AttackFormFeature.isReady()) return null;
        if (classBeingRedefined != null) {
            AttackFormFeature.disable("CustomEntity was loaded before its data transformer", null);
            return null;
        }

        try {
            Scan scan = scan(bytes);
            if (scan.complete()) return null;
            if (scan.partial()) {
                AttackFormFeature.disable("partial CustomEntity attack-form patch detected", null);
                return null;
            }
            if (!scan.importMethod || !scan.injectMethod) {
                AttackFormFeature.disable("CustomEntity copy/load anchors are missing", null);
                return null;
            }

            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new Patcher(writer), 0);
            byte[] transformed = writer.toByteArray();
            Scan verify = scan(transformed);
            if (!verify.complete()) {
                AttackFormFeature.disable("CustomEntity transform verification failed", null);
                return null;
            }
            AttackFormLog.info("data carrier installed on CustomEntity");
            return transformed;
        } catch (Throwable error) {
            AttackFormFeature.disable("CustomEntity data transform", error);
            return null;
        }
    }

    private static Scan scan(byte[] bytes) {
        final Scan scan = new Scan();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                if (interfaces != null)
                    for (String itf : interfaces)
                        if (CARRIER.equals(itf)) scan.carrier = true;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (FIELD.equals(name) && FIELD_DESC.equals(descriptor)) {
                    scan.field = true;
                    scan.publicField = (access & Opcodes.ACC_PUBLIC) != 0;
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotationDescriptor,
                                                                 boolean visible) {
                            if ("Lcommon/io/json/JsonField;".equals(annotationDescriptor)) {
                                scan.jsonAnnotation = true;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String key, Object value) {
                                        if ("block".equals(key) && Boolean.TRUE.equals(value))
                                            scan.jsonBlocked = true;
                                    }
                                };
                            }
                            return null;
                        }
                    };
                }
                if (FIELD.equals(name)) scan.conflictingField = true;
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                final boolean importTarget = "importData".equals(name) && IMPORT_DESC.equals(descriptor);
                final boolean injectTarget = "onInjected".equals(name) && "()V".equals(descriptor);
                if (importTarget) scan.importMethod = true;
                if (injectTarget) scan.injectMethod = true;
                if ("attackForm$get".equals(name) && ("()" + FIELD_DESC).equals(descriptor)) {
                    scan.getter = true;
                    scan.publicGetter = (access & Opcodes.ACC_PUBLIC) != 0;
                }
                if ("attackForm$set".equals(name) && ("(" + FIELD_DESC + ")V").equals(descriptor)) {
                    scan.setter = true;
                    scan.publicSetter = (access & Opcodes.ACC_PUBLIC) != 0;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String calledName,
                                                String calledDescriptor, boolean isInterface) {
                        if (importTarget && opcode == Opcodes.INVOKESTATIC && STORE.equals(owner)
                                && "onImport".equals(calledName)
                                && "(Ljava/lang/Object;Ljava/lang/Object;)V".equals(calledDescriptor))
                            scan.importHook = true;
                        if (injectTarget && opcode == Opcodes.INVOKESTATIC && STORE.equals(owner)
                                && "onInjected".equals(calledName)
                                && "(Ljava/lang/Object;)V".equals(calledDescriptor))
                            scan.injectHook = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static final class Patcher extends ClassVisitor {
        private String owner;

        private Patcher(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            owner = name;
            String[] next = interfaces == null ? new String[]{CARRIER}
                    : Arrays.copyOf(interfaces, interfaces.length + 1);
            if (interfaces != null) next[interfaces.length] = CARRIER;
            super.visit(version, access, name, signature, superName, next);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor base = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ("importData".equals(name) && IMPORT_DESC.equals(descriptor)) {
                return new MethodVisitor(Opcodes.ASM9, base) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, STORE, "onImport",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
            if ("onInjected".equals(name) && "()V".equals(descriptor)) {
                return new MethodVisitor(Opcodes.ASM9, base) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, STORE, "onInjected",
                                    "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
            return base;
        }

        @Override
        public void visitEnd() {
            FieldVisitor field = super.visitField(Opcodes.ACC_PUBLIC, FIELD, FIELD_DESC, null, null);
            AnnotationVisitor annotation = field.visitAnnotation("Lcommon/io/json/JsonField;", true);

            annotation.visit("block", true);
            annotation.visitEnd();
            field.visitEnd();

            MethodVisitor getter = super.visitMethod(Opcodes.ACC_PUBLIC, "attackForm$get",
                    "()" + FIELD_DESC, null, null);
            getter.visitCode();
            getter.visitVarInsn(Opcodes.ALOAD, 0);
            getter.visitFieldInsn(Opcodes.GETFIELD, owner, FIELD, FIELD_DESC);
            getter.visitInsn(Opcodes.ARETURN);
            getter.visitMaxs(1, 1);
            getter.visitEnd();

            MethodVisitor setter = super.visitMethod(Opcodes.ACC_PUBLIC, "attackForm$set",
                    "(" + FIELD_DESC + ")V", null, null);
            setter.visitCode();
            setter.visitVarInsn(Opcodes.ALOAD, 0);
            setter.visitVarInsn(Opcodes.ALOAD, 1);
            setter.visitFieldInsn(Opcodes.PUTFIELD, owner, FIELD, FIELD_DESC);
            setter.visitInsn(Opcodes.RETURN);
            setter.visitMaxs(2, 2);
            setter.visitEnd();

            super.visitEnd();
        }
    }

    private static final class Scan {
        boolean field;
        boolean publicField;
        boolean jsonAnnotation;
        boolean jsonBlocked;
        boolean conflictingField;
        boolean carrier;
        boolean getter;
        boolean publicGetter;
        boolean setter;
        boolean publicSetter;
        boolean importMethod;
        boolean injectMethod;
        boolean importHook;
        boolean injectHook;

        boolean complete() {
            return !conflictingField && field && publicField && jsonAnnotation && jsonBlocked
                    && carrier && getter && publicGetter && setter && publicSetter
                    && importMethod && injectMethod && importHook && injectHook;
        }

        boolean partial() {
            int present = (field ? 1 : 0) + (carrier ? 1 : 0) + (getter ? 1 : 0)
                    + (setter ? 1 : 0) + (importHook ? 1 : 0) + (injectHook ? 1 : 0)
                    + (jsonAnnotation ? 1 : 0);
            return conflictingField || present != 0;
        }
    }
}
