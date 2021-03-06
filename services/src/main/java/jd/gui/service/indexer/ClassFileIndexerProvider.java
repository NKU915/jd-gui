/*
 * Copyright (c) 2008-2015 Emmanuel Dupuy
 * This program is made available under the terms of the GPLv3 License.
 */

package jd.gui.service.indexer;

import groovyjarjarasm.asm.*;
import groovyjarjarasm.asm.signature.SignatureReader;
import groovyjarjarasm.asm.signature.SignatureVisitor;
import jd.gui.api.API;
import jd.gui.api.model.Container;
import jd.gui.api.model.Indexes;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Unsafe thread implementation of class file indexer.
 */
public class ClassFileIndexerProvider extends AbstractIndexerProvider {
    protected Set<String> typeDeclarationSet = new HashSet<>();
    protected Set<String> constructorDeclarationSet = new HashSet<>();
    protected Set<String> methodDeclarationSet = new HashSet<>();
    protected Set<String> fieldDeclarationSet = new HashSet<>();
    protected Set<String> typeReferenceSet = new HashSet<>();
    protected Set<String> constructorReferenceSet = new HashSet<>();
    protected Set<String> methodReferenceSet = new HashSet<>();
    protected Set<String> fieldReferenceSet = new HashSet<>();
    protected Set<String> stringSet = new HashSet<>();
    protected Set<String> superTypeNameSet = new HashSet<>();
    protected Set<String> descriptorSet = new HashSet<>();

    protected ClassIndexer classIndexer = new ClassIndexer(
        typeDeclarationSet, constructorDeclarationSet, methodDeclarationSet,
        fieldDeclarationSet, typeReferenceSet, superTypeNameSet, descriptorSet);
    protected SignatureIndexer signatureIndexer = new SignatureIndexer(typeReferenceSet);

    /**
     * @return local + optional external selectors
     */
    public String[] getSelectors() {
        List<String> externalSelectors = getExternalSelectors();

        if (externalSelectors == null) {
            return new String[] { "*:file:*.class" };
        } else {
            int size = externalSelectors.size();
            String[] selectors = new String[size+1];
            externalSelectors.toArray(selectors);
            selectors[size] = "*:file:*.class";
            return selectors;
        }
    }

    /**
     * Index format : @see jd.gui.spi.Indexer
     */
    @SuppressWarnings("unchecked")
    public void index(API api, Container.Entry entry, Indexes indexes) {
        // Cleaning sets...
        typeDeclarationSet.clear();
        constructorDeclarationSet.clear();
        methodDeclarationSet.clear();
        fieldDeclarationSet.clear();
        typeReferenceSet.clear();
        constructorReferenceSet.clear();
        methodReferenceSet.clear();
        fieldReferenceSet.clear();
        stringSet.clear();
        superTypeNameSet.clear();
        descriptorSet.clear();

        InputStream inputStream = null;

        try {
            // Index field, method, interfaces & super type
            ClassReader classReader = new ClassReader(inputStream = entry.getInputStream());
            classReader.accept(classIndexer, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            // Index descriptors
            for (String descriptor : descriptorSet) {
                new SignatureReader(descriptor).accept(signatureIndexer);
            }

            // Index references
            char[] buffer = new char[classReader.getMaxStringLength()];

            for (int i=classReader.getItemCount()-1; i>0; i--) {
                int startIndex = classReader.getItem(i);

                if (startIndex != 0) {
                    int tag = classReader.readByte(startIndex-1);

                    switch (tag) {
                        case 7: // CONSTANT_Class
                            String className = classReader.readUTF8(startIndex, buffer);
                            if (className.startsWith("[")) {
                                new SignatureReader(className).acceptType(signatureIndexer);
                            } else {
                                typeReferenceSet.add(className);
                            }
                            break;
                        case 8: // CONSTANT_String
                            String str = classReader.readUTF8(startIndex, buffer);
                            stringSet.add(str);
                            break;
                        case 9: // CONSTANT_Fieldref
                            int nameAndTypeItem = classReader.readUnsignedShort(startIndex+2);
                            int nameAndTypeIndex = classReader.getItem(nameAndTypeItem);
                            tag = classReader.readByte(nameAndTypeIndex-1);
                            if (tag == 12) { // CONSTANT_NameAndType
                                String fieldName = classReader.readUTF8(nameAndTypeIndex, buffer);
                                fieldReferenceSet.add(fieldName);
                            }
                            break;
                        case 10: // CONSTANT_Methodref:
                        case 11: // CONSTANT_InterfaceMethodref:
                            nameAndTypeItem = classReader.readUnsignedShort(startIndex+2);
                            nameAndTypeIndex = classReader.getItem(nameAndTypeItem);
                            tag = classReader.readByte(nameAndTypeIndex-1);
                            if (tag == 12) { // CONSTANT_NameAndType
                                String methodName = classReader.readUTF8(nameAndTypeIndex, buffer);
                                if ("<init>".equals(methodName)) {
                                    int classItem = classReader.readUnsignedShort(startIndex);
                                    int classIndex = classReader.getItem(classItem);
                                    className = classReader.readUTF8(classIndex, buffer);
                                    constructorReferenceSet.add(className);
                                } else {
                                    methodReferenceSet.add(methodName);
                                }
                            }
                            break;
                    }
                }
            }

            String typeName = classIndexer.name;

            // Append sets to indexes
            addToIndex(indexes, "typeDeclarations", typeDeclarationSet, entry);
            addToIndex(indexes, "constructorDeclarations", constructorDeclarationSet, entry);
            addToIndex(indexes, "methodDeclarations", methodDeclarationSet, entry);
            addToIndex(indexes, "fieldDeclarations", fieldDeclarationSet, entry);
            addToIndex(indexes, "typeReferences", typeReferenceSet, entry);
            addToIndex(indexes, "constructorReferences", constructorReferenceSet, entry);
            addToIndex(indexes, "methodReferences", methodReferenceSet, entry);
            addToIndex(indexes, "fieldReferences", fieldReferenceSet, entry);
            addToIndex(indexes, "strings", stringSet, entry);

            // Populate map [super type name : [sub type name]]
            if (superTypeNameSet.size() > 0) {
                Map<String, Collection> index = indexes.getIndex("subTypeNames");

                for (String superTypeName : superTypeNameSet) {
                    index.get(superTypeName).add(typeName);
                }
            }

        } catch (Exception ignore) {
        } finally {
            if (inputStream != null) {
                try { inputStream.close(); } catch (IOException ignore) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected static void addToIndex(Indexes indexes, String indexName, Set<String> set, Container.Entry entry) {
        if (set.size() > 0) {
            Map<String, Collection> index = indexes.getIndex(indexName);

            for (String key : set) {
                index.get(key).add(entry);
            }
        }
    }

    protected static class ClassIndexer extends ClassVisitor {
        protected Set<String> typeDeclarationSet;
        protected Set<String> constructorDeclarationSet;
        protected Set<String> methodDeclarationSet;
        protected Set<String> fieldDeclarationSet;
        protected Set<String> typeReferenceSet;
        protected Set<String> superTypeNameSet;
        protected Set<String> descriptorSet;

        protected AnnotationIndexer annotationIndexer;
        protected FieldIndexer fieldIndexer;
        protected MethodIndexer methodIndexer;

        protected String name;

        public ClassIndexer(
                Set<String> typeDeclarationSet, Set<String> constructorDeclarationSet,
                Set<String> methodDeclarationSet, Set<String> fieldDeclarationSet,
                Set<String> typeReferenceSet, Set<String> superTypeNameSet, Set<String> descriptorSet) {
            super(Opcodes.ASM5);

            this.typeDeclarationSet = typeDeclarationSet;
            this.constructorDeclarationSet = constructorDeclarationSet;
            this.methodDeclarationSet = methodDeclarationSet;
            this.fieldDeclarationSet = fieldDeclarationSet;
            this.typeReferenceSet = typeReferenceSet;
            this.superTypeNameSet = superTypeNameSet;
            this.descriptorSet = descriptorSet;

            this.annotationIndexer = new AnnotationIndexer(descriptorSet);
            this.fieldIndexer = new FieldIndexer(descriptorSet, annotationIndexer);
            this.methodIndexer = new MethodIndexer(descriptorSet, annotationIndexer);
        }

        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            typeDeclarationSet.add(name);
            superTypeNameSet.add(superName);

            if (interfaces != null) {
                for (int i=interfaces.length-1; i>=0; i--) {
                    superTypeNameSet.add(interfaces[i]);
                }
            }
        }

        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            descriptorSet.add(desc);
            return annotationIndexer;
        }

        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            descriptorSet.add(desc);
            return annotationIndexer;
        }

        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            fieldDeclarationSet.add(name);
            descriptorSet.add(signature==null ? desc : signature);
            return fieldIndexer;
        }

        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if ("<init>".equals(name)) {
                constructorDeclarationSet.add(this.name);
            } else if (! "<clinit>".equals(name)) {
                methodDeclarationSet.add(name);
            }

            descriptorSet.add(signature==null ? desc : signature);

            if (exceptions != null) {
                for (int i=exceptions.length-1; i>=0; i--) {
                    typeReferenceSet.add(exceptions[i]);
                }
            }
            return methodIndexer;
        }
    }

    protected static class SignatureIndexer extends SignatureVisitor {
        protected Set<String> typeReferenceSet;

        SignatureIndexer(Set<String> typeReferenceSet) {
            super(Opcodes.ASM5);
            this.typeReferenceSet = typeReferenceSet;
        }

        public void visitClassType(String name) {
            typeReferenceSet.add(name);
        }
    }

    protected static class AnnotationIndexer extends AnnotationVisitor {
        protected Set<String> descriptorSet;

        public AnnotationIndexer(Set<String> descriptorSet) {
            super(Opcodes.ASM5);
            this.descriptorSet = descriptorSet;
        }

        public void visitEnum(String name, String desc, String value) {
            descriptorSet.add(desc);
        }

        public AnnotationVisitor visitAnnotation(String name, String desc) {
            descriptorSet.add(desc);
            return this;
        }
    }

    protected static class FieldIndexer extends FieldVisitor {
        protected Set<String> descriptorSet;
        protected AnnotationIndexer annotationIndexer;

        public FieldIndexer(Set<String> descriptorSet, AnnotationIndexer annotationInexer) {
            super(Opcodes.ASM5);
            this.descriptorSet = descriptorSet;
            this.annotationIndexer = annotationInexer;
        }

        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            descriptorSet.add(desc);
            return annotationIndexer;
        }

        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            descriptorSet.add(desc);
            return annotationIndexer;
        }
    }

    protected static class MethodIndexer extends MethodVisitor {
        protected Set<String> descriptorSet;
        protected AnnotationIndexer annotationIndexer;

        public MethodIndexer(Set<String> descriptorSet, AnnotationIndexer annotationIndexer) {
            super(Opcodes.ASM5);
            this.descriptorSet = descriptorSet;
            this.annotationIndexer = annotationIndexer;
        }

        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            descriptorSet.add(desc);
            return annotationIndexer;
        }

        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
            descriptorSet.add(desc);
            return annotationIndexer;
        }

        public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
            descriptorSet.add(desc);
            return annotationIndexer;
        }
    }
}
