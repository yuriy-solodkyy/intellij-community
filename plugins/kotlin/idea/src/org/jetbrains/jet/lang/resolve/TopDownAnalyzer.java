package org.jetbrains.jet.lang.resolve;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.JetSemanticServices;
import org.jetbrains.jet.lang.cfg.JetControlFlowProcessor;
import org.jetbrains.jet.lang.cfg.JetFlowInformationProvider;
import org.jetbrains.jet.lang.cfg.pseudocode.*;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.types.*;
import org.jetbrains.jet.lexer.JetTokens;

import java.util.*;

/**
 * @author abreslav
 */
public class TopDownAnalyzer {

    private final Map<JetClass, MutableClassDescriptor> classes = new LinkedHashMap<JetClass, MutableClassDescriptor>();
    private final Map<JetNamespace, WritableScope> namespaceScopes = new LinkedHashMap<JetNamespace, WritableScope>();
    private final Map<JetDeclaration, FunctionDescriptor> functions = new LinkedHashMap<JetDeclaration, FunctionDescriptor>();
    private final Map<JetProperty, PropertyDescriptor> properties = new LinkedHashMap<JetProperty, PropertyDescriptor>();
    private final Map<JetDeclaration, WritableScope> declaringScopes = new HashMap<JetDeclaration, WritableScope>();
    private final Multimap<DeclarationDescriptor, PropertyDescriptor> declaringScopesToProperties = ArrayListMultimap.create();

    private final JetSemanticServices semanticServices;
    private final ClassDescriptorResolver classDescriptorResolver;
    private final BindingTraceContext trace;
    private final JetControlFlowDataTraceFactory flowDataTraceFactory;
    private boolean readyToProcessExpressions = false;
    private final BindingTraceAdapter traceForConstructors;

    public TopDownAnalyzer(JetSemanticServices semanticServices, @NotNull BindingTraceContext bindingTrace, @NotNull JetControlFlowDataTraceFactory flowDataTraceFactory) {
        this.semanticServices = semanticServices;
        this.classDescriptorResolver = new ClassDescriptorResolver(semanticServices, bindingTrace);
        this.trace = bindingTrace;
        this.flowDataTraceFactory = flowDataTraceFactory;
        this.traceForConstructors = new BindingTraceAdapter(trace) {
            @Override
            public void recordReferenceResolution(@NotNull JetReferenceExpression expression, @NotNull DeclarationDescriptor descriptor) {
                super.recordReferenceResolution(expression, descriptor);
                if (expression instanceof JetSimpleNameExpression) {
                    JetSimpleNameExpression simpleNameExpression = (JetSimpleNameExpression) expression;
                    if (simpleNameExpression.getReferencedNameElementType() == JetTokens.FIELD_IDENTIFIER) {
                        if (!trace.hasBackingField((PropertyDescriptor) descriptor)) {
                            TopDownAnalyzer.this.semanticServices.getErrorHandler().genericError(expression.getNode(), "This property does not have a backing field");
                        }
                    }
                }
            }
        };
    }

    public TopDownAnalyzer(JetSemanticServices semanticServices, @NotNull BindingTraceContext bindingTrace) {
        this(semanticServices, bindingTrace, JetControlFlowDataTraceFactory.EMPTY);
    }

    public void process(@NotNull JetScope outerScope, @NotNull JetDeclaration declaration) {
        process(outerScope, Collections.singletonList(declaration));
    }

    public void process(@NotNull JetScope outerScope, @NotNull List<JetDeclaration> declarations) {
        final WritableScope toplevelScope = semanticServices.createWritableScope(outerScope, outerScope.getContainingDeclaration()); // TODO ?!
        trace.setToplevelScope(toplevelScope); // TODO : this is a hack
        collectTypeDeclarators(toplevelScope, declarations);
        resolveTypeDeclarations();
        processBehaviorDeclarators(toplevelScope, declarations);
        readyToProcessExpressions = true;
        resolveBehaviorDeclarationBodies();
    }

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void collectTypeDeclarators(
            @NotNull final WritableScope declaringScope,
            List<JetDeclaration> declarations) {
        for (JetDeclaration declaration : declarations) {
            declaration.accept(new JetVisitor() {
                @Override
                public void visitClass(JetClass klass) {
                    WritableScope classScope = processClass(declaringScope, klass);
                    collectTypeDeclarators(classScope, klass.getDeclarations());
                }

                @Override
                public void visitNamespace(JetNamespace namespace) {
                    List<JetImportDirective> importDirectives = namespace.getImportDirectives();

                    String name = namespace.getName();
                    if (name == null) {
                        name = "<no name provided>";
                    }
                    NamespaceDescriptor namespaceDescriptor = declaringScope.getDeclaredNamespace(name);
                    if (namespaceDescriptor == null) {
                        namespaceDescriptor = new NamespaceDescriptor(
                                declaringScope.getContainingDeclaration(),
                                Collections.<Attribute>emptyList(), // TODO
                                name
                        );
                        namespaceDescriptor.initialize(semanticServices.createWritableScope(JetScope.EMPTY, namespaceDescriptor));
                        declaringScope.addNamespace(namespaceDescriptor);
                        trace.recordDeclarationResolution(namespace, namespaceDescriptor);
                    }

                    WritableScope namespaceScope = new WriteThroughScope(declaringScope, (WritableScope) namespaceDescriptor.getMemberScope());
                    namespaceScopes.put(namespace, namespaceScope);

                    for (JetImportDirective importDirective : importDirectives) {
                        if (importDirective.isAbsoluteInRootNamespace()) {
                            throw new UnsupportedOperationException();
                        }
                        if (importDirective.isAllUnder()) {
                            JetExpression importedReference = importDirective.getImportedReference();
                            if (importedReference != null) {
                                JetType type = semanticServices.getTypeInferrer(trace, JetFlowInformationProvider.THROW_EXCEPTION).getType(namespaceScope, importedReference, false);
                                if (type != null) {
                                    namespaceScope.importScope(type.getMemberScope());
                                }
                            }
                        } else {
                            throw new UnsupportedOperationException();
                        }
                    }

                    collectTypeDeclarators(namespaceScope, namespace.getDeclarations());
                }

                @Override
                public void visitTypedef(JetTypedef typedef) {
                    processTypeDef(typedef);
                }

                @Override
                public void visitDeclaration(JetDeclaration dcl) {
                    // Other declarations do not declare visible types
                }
            });
        }
    }

    private WritableScope processClass(@NotNull WritableScope declaringScope, JetClass klass) {
        MutableClassDescriptor mutableClassDescriptor = new MutableClassDescriptor(semanticServices, declaringScope.getContainingDeclaration(), declaringScope);
        mutableClassDescriptor.setName(JetPsiUtil.safeName(klass.getName()));

        declaringScope.addClassifierDescriptor(mutableClassDescriptor);

        classes.put(klass, mutableClassDescriptor);
        declaringScopes.put(klass, declaringScope);

        return mutableClassDescriptor.getWritableUnsubstitutedMemberScope();
    }

    private void processExtension(JetExtension extension) {
        throw new UnsupportedOperationException(extension.getText()); // TODO
    }

    private void processTypeDef(@NotNull JetTypedef typedef) {
        throw new UnsupportedOperationException(typedef.getText()); // TODO
    }

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void resolveTypeDeclarations() {
        for (Map.Entry<JetClass, MutableClassDescriptor> entry : classes.entrySet()) {
            JetClass jetClass = entry.getKey();
            MutableClassDescriptor descriptor = entry.getValue();
            classDescriptorResolver.resolveMutableClassDescriptor(declaringScopes.get(jetClass), jetClass, descriptor);
        }
    }

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void processBehaviorDeclarators(@NotNull final WritableScope declaringScope, List<JetDeclaration> declarations) {
        for (JetDeclaration declaration : declarations) {
            declaration.accept(new JetVisitor() {
                @Override
                public void visitClass(JetClass klass) {
                    MutableClassDescriptor mutableClassDescriptor = classes.get(klass);
                    processPrimaryConstructor(mutableClassDescriptor, klass);
                    processBehaviorDeclarators(mutableClassDescriptor.getWritableUnsubstitutedMemberScope(), klass.getDeclarations());
                }

                @Override
                public void visitClassObject(JetClassObject classObject) {
                    processClassObject(classObject);
                    processBehaviorDeclarators(declaringScope, classObject.getObject().getDeclarations());
                }

                @Override
                public void visitNamespace(JetNamespace namespace) {
                    WritableScope namespaceScope = namespaceScopes.get(namespace);
                    processBehaviorDeclarators(namespaceScope, namespace.getDeclarations());
                }

                @Override
                public void visitFunction(JetFunction function) {
                    processFunction(declaringScope, function);
                }

                @Override
                public void visitProperty(JetProperty property) {
                    processProperty(declaringScope, property);
                }

                @Override
                public void visitConstructor(JetConstructor constructor) {
                    DeclarationDescriptor containingDeclaration = declaringScope.getContainingDeclaration();
                    if (containingDeclaration instanceof ClassDescriptor) {
                        processConstructor((MutableClassDescriptor) containingDeclaration, constructor);
                    }
                    else {
                        semanticServices.getErrorHandler().genericError(constructor.getNode(), "Constructors are only allowed inside classes");
                    }
                }

                @Override
                public void visitDeclaration(JetDeclaration dcl) {
                    semanticServices.getErrorHandler().genericError(dcl.getNode(), "Unsupported declaration: " + dcl); // TODO
                }
            });
        }

    }

    private void processPrimaryConstructor(MutableClassDescriptor classDescriptor, JetClass klass) {
        // TODO : not all the parameters are real properties
        WritableScope memberScope = classDescriptor.getWritableUnsubstitutedMemberScope(); // TODO : this is REALLY questionable
        ConstructorDescriptor constructorDescriptor = classDescriptorResolver.resolvePrimaryConstructor(memberScope, classDescriptor, klass);
        for (JetParameter parameter : klass.getPrimaryConstructorParameters()) {
            VariableDescriptor propertyDescriptor = classDescriptorResolver.resolvePrimaryConstructorParameterToAProperty(
                    classDescriptor,
                    memberScope,
                    parameter
            );
            memberScope.addVariableDescriptor(
                    propertyDescriptor);
        }
        if (constructorDescriptor != null) {
            classDescriptor.addConstructor(constructorDescriptor);
        }
    }

    private void processConstructor(MutableClassDescriptor classDescriptor, JetConstructor constructor) {
        ConstructorDescriptor constructorDescriptor = classDescriptorResolver.resolveConstructorDescriptor(classDescriptor.getWritableUnsubstitutedMemberScope(), classDescriptor, constructor, false);
        classDescriptor.addConstructor(constructorDescriptor);
        functions.put(constructor, constructorDescriptor);
        declaringScopes.put(constructor, classDescriptor.getWritableUnsubstitutedMemberScope());
    }

    private void processFunction(@NotNull WritableScope declaringScope, JetFunction function) {
        declaringScopes.put(function, declaringScope);
        FunctionDescriptor descriptor = classDescriptorResolver.resolveFunctionDescriptor(declaringScope.getContainingDeclaration(), declaringScope, function);
        declaringScope.addFunctionDescriptor(descriptor);
        functions.put(function, descriptor);
    }

    private void processProperty(WritableScope declaringScope, JetProperty property) {
        declaringScopes.put(property, declaringScope);
        PropertyDescriptor descriptor = classDescriptorResolver.resolvePropertyDescriptor(declaringScope.getContainingDeclaration(), declaringScope, property);
        declaringScope.addVariableDescriptor(descriptor);
        declaringScopesToProperties.put(declaringScope.getContainingDeclaration(), descriptor);
        properties.put(property, descriptor);
    }

    private void processClassObject(JetClassObject classObject) {
        semanticServices.getErrorHandler().genericError(classObject.getNode(), "Class objects are not supported yet"); // TODO
    }

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void resolveBehaviorDeclarationBodies() {
        resolveDelegationSpecifierLists();


        //TODO : anonymous initializers

        resolvePropertyDeclarationBodies();
        resolveFunctionDeclarationBodies();
    }

    private void resolveDelegationSpecifierLists() {
        final JetTypeInferrer typeInferrer = semanticServices.getTypeInferrer(trace, JetFlowInformationProvider.NONE);
        for (Map.Entry<JetClass, MutableClassDescriptor> entry : classes.entrySet()) {
            final JetClass declaration = entry.getKey();
            final MutableClassDescriptor descriptor = entry.getValue();

            for (JetDelegationSpecifier delegationSpecifier : declaration.getDelegationSpecifiers()) {
                delegationSpecifier.accept(new JetVisitor() {
                    @Override
                    public void visitDelegationByExpressionSpecifier(JetDelegatorByExpressionSpecifier specifier) {
                        JetExpression delegateExpression = specifier.getDelegateExpression();
                        if (delegateExpression != null) {
                            JetType type = typeInferrer.getType(descriptor.getWritableUnsubstitutedMemberScope(), delegateExpression, false);
                            JetType supertype = trace.resolveTypeReference(specifier.getTypeReference());
                            if (type != null && !semanticServices.getTypeChecker().isSubtypeOf(type, supertype)) { // TODO : Convertible?
                                semanticServices.getErrorHandler().typeMismatch(delegateExpression, supertype, type);
                            }
                        }
                    }

                    @Override
                    public void visitDelegationToSuperCallSpecifier(JetDelegatorToSuperCall call) {
                        JetTypeReference typeReference = call.getTypeReference();
                        if (typeReference != null) {
                            typeInferrer.checkConstructorCall(descriptor.getWritableUnsubstitutedMemberScope(), typeReference, call);
                        }
                    }

                    @Override
                    public void visitDelegationToSuperClassSpecifier(JetDelegatorToSuperClass specifier) {
                        if (declaration.getPrimaryConstructorParameterList() != null) {
                            semanticServices.getErrorHandler().genericError(specifier.getNode(), "Constructor parameters required in initializer");
                        }
                    }

                    @Override
                    public void visitDelegationToThisCall(JetDelegatorToThisCall thisCall) {
                        throw new IllegalStateException("This-calls should be prohibitied by the parser");
                    }

                    @Override
                    public void visitJetElement(JetElement elem) {
                        throw new UnsupportedOperationException(elem.getText() + " : " + elem);
                    }
                });
            }
        }
    }

    private void resolvePropertyDeclarationBodies() {
        for (Map.Entry<JetProperty, PropertyDescriptor> entry : properties.entrySet()) {
            JetProperty declaration = entry.getKey();
            final PropertyDescriptor propertyDescriptor = entry.getValue();
            WritableScope declaringScope = declaringScopes.get(declaration);

            JetExpression initializer = declaration.getInitializer();
            if (initializer != null) {
                JetFlowInformationProvider flowInformationProvider = computeFlowData(declaration, initializer);
                JetTypeInferrer typeInferrer = semanticServices.getTypeInferrer(trace, flowInformationProvider);
                JetType type = typeInferrer.getType(declaringScope, initializer, false);

                JetType expectedType;
                PropertySetterDescriptor setter = propertyDescriptor.getSetter();
                if (setter != null) {
                    expectedType = setter.getUnsubstitutedReturnType();
                }
                else {
                    expectedType = propertyDescriptor.getInType();
                    if (expectedType == null) {
                        expectedType = propertyDescriptor.getOutType();
                    }
                }
                if (type != null && expectedType != null
                    && !semanticServices.getTypeChecker().isConvertibleTo(type, expectedType)) {
                    semanticServices.getErrorHandler().typeMismatch(initializer, expectedType, type);
                }
            }

            BindingTraceAdapter fieldAccessTrackingTrace = new BindingTraceAdapter(trace) {
                @Override
                public void recordReferenceResolution(@NotNull JetReferenceExpression expression, @NotNull DeclarationDescriptor descriptor) {
                    super.recordReferenceResolution(expression, descriptor);
                    if (expression instanceof JetSimpleNameExpression) {
                        JetSimpleNameExpression simpleNameExpression = (JetSimpleNameExpression) expression;
                        if (simpleNameExpression.getReferencedNameElementType() == JetTokens.FIELD_IDENTIFIER) {
                            // This check may be considered redundant as long as $x is only accessible from accessors to $x
                            if (descriptor == propertyDescriptor) { // TODO : original?
                                recordFieldAccessFromAccessor(propertyDescriptor);
                            }
                        }
                    }
                }
            };

            WritableScope accessorScope = semanticServices.createWritableScope(declaringScope, declaringScope.getContainingDeclaration());
            accessorScope.addPropertyDescriptorByFieldName("$" + propertyDescriptor.getName(), propertyDescriptor);

            JetPropertyAccessor getter = declaration.getGetter();
            PropertyGetterDescriptor getterDescriptor = propertyDescriptor.getGetter();
            if (getter != null && getterDescriptor != null) {
                resolveFunctionBody(fieldAccessTrackingTrace, getter, getterDescriptor, accessorScope);
            }

            JetPropertyAccessor setter = declaration.getSetter();
            PropertySetterDescriptor setterDescriptor = propertyDescriptor.getSetter();
            if (setter != null && setterDescriptor != null) {
                resolveFunctionBody(fieldAccessTrackingTrace, setter, setterDescriptor, accessorScope);
            }

            if (!declaration.isVar() && initializer != null && !trace.hasBackingField(propertyDescriptor)) {
                semanticServices.getErrorHandler().genericError(initializer.getNode(), "Initializer is not allowed here because this property has no setter and no backing field either");
            }
        }
    }

    private void resolveFunctionDeclarationBodies() {
        for (Map.Entry<JetDeclaration, FunctionDescriptor> entry : functions.entrySet()) {
            JetDeclaration declaration = entry.getKey();
            FunctionDescriptor descriptor = entry.getValue();

            WritableScope declaringScope = declaringScopes.get(declaration);
            assert declaringScope != null;

            if (declaration instanceof JetFunction) {
                resolveFunctionBody(trace, (JetFunction) declaration, (FunctionDescriptorImpl) descriptor, declaringScope);
            }
            else if (declaration instanceof JetConstructor) {
                resolveConstructorBody((JetConstructor) declaration, descriptor, declaringScope);
            }
            else {
                throw new UnsupportedOperationException(); // TODO
            }

            assert descriptor.getUnsubstitutedReturnType() != null;
        }
    }

    private void resolveConstructorBody(JetConstructor declaration, final FunctionDescriptor descriptor, final WritableScope declaringScope) {
        WritableScope constructorScope = semanticServices.createWritableScope(declaringScope, declaringScope.getContainingDeclaration());
        for (PropertyDescriptor propertyDescriptor : declaringScopesToProperties.get(descriptor.getContainingDeclaration())) {
            constructorScope.addPropertyDescriptorByFieldName("$" + propertyDescriptor.getName(), propertyDescriptor);
        }
        final JetScope functionInnerScope = FunctionDescriptorUtil.getFunctionInnerScope(constructorScope, descriptor, semanticServices);
        final JetTypeInferrer typeInferrerForInitializers = semanticServices.getTypeInferrer(traceForConstructors, JetFlowInformationProvider.NONE);
        for (JetDelegationSpecifier initializer : declaration.getInitializers()) {
            // TODO : check that the type being referenced is actually a supertype
            initializer.accept(new JetVisitor() {
                @Override
                public void visitDelegationToSuperCallSpecifier(JetDelegatorToSuperCall call) {
                    JetTypeReference typeReference = call.getTypeReference();
                    if (typeReference != null) {
                        typeInferrerForInitializers.checkConstructorCall(functionInnerScope, typeReference, call);
                    }
                }

                @Override
                public void visitDelegationToThisCall(JetDelegatorToThisCall call) {
                    // TODO : check that there's no recursion in this() calls
                    // TODO : check: if a this() call is present, no other initializers are allowed
                    ClassDescriptor classDescriptor = (ClassDescriptor) descriptor.getContainingDeclaration();
                    typeInferrerForInitializers.checkClassConstructorCall(
                            functionInnerScope,
                            call.getThisReference(),
                            classDescriptor,
                            classDescriptor.getDefaultType(),
                            call);
                }

                @Override
                public void visitDelegationByExpressionSpecifier(JetDelegatorByExpressionSpecifier specifier) {
                    semanticServices.getErrorHandler().genericError(specifier.getNode(), "'by'-clause is only supported for primary constructors");
                }

                @Override
                public void visitDelegationToSuperClassSpecifier(JetDelegatorToSuperClass specifier) {
                    semanticServices.getErrorHandler().genericError(specifier.getNode(), "Constructor parameters required");
                }

                @Override
                public void visitDelegationSpecifier(JetDelegationSpecifier specifier) {
                    throw new IllegalStateException();
                }
            });
        }
        JetExpression bodyExpression = declaration.getBodyExpression();
        if (bodyExpression != null) {
            computeFlowData(declaration, bodyExpression);
            JetFlowInformationProvider flowInformationProvider = computeFlowData(declaration, bodyExpression);
            JetTypeInferrer typeInferrer = semanticServices.getTypeInferrer(traceForConstructors, flowInformationProvider);

            typeInferrer.getType(functionInnerScope, bodyExpression, true);
        }
    }

    private void resolveFunctionBody(
            @NotNull BindingTrace trace,
            @NotNull JetDeclarationWithBody function,
            @NotNull MutableFunctionDescriptor functionDescriptor,
            @NotNull WritableScope declaringScope) {
        JetExpression bodyExpression = function.getBodyExpression();
        if (bodyExpression != null) {
            JetFlowInformationProvider flowInformationProvider = computeFlowData(function.asElement(), bodyExpression);
            JetTypeInferrer typeInferrer = semanticServices.getTypeInferrer(trace, flowInformationProvider);

            assert readyToProcessExpressions : "Must be ready collecting types";

            if (functionDescriptor.isReturnTypeSet()) {
                typeInferrer.checkFunctionReturnType(declaringScope, function, functionDescriptor);
            }
            else {
                JetType returnType = typeInferrer.getFunctionReturnType(declaringScope, function, functionDescriptor);
                if (returnType == null) {
                    returnType = ErrorUtils.createErrorType("Unable to infer body type");
                }
                functionDescriptor.setUnsubstitutedReturnType(returnType);
            }

            List<JetElement> unreachableElements = new ArrayList<JetElement>();
            flowInformationProvider.collectUnreachableExpressions(function.asElement(), unreachableElements);

            // This is needed in order to highlight only '1 < 2' and not '1', '<' and '2' as well
            Set<JetElement> rootElements = JetPsiUtil.findRootExpressions(unreachableElements);

            // TODO : (return 1) || (return 2) -- only || and right of it is unreachable
            // TODO : try {return 1} finally {return 2}. Currently 'return 1' is reported as unreachable,
            //        though it'd better be reported more specifically

            for (JetElement element : rootElements) {
                semanticServices.getErrorHandler().genericError(element.getNode(), "Unreachable code");
            }
        }
        else {
            if (!functionDescriptor.isReturnTypeSet()) {
                semanticServices.getErrorHandler().genericError(function.asElement().getNode(), "This function must either declare a return type or have a body element");
                functionDescriptor.setUnsubstitutedReturnType(ErrorUtils.createErrorType("No type, no body"));
            }
        }
    }

    private JetFlowInformationProvider computeFlowData(@NotNull JetElement declaration, @NotNull JetExpression bodyExpression) {
        final JetPseudocodeTrace pseudocodeTrace = flowDataTraceFactory.createTrace(declaration);
        final Map<JetElement, Pseudocode> pseudocodeMap = new HashMap<JetElement, Pseudocode>();
        final Map<JetElement, Instruction> representativeInstructions = new HashMap<JetElement, Instruction>();
        JetPseudocodeTrace wrappedTrace = new JetPseudocodeTrace() {
            @Override
            public void recordControlFlowData(@NotNull JetElement element, @NotNull Pseudocode pseudocode) {
                pseudocodeTrace.recordControlFlowData(element, pseudocode);
                pseudocodeMap.put(element, pseudocode);
            }

            @Override
            public void recordRepresentativeInstruction(@NotNull JetElement element, @NotNull Instruction instruction) {
                Instruction oldValue = representativeInstructions.put(element, instruction);
//                assert oldValue == null : element.getText();
            }

            @Override
            public void close() {
                pseudocodeTrace.close();
                for (Pseudocode pseudocode : pseudocodeMap.values()) {
                    pseudocode.postProcess();
                }
            }
        };
        JetControlFlowInstructionsGenerator instructionsGenerator = new JetControlFlowInstructionsGenerator(wrappedTrace);
        new JetControlFlowProcessor(semanticServices, trace, instructionsGenerator).generate(declaration, bodyExpression);
        wrappedTrace.close();
        return new JetFlowInformationProvider() {
            @Override
            public void collectReturnedInformation(@NotNull JetElement subroutine, @NotNull Collection<JetExpression> returnedExpressions, @NotNull Collection<JetElement> elementsReturningUnit) {
                Pseudocode pseudocode = pseudocodeMap.get(subroutine);
                assert pseudocode != null;

                SubroutineExitInstruction exitInstruction = pseudocode.getExitInstruction();
                processPreviousInstructions(exitInstruction, new HashSet<Instruction>(), returnedExpressions, elementsReturningUnit);
            }

            @Override
            public void collectUnreachableExpressions(@NotNull JetElement subroutine, @NotNull Collection<JetElement> unreachableElements) {
                Pseudocode pseudocode = pseudocodeMap.get(subroutine);
                assert pseudocode != null;

                SubroutineEnterInstruction enterInstruction = pseudocode.getEnterInstruction();
                Set<Instruction> visited = new HashSet<Instruction>();
                collectReachable(enterInstruction, visited);

                for (Instruction instruction : pseudocode.getInstructions()) {
                    if (!visited.contains(instruction) &&
                        instruction instanceof JetElementInstruction &&
                        // TODO : do {return} while (1 > a)
                        !(instruction instanceof ReadUnitValueInstruction)) {
                        unreachableElements.add(((JetElementInstruction) instruction).getElement());
                    }
                }
            }

            @Override
            public void collectDominatedExpressions(@NotNull JetExpression dominator, @NotNull Collection<JetElement> dominated) {
                Instruction dominatorInstruction = representativeInstructions.get(dominator);
                if (dominatorInstruction == null) {
                    return;
                }
                SubroutineEnterInstruction enterInstruction = dominatorInstruction.getOwner().getEnterInstruction();

                Set<Instruction> reachable = new HashSet<Instruction>();
                collectReachable(enterInstruction, reachable);

                Set<Instruction> reachableWithDominatorProhibited = new HashSet<Instruction>();
                reachableWithDominatorProhibited.add(dominatorInstruction);
                collectReachable(enterInstruction, reachableWithDominatorProhibited);

                for (Instruction instruction : reachable) {
                    if (instruction instanceof JetElementInstruction
                            && reachable.contains(instruction)
                            && !reachableWithDominatorProhibited.contains(instruction)) {
                        JetElementInstruction elementInstruction = (JetElementInstruction) instruction;
                        dominated.add(elementInstruction.getElement());
                    }
                }
            }
        };
    }

    private void collectReachable(Instruction current, Set<Instruction> visited) {
        if (!visited.add(current)) return;

        for (Instruction nextInstruction : current.getNextInstructions()) {
            collectReachable(nextInstruction, visited);
        }
    }

    private void processPreviousInstructions(Instruction previousFor, final Set<Instruction> visited, final Collection<JetExpression> returnedExpressions, final Collection<JetElement> elementsReturningUnit) {
        if (!visited.add(previousFor)) return;

        Collection<Instruction> previousInstructions = previousFor.getPreviousInstructions();
        InstructionVisitor visitor = new InstructionVisitor() {
            @Override
            public void visitReadValue(ReadValueInstruction instruction) {
                returnedExpressions.add((JetExpression) instruction.getElement());
            }

            @Override
            public void visitReturnValue(ReturnValueInstruction instruction) {
                processPreviousInstructions(instruction, visited, returnedExpressions, elementsReturningUnit);
            }

            @Override
            public void visitReturnNoValue(ReturnNoValueInstruction instruction) {
                elementsReturningUnit.add(instruction.getElement());
            }

            @Override
            public void visitSubroutineEnter(SubroutineEnterInstruction instruction) {
                elementsReturningUnit.add(instruction.getSubroutine());
            }

            @Override
            public void visitUnsupportedElementInstruction(UnsupportedElementInstruction instruction) {
                semanticServices.getErrorHandler().genericError(instruction.getElement().getNode(), "Unsupported by control-flow builder " + instruction.getElement());
            }

            @Override
            public void visitWriteValue(WriteValueInstruction writeValueInstruction) {
                elementsReturningUnit.add(writeValueInstruction.getElement());
            }

            @Override
            public void visitJump(AbstractJumpInstruction instruction) {
                processPreviousInstructions(instruction, visited, returnedExpressions, elementsReturningUnit);
            }

            @Override
            public void visitReadUnitValue(ReadUnitValueInstruction instruction) {
                returnedExpressions.add((JetExpression) instruction.getElement());
            }

            @Override
            public void visitInstruction(Instruction instruction) {
                if (instruction instanceof JetElementInstructionImpl) {
                    JetElementInstructionImpl elementInstruction = (JetElementInstructionImpl) instruction;
                    semanticServices.getErrorHandler().genericError(elementInstruction.getElement().getNode(), "Unsupported by control-flow builder " + elementInstruction.getElement());
                }
                else {
                    throw new UnsupportedOperationException(instruction.toString());
                }
            }
        };
        for (Instruction previousInstruction : previousInstructions) {
            previousInstruction.accept(visitor);
        }
    }

}
