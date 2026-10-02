package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time stateful-trait composition.
 *
 * Traits have no runtime object identity. This pass expands trait state,
 * behavior, and interface obligations into the consuming class before the
 * ordinary type/ownership/capability/runtime passes execute. Struct hosts remain structs after flattening.
 */
public final class TraitComposer {
    private TraitComposer() { }

    public static Ast.Program compose(Ast.Program program) {
        return new Composer(program).compose();
    }

    private record TraitBinding(String module, Ast.TraitDecl declaration, String identity) { }
    private record FieldEntry(Ast.FieldDecl field, String origin) { }
    private record MethodEntry(Ast.MethodDecl method, String origin) { }

    private static final class Material {
        private final LinkedHashMap<String, FieldEntry> fields = new LinkedHashMap<>();
        private final LinkedHashMap<String, MethodEntry> methods = new LinkedHashMap<>();
        private final LinkedHashMap<String, Ast.TypeRef> interfaces = new LinkedHashMap<>();
    }

    private static final class Composer {
        private final Ast.Program program;
        private final Map<String, TraitBinding> qualifiedTraits = new LinkedHashMap<>();
        private final Map<String, TraitBinding> unqualifiedTraits = new LinkedHashMap<>();
        private final Set<String> ambiguousTraits = new HashSet<>();
        private final Map<String, Material> materialCache = new HashMap<>();
        private final ArrayDeque<Map<String, TraitBinding>> localTraitScopes = new ArrayDeque<>();
        private final IdentityHashMap<Ast.TraitDecl, String> localTraitIdentities = new IdentityHashMap<>();
        private int nextLocalTraitIdentity = 1;

        private Composer(Ast.Program program) {
            this.program = program;
            indexTraits();
            validateTypeNamespaces();
            validateTraitTypeBarriers();
        }

        private void validateTypeNamespaces() {
            for (Ast.ModuleDecl module : program.modules()) {
                LinkedHashMap<String, String> names = new LinkedHashMap<>();
                for (Ast.Decl declaration : module.declarations()) {
                    String name = null;
                    String kind = null;
                    if (declaration instanceof Ast.ClassDecl klass) {
                        name = klass.name();
                        kind = klass.isStruct() ? "struct" : "class";
                    } else if (declaration instanceof Ast.InterfaceDecl iface) {
                        name = iface.name();
                        kind = "interface";
                    } else if (declaration instanceof Ast.TraitDecl trait) {
                        name = trait.name();
                        kind = "trait";
                    } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                        name = alias.name();
                        kind = "type alias";
                    }
                    if (name == null) continue;
                    String previous = names.putIfAbsent(name, kind);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "type name '" + module.name() + "." + name + "' is declared as both "
                                        + previous + " and " + kind + "; class/struct/interface/trait/type names share one namespace");
                    }
                }
            }
        }

        private void validateTraitTypeBarriers() {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    validateDeclarationTypes(module.name(), declaration, Set.of());
                }
            }
        }

        private void validateDeclarationTypes(
                String moduleName,
                Ast.Decl declaration,
                Set<String> outerGenerics) {
            if (declaration instanceof Ast.FunctionDecl fn) {
                Set<String> generics = withGenerics(outerGenerics, fn.genericParameters());
                for (Ast.Param param : fn.parameters()) {
                    rejectTraitType(moduleName, param.type(), generics, "parameter " + fn.name() + "." + param.name());
                }
                rejectTraitType(moduleName, fn.returnType(), generics, "return type of " + fn.name());
                validateStatementsTypes(moduleName, fn.body(), generics);
                return;
            }
            if (declaration instanceof Ast.InitDecl init) {
                validateStatementsTypes(moduleName, init.body(), outerGenerics);
                return;
            }
            if (declaration instanceof Ast.ClassDecl klass) {
                Set<String> generics = withGenerics(outerGenerics, klass.genericParameters());
                for (Ast.TypeRef parent : klass.parents()) {
                    rejectTraitType(moduleName, parent, generics, "base type of " + klass.name());
                }
                for (Ast.TypeRef iface : klass.interfaces()) {
                    rejectTraitType(moduleName, iface, generics, "interface conformance of " + klass.name());
                }
                for (Ast.TypeRef trait : klass.traits()) {
                    rejectTraitArguments(moduleName, trait, generics, "trait composition of " + klass.name());
                }
                for (Ast.FieldDecl field : klass.fields()) {
                    rejectTraitType(moduleName, field.type(), generics, "field " + klass.name() + "." + field.name());
                    validateExprTypes(moduleName, field.initializer(), generics);
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    Set<String> methodGenerics = withGenerics(generics, method.genericParameters());
                    rejectTraitType(moduleName, method.explicitReceiverType(), methodGenerics,
                            "receiver of " + klass.name() + "." + method.name());
                    for (Ast.Param param : method.parameters()) {
                        rejectTraitType(moduleName, param.type(), methodGenerics,
                                "parameter " + klass.name() + "." + method.name() + "." + param.name());
                    }
                    rejectTraitType(moduleName, method.returnType(), methodGenerics,
                            "return type of " + klass.name() + "." + method.name());
                    validateStatementsTypes(moduleName, method.body(), methodGenerics);
                }
                return;
            }
            if (declaration instanceof Ast.InterfaceDecl iface) {
                Set<String> generics = withGenerics(outerGenerics, iface.genericParameters());
                for (Ast.TypeRef parent : iface.parents()) {
                    rejectTraitType(moduleName, parent, generics, "parent interface of " + iface.name());
                }
                for (Ast.InterfaceMember member : iface.members()) {
                    Ast.InterfaceFunctionDecl method = (Ast.InterfaceFunctionDecl) member;
                    Set<String> methodGenerics = withGenerics(generics, method.genericParameters());
                    for (Ast.Param param : method.parameters()) {
                        rejectTraitType(moduleName, param.type(), methodGenerics,
                                "interface parameter " + iface.name() + "." + method.name() + "." + param.name());
                    }
                    rejectTraitType(moduleName, method.returnType(), methodGenerics,
                            "interface return type " + iface.name() + "." + method.name());
                }
                return;
            }
            if (declaration instanceof Ast.TraitDecl trait) {
                Set<String> generics = withGenerics(outerGenerics, trait.genericParameters());
                for (Ast.TypeRef iface : trait.interfaces()) {
                    rejectTraitType(moduleName, iface, generics, "interface conformance of trait " + trait.name());
                }
                for (Ast.TypeRef nested : trait.traits()) {
                    rejectTraitArguments(moduleName, nested, generics, "nested trait composition of " + trait.name());
                }
                for (Ast.FieldDecl field : trait.fields()) {
                    rejectTraitType(moduleName, field.type(), generics, "trait field " + trait.name() + "." + field.name());
                    validateExprTypes(moduleName, field.initializer(), generics);
                }
                for (Ast.MethodDecl method : trait.methods()) {
                    Set<String> methodGenerics = withGenerics(generics, method.genericParameters());
                    rejectTraitType(moduleName, method.explicitReceiverType(), methodGenerics,
                            "trait receiver " + trait.name() + "." + method.name());
                    for (Ast.Param param : method.parameters()) {
                        rejectTraitType(moduleName, param.type(), methodGenerics,
                                "trait parameter " + trait.name() + "." + method.name() + "." + param.name());
                    }
                    rejectTraitType(moduleName, method.returnType(), methodGenerics,
                            "trait return type " + trait.name() + "." + method.name());
                    validateStatementsTypes(moduleName, method.body(), methodGenerics);
                }
                return;
            }
            if (declaration instanceof Ast.TypeAliasDecl alias) {
                Set<String> generics = withGenerics(outerGenerics, alias.genericParameters());
                rejectTraitType(moduleName, alias.target(), generics, "type alias " + alias.name());
                return;
            }
            if (declaration instanceof Ast.FieldDecl field) {
                rejectTraitType(moduleName, field.type(), outerGenerics, "module field " + field.name());
                validateExprTypes(moduleName, field.initializer(), outerGenerics);
            }
        }

        private void validateStatementsTypes(
                String moduleName,
                List<Ast.Stmt> statements,
                Set<String> generics) {
            for (Ast.Stmt stmt : statements) {
                if (stmt instanceof Ast.TypeDeclStmt local) {
                    String localName = switch (local.declaration()) {
                        case Ast.ClassDecl klass -> klass.name();
                        case Ast.InterfaceDecl iface -> iface.name();
                        case Ast.TraitDecl trait -> trait.name();
                        case Ast.TypeAliasDecl alias -> alias.name();
                        default -> null;
                    };
                    if (localName != null && generics.contains(localName)) {
                        throw new IllegalArgumentException("callable-local type '" + localName
                                + "' collides with an in-scope generic type parameter");
                    }
                    validateDeclarationTypes(moduleName, local.declaration(), generics);
                } else if (stmt instanceof Ast.BindingStmt binding) {
                    rejectTraitType(moduleName, binding.declaredType(), generics, "local binding " + binding.name());
                    validateExprTypes(moduleName, binding.initializer(), generics);
                } else if (stmt instanceof Ast.DestructureStmt destructure) {
                    validateExprTypes(moduleName, destructure.initializer(), generics);
                } else if (stmt instanceof Ast.ReturnStmt ret) {
                    validateExprTypes(moduleName, ret.value(), generics);
                } else if (stmt instanceof Ast.ExprStmt expression) {
                    validateExprTypes(moduleName, expression.expression(), generics);
                } else if (stmt instanceof Ast.DeferStmt defer) {
                    validateExprTypes(moduleName, defer.expression(), generics);
                } else if (stmt instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        validateExprTypes(moduleName, branch.condition(), generics);
                        validateStatementsTypes(moduleName, branch.body(), generics);
                    }
                    validateStatementsTypes(moduleName, conditional.elseBody(), generics);
                } else if (stmt instanceof Ast.TryStmt attempted) {
                    validateStatementsTypes(moduleName, attempted.body(), generics);
                    validateStatementsTypes(moduleName, attempted.catchBody(), generics);
                    validateStatementsTypes(moduleName, attempted.finallyBody(), generics);
                } else if (stmt instanceof Ast.ForOfStmt loop) {
                    validateExprTypes(moduleName, loop.iterable(), generics);
                    validateStatementsTypes(moduleName, loop.body(), generics);
                } else if (stmt instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null) {
                        validateStatementsTypes(moduleName, List.of(loop.initializer()), generics);
                    }
                    validateExprTypes(moduleName, loop.condition(), generics);
                    validateExprTypes(moduleName, loop.update(), generics);
                    validateStatementsTypes(moduleName, loop.body(), generics);
                }
            }
        }

        private void validateExprTypes(
                String moduleName,
                Ast.Expr expr,
                Set<String> generics) {
            if (expr == null || expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;
            if (expr instanceof Ast.BinaryExpr e) {
                validateExprTypes(moduleName, e.left(), generics);
                validateExprTypes(moduleName, e.right(), generics);
            } else if (expr instanceof Ast.UnaryExpr e) {
                validateExprTypes(moduleName, e.operand(), generics);
            } else if (expr instanceof Ast.AssignExpr e) {
                validateExprTypes(moduleName, e.target(), generics);
                validateExprTypes(moduleName, e.value(), generics);
            } else if (expr instanceof Ast.ConditionalExpr e) {
                validateExprTypes(moduleName, e.condition(), generics);
                validateExprTypes(moduleName, e.whenTrue(), generics);
                validateExprTypes(moduleName, e.whenFalse(), generics);
            } else if (expr instanceof Ast.CallExpr e) {
                validateExprTypes(moduleName, e.callee(), generics);
                for (Ast.Expr argument : e.arguments()) validateExprTypes(moduleName, argument, generics);
            } else if (expr instanceof Ast.MemberExpr e) {
                validateExprTypes(moduleName, e.receiver(), generics);
            } else if (expr instanceof Ast.IndexExpr e) {
                validateExprTypes(moduleName, e.receiver(), generics);
                validateExprTypes(moduleName, e.index(), generics);
            } else if (expr instanceof Ast.NewExpr e) {
                if (!generics.contains(e.type().name()) && isTraitReference(moduleName, e.type().name())) {
                    throw new IllegalArgumentException(
                            "trait '" + e.type().name()
                                    + "' has no runtime/value type identity and cannot be instantiated; "
                                    + "compose it into a class with 'with'");
                }
                rejectTraitType(moduleName, e.type(), generics, "new expression");
                for (Ast.Expr argument : e.arguments()) validateExprTypes(moduleName, argument, generics);
            } else if (expr instanceof Ast.StructInitExpr e) {
                rejectTraitType(moduleName, e.type(), generics, "struct initializer");
                for (Ast.ObjectField field : e.fields()) validateExprTypes(moduleName, field.value(), generics);
            } else if (expr instanceof Ast.AwaitExpr e) {
                validateExprTypes(moduleName, e.expression(), generics);
            } else if (expr instanceof Ast.ListExpr e) {
                for (Ast.Expr element : e.elements()) validateExprTypes(moduleName, element, generics);
            } else if (expr instanceof Ast.TupleExpr e) {
                for (Ast.Expr element : e.elements()) validateExprTypes(moduleName, element, generics);
            } else if (expr instanceof Ast.ObjectExpr e) {
                for (Ast.ObjectField field : e.fields()) validateExprTypes(moduleName, field.value(), generics);
            } else if (expr instanceof Ast.LambdaExpr e) {
                Set<String> lambdaGenerics = generics;
                for (Ast.Param param : e.parameters()) {
                    rejectTraitType(moduleName, param.type(), lambdaGenerics, "lambda parameter " + param.name());
                }
                validateExprTypes(moduleName, e.expressionBody(), lambdaGenerics);
                if (e.blockBody() != null) validateStatementsTypes(moduleName, e.blockBody(), lambdaGenerics);
            }
        }

        private void rejectTraitArguments(
                String moduleName,
                Ast.TypeRef traitApplication,
                Set<String> generics,
                String where) {
            if (traitApplication == null) return;
            for (Ast.TypeRef argument : traitApplication.arguments()) {
                rejectTraitType(moduleName, argument, generics, where + " type argument");
            }
        }

        private void rejectTraitType(
                String moduleName,
                Ast.TypeRef type,
                Set<String> generics,
                String where) {
            if (type == null) return;
            if (!generics.contains(type.name()) && isTraitReference(moduleName, type.name())) {
                throw new IllegalArgumentException(
                        "trait '" + type.name() + "' has no runtime/value type identity and cannot be used as a runtime type ("
                                + where + "); compose it with 'with' and expose behavior through an interface");
            }
            for (Ast.TypeRef argument : type.arguments()) {
                rejectTraitType(moduleName, argument, generics, where + " type argument");
            }
        }

        private boolean isTraitReference(String moduleName, String name) {
            if (!name.contains(".")) {
                for (Map<String, TraitBinding> scope : localTraitScopes) {
                    if (scope.containsKey(name)) return true;
                }
            }
            return qualifiedTraits.containsKey(name)
                    || qualifiedTraits.containsKey(moduleName + "." + name);
        }

        private void pushLocalTraitScope(String moduleName, List<Ast.Stmt> statements) {
            LinkedHashMap<String, TraitBinding> traits = new LinkedHashMap<>();
            LinkedHashMap<String, String> names = new LinkedHashMap<>();

            for (Ast.Stmt stmt : statements) {
                if (!(stmt instanceof Ast.TypeDeclStmt local)) continue;

                String name = null;
                String kind = null;
                if (local.declaration() instanceof Ast.ClassDecl klass) {
                    name = klass.name();
                    kind = klass.isStruct() ? "struct" : "class";
                } else if (local.declaration() instanceof Ast.InterfaceDecl iface) {
                    name = iface.name();
                    kind = "interface";
                } else if (local.declaration() instanceof Ast.TraitDecl trait) {
                    name = trait.name();
                    kind = "trait";
                } else if (local.declaration() instanceof Ast.TypeAliasDecl alias) {
                    name = alias.name();
                    kind = "type alias";
                }

                if (name == null) continue;
                String previous = names.putIfAbsent(name, kind);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate callable-local type '" + name + "' (callable-local type name '"
                                    + name + "'): declared as both " + previous + " and " + kind
                                    + " in the same lexical block");
                }

                if (local.declaration() instanceof Ast.TraitDecl trait) {
                    String identity = localTraitIdentities.computeIfAbsent(
                            trait,
                            ignored -> moduleName + ".$localTrait$"
                                    + (nextLocalTraitIdentity++) + "." + trait.name());
                    traits.put(trait.name(), new TraitBinding(moduleName, trait, identity));
                }
            }
            localTraitScopes.push(traits);
        }

        private void popLocalTraitScope() {
            localTraitScopes.pop();
        }

        private Set<String> withGenerics(Set<String> base, List<String> additions) {
            LinkedHashSet<String> result = new LinkedHashSet<>(base);
            result.addAll(additions);
            return Set.copyOf(result);
        }

        private Ast.Program compose() {
            validateNoTraitInstantiation();
            validateNoTraitRuntimeTypes();
            List<Ast.ModuleDecl> modules = new ArrayList<>();
            for (Ast.ModuleDecl module : program.modules()) {
                List<Ast.Decl> declarations = new ArrayList<>();
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.TraitDecl) {
                        continue;
                    }
                    if (declaration instanceof Ast.ClassDecl klass) {
                        declarations.add(composeClass(module.name(), klass));
                    } else if (declaration instanceof Ast.FunctionDecl fn) {
                        declarations.add(withBody(fn, composeStatements(module.name(), fn.body())));
                    } else if (declaration instanceof Ast.InitDecl init) {
                        declarations.add(new Ast.InitDecl(composeStatements(module.name(), init.body())));
                    } else if (declaration instanceof Ast.FieldDecl field) {
                        declarations.add(withInitializer(field, composeExpr(module.name(), field.initializer())));
                    } else {
                        declarations.add(declaration);
                    }
                }
                modules.add(new Ast.ModuleDecl(
                        module.name(),
                        module.singleton(),
                        module.annotations(),
                        declarations));
            }
            return new Ast.Program(program.namespace(), program.imports(), modules);
        }

        private void validateNoTraitRuntimeTypes() {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    validateNoTraitRuntimeTypes(module.name(), declaration);
                }
            }
        }

        private void validateNoTraitRuntimeTypes(String moduleName, Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) {
                Set<String> generics = Set.copyOf(function.genericParameters());
                validateParams(moduleName, function.parameters(), generics, "function " + function.name());
                validateTypeRef(moduleName, function.returnType(), generics, "function return " + function.name());
                validateNoTraitRuntimeTypes(moduleName, function.body(), generics);
            } else if (declaration instanceof Ast.InitDecl init) {
                validateNoTraitRuntimeTypes(moduleName, init.body(), Set.of());
            } else if (declaration instanceof Ast.FieldDecl field) {
                validateTypeRef(moduleName, field.type(), Set.of(), "module field " + field.name());
                if (field.initializer() != null) validateNoTraitRuntimeTypes(moduleName, field.initializer(), Set.of());
            } else if (declaration instanceof Ast.ClassDecl klass) {
                Set<String> classGenerics = Set.copyOf(klass.genericParameters());
                for (Ast.FieldDecl field : klass.fields()) {
                    validateTypeRef(moduleName, field.type(), classGenerics, "class field " + klass.name() + "." + field.name());
                    if (field.initializer() != null) validateNoTraitRuntimeTypes(moduleName, field.initializer(), classGenerics);
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    Set<String> generics = new LinkedHashSet<>(classGenerics);
                    generics.addAll(method.genericParameters());
                    validateTypeRef(moduleName, method.explicitReceiverType(), generics, "method receiver " + klass.name() + "." + method.name());
                    validateParams(moduleName, method.parameters(), generics, "method " + klass.name() + "." + method.name());
                    validateTypeRef(moduleName, method.returnType(), generics, "method return " + klass.name() + "." + method.name());
                    validateNoTraitRuntimeTypes(moduleName, method.body(), generics);
                }
            } else if (declaration instanceof Ast.TraitDecl trait) {
                Set<String> traitGenerics = Set.copyOf(trait.genericParameters());
                for (Ast.FieldDecl field : trait.fields()) {
                    validateTypeRef(moduleName, field.type(), traitGenerics, "trait field " + trait.name() + "." + field.name());
                    if (field.initializer() != null) validateNoTraitRuntimeTypes(moduleName, field.initializer(), traitGenerics);
                }
                for (Ast.MethodDecl method : trait.methods()) {
                    Set<String> generics = new LinkedHashSet<>(traitGenerics);
                    generics.addAll(method.genericParameters());
                    validateTypeRef(moduleName, method.explicitReceiverType(), generics, "trait receiver " + trait.name() + "." + method.name());
                    validateParams(moduleName, method.parameters(), generics, "trait method " + trait.name() + "." + method.name());
                    validateTypeRef(moduleName, method.returnType(), generics, "trait method return " + trait.name() + "." + method.name());
                    validateNoTraitRuntimeTypes(moduleName, method.body(), generics);
                }
            } else if (declaration instanceof Ast.InterfaceDecl iface) {
                Set<String> interfaceGenerics = Set.copyOf(iface.genericParameters());
                for (Ast.InterfaceMember member : iface.members()) {
                    if (member instanceof Ast.InterfaceFunctionDecl function) {
                        Set<String> generics = new LinkedHashSet<>(interfaceGenerics);
                        generics.addAll(function.genericParameters());
                        validateParams(moduleName, function.parameters(), generics, "interface function " + iface.name() + "." + function.name());
                        validateTypeRef(moduleName, function.returnType(), generics, "interface function return " + iface.name() + "." + function.name());
                    }
                }
            } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                validateTypeRef(moduleName, alias.target(), Set.copyOf(alias.genericParameters()), "type alias " + alias.name());
            }
        }

        private void validateNoTraitRuntimeTypes(
                String moduleName,
                List<Ast.Stmt> statements,
                Set<String> generics) {
            pushLocalTraitScope(moduleName, statements);
            try {
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.TypeDeclStmt local) {
                    validateNoTraitRuntimeTypes(moduleName, local.declaration());
                    continue;
                }
                if (statement instanceof Ast.BindingStmt binding) {
                    validateTypeRef(moduleName, binding.declaredType(), generics, "binding " + binding.name());
                    validateNoTraitRuntimeTypes(moduleName, binding.initializer(), generics);
                } else if (statement instanceof Ast.DestructureStmt destructure) {
                    validateNoTraitRuntimeTypes(moduleName, destructure.initializer(), generics);
                } else if (statement instanceof Ast.ReturnStmt returned && returned.value() != null) {
                    validateNoTraitRuntimeTypes(moduleName, returned.value(), generics);
                } else if (statement instanceof Ast.ExprStmt expression) {
                    validateNoTraitRuntimeTypes(moduleName, expression.expression(), generics);
                } else if (statement instanceof Ast.DeferStmt deferred) {
                    validateNoTraitRuntimeTypes(moduleName, deferred.expression(), generics);
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        validateNoTraitRuntimeTypes(moduleName, branch.condition(), generics);
                        validateNoTraitRuntimeTypes(moduleName, branch.body(), generics);
                    }
                    validateNoTraitRuntimeTypes(moduleName, conditional.elseBody(), generics);
                } else if (statement instanceof Ast.TryStmt attempted) {
                    validateNoTraitRuntimeTypes(moduleName, attempted.body(), generics);
                    validateNoTraitRuntimeTypes(moduleName, attempted.catchBody(), generics);
                    validateNoTraitRuntimeTypes(moduleName, attempted.finallyBody(), generics);
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    validateNoTraitRuntimeTypes(moduleName, loop.iterable(), generics);
                    validateNoTraitRuntimeTypes(moduleName, loop.body(), generics);
                } else if (statement instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null) validateNoTraitRuntimeTypes(moduleName, List.of(loop.initializer()), generics);
                    if (loop.condition() != null) validateNoTraitRuntimeTypes(moduleName, loop.condition(), generics);
                    if (loop.update() != null) validateNoTraitRuntimeTypes(moduleName, loop.update(), generics);
                    validateNoTraitRuntimeTypes(moduleName, loop.body(), generics);
                }
            }
            } finally {
                popLocalTraitScope();
            }
        }

        private void validateNoTraitRuntimeTypes(
                String moduleName,
                Ast.Expr expression,
                Set<String> generics) {
            if (expression instanceof Ast.NewExpr created) {
                validateTypeRef(moduleName, created.type(), generics, "constructor type");
                for (Ast.Expr argument : created.arguments()) validateNoTraitRuntimeTypes(moduleName, argument, generics);
            } else if (expression instanceof Ast.StructInitExpr created) {
                validateTypeRef(moduleName, created.type(), generics, "struct initializer type");
                for (Ast.ObjectField field : created.fields()) {
                    validateNoTraitRuntimeTypes(moduleName, field.value(), generics);
                }
            } else if (expression instanceof Ast.MemberExpr member) {
                validateNoTraitRuntimeTypes(moduleName, member.receiver(), generics);
            } else if (expression instanceof Ast.CallExpr call) {
                validateNoTraitRuntimeTypes(moduleName, call.callee(), generics);
                for (Ast.Expr argument : call.arguments()) validateNoTraitRuntimeTypes(moduleName, argument, generics);
            } else if (expression instanceof Ast.BinaryExpr binary) {
                validateNoTraitRuntimeTypes(moduleName, binary.left(), generics);
                validateNoTraitRuntimeTypes(moduleName, binary.right(), generics);
            } else if (expression instanceof Ast.UnaryExpr unary) {
                validateNoTraitRuntimeTypes(moduleName, unary.operand(), generics);
            } else if (expression instanceof Ast.AssignExpr assignment) {
                validateNoTraitRuntimeTypes(moduleName, assignment.target(), generics);
                validateNoTraitRuntimeTypes(moduleName, assignment.value(), generics);
            } else if (expression instanceof Ast.ConditionalExpr conditional) {
                validateNoTraitRuntimeTypes(moduleName, conditional.condition(), generics);
                validateNoTraitRuntimeTypes(moduleName, conditional.whenTrue(), generics);
                validateNoTraitRuntimeTypes(moduleName, conditional.whenFalse(), generics);
            } else if (expression instanceof Ast.IndexExpr indexed) {
                validateNoTraitRuntimeTypes(moduleName, indexed.receiver(), generics);
                validateNoTraitRuntimeTypes(moduleName, indexed.index(), generics);
            } else if (expression instanceof Ast.AwaitExpr awaited) {
                validateNoTraitRuntimeTypes(moduleName, awaited.expression(), generics);
            } else if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) validateNoTraitRuntimeTypes(moduleName, item, generics);
            } else if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) validateNoTraitRuntimeTypes(moduleName, item, generics);
            } else if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) validateNoTraitRuntimeTypes(moduleName, field.value(), generics);
            } else if (expression instanceof Ast.LambdaExpr lambda) {
                for (Ast.Param parameter : lambda.parameters()) {
                    validateTypeRef(moduleName, parameter.type(), generics, "lambda parameter " + parameter.name());
                }
                if (lambda.expressionBody() != null) validateNoTraitRuntimeTypes(moduleName, lambda.expressionBody(), generics);
                if (lambda.blockBody() != null) validateNoTraitRuntimeTypes(moduleName, lambda.blockBody(), generics);
            }
        }

        private void validateParams(
                String moduleName,
                List<Ast.Param> parameters,
                Set<String> generics,
                String where) {
            for (Ast.Param parameter : parameters) {
                validateTypeRef(moduleName, parameter.type(), generics, where + " parameter " + parameter.name());
            }
        }

        private void validateTypeRef(
                String moduleName,
                Ast.TypeRef type,
                Set<String> generics,
                String where) {
            if (type == null) return;
            if (type.isBorrow()) {
                validateTypeRef(moduleName, type.borrowedTarget(), generics, where);
                return;
            }
            if (!generics.contains(type.name()) && isTraitName(moduleName, type.name())) {
                throw new IllegalArgumentException(
                        "trait '" + type.name() + "' cannot be used as a runtime type in " + where
                                + "; traits are composition units only—use an interface for contracts or a class for values");
            }
            for (Ast.TypeRef argument : type.arguments()) {
                validateTypeRef(moduleName, argument, generics, where);
            }
        }

        private void validateNoTraitInstantiation() {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    validateNoTraitInstantiation(module.name(), declaration);
                }
            }
        }

        private void validateNoTraitInstantiation(String moduleName, Ast.Decl declaration) {
            if (declaration instanceof Ast.FunctionDecl function) {
                validateNoTraitInstantiation(moduleName, function.body());
            } else if (declaration instanceof Ast.InitDecl init) {
                validateNoTraitInstantiation(moduleName, init.body());
            } else if (declaration instanceof Ast.FieldDecl field) {
                if (field.initializer() != null) validateNoTraitInstantiation(moduleName, field.initializer());
            } else if (declaration instanceof Ast.ClassDecl klass) {
                for (Ast.FieldDecl field : klass.fields()) {
                    if (field.initializer() != null) validateNoTraitInstantiation(moduleName, field.initializer());
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    validateNoTraitInstantiation(moduleName, method.body());
                }
            } else if (declaration instanceof Ast.TraitDecl trait) {
                for (Ast.FieldDecl field : trait.fields()) {
                    if (field.initializer() != null) validateNoTraitInstantiation(moduleName, field.initializer());
                }
                for (Ast.MethodDecl method : trait.methods()) {
                    validateNoTraitInstantiation(moduleName, method.body());
                }
            }
        }

        private void validateNoTraitInstantiation(String moduleName, List<Ast.Stmt> statements) {
            pushLocalTraitScope(moduleName, statements);
            try {
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.TypeDeclStmt local) {
                    validateNoTraitInstantiation(moduleName, local.declaration());
                    continue;
                }
                if (statement instanceof Ast.BindingStmt binding) {
                    validateNoTraitInstantiation(moduleName, binding.initializer());
                } else if (statement instanceof Ast.DestructureStmt destructure) {
                    validateNoTraitInstantiation(moduleName, destructure.initializer());
                } else if (statement instanceof Ast.ReturnStmt returned && returned.value() != null) {
                    validateNoTraitInstantiation(moduleName, returned.value());
                } else if (statement instanceof Ast.ExprStmt expression) {
                    validateNoTraitInstantiation(moduleName, expression.expression());
                } else if (statement instanceof Ast.DeferStmt deferred) {
                    validateNoTraitInstantiation(moduleName, deferred.expression());
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        validateNoTraitInstantiation(moduleName, branch.condition());
                        validateNoTraitInstantiation(moduleName, branch.body());
                    }
                    validateNoTraitInstantiation(moduleName, conditional.elseBody());
                } else if (statement instanceof Ast.TryStmt attempted) {
                    validateNoTraitInstantiation(moduleName, attempted.body());
                    validateNoTraitInstantiation(moduleName, attempted.catchBody());
                    validateNoTraitInstantiation(moduleName, attempted.finallyBody());
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    validateNoTraitInstantiation(moduleName, loop.iterable());
                    validateNoTraitInstantiation(moduleName, loop.body());
                } else if (statement instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null) validateNoTraitInstantiation(moduleName, List.of(loop.initializer()));
                    if (loop.condition() != null) validateNoTraitInstantiation(moduleName, loop.condition());
                    if (loop.update() != null) validateNoTraitInstantiation(moduleName, loop.update());
                    validateNoTraitInstantiation(moduleName, loop.body());
                }
            }
            } finally {
                popLocalTraitScope();
            }
        }

        private void validateNoTraitInstantiation(String moduleName, Ast.Expr expression) {
            if (expression instanceof Ast.NewExpr created) {
                if (isTraitName(moduleName, created.type().name())) {
                    throw new IllegalArgumentException(
                            "trait '" + created.type().name()
                                    + "' cannot be instantiated; compose it into a class with 'with'");
                }
                for (Ast.Expr argument : created.arguments()) validateNoTraitInstantiation(moduleName, argument);
            } else if (expression instanceof Ast.StructInitExpr created) {
                if (created.type() != null && isTraitName(moduleName, created.type().name())) {
                    throw new IllegalArgumentException(
                            "trait '" + created.type().name()
                                    + "' cannot be initialized as a value; compose it into a struct/class with 'with'");
                }
                for (Ast.ObjectField field : created.fields()) {
                    validateNoTraitInstantiation(moduleName, field.value());
                }
            } else if (expression instanceof Ast.MemberExpr member) {
                validateNoTraitInstantiation(moduleName, member.receiver());
            } else if (expression instanceof Ast.CallExpr call) {
                validateNoTraitInstantiation(moduleName, call.callee());
                for (Ast.Expr argument : call.arguments()) validateNoTraitInstantiation(moduleName, argument);
            } else if (expression instanceof Ast.BinaryExpr binary) {
                validateNoTraitInstantiation(moduleName, binary.left());
                validateNoTraitInstantiation(moduleName, binary.right());
            } else if (expression instanceof Ast.UnaryExpr unary) {
                validateNoTraitInstantiation(moduleName, unary.operand());
            } else if (expression instanceof Ast.AssignExpr assignment) {
                validateNoTraitInstantiation(moduleName, assignment.target());
                validateNoTraitInstantiation(moduleName, assignment.value());
            } else if (expression instanceof Ast.ConditionalExpr conditional) {
                validateNoTraitInstantiation(moduleName, conditional.condition());
                validateNoTraitInstantiation(moduleName, conditional.whenTrue());
                validateNoTraitInstantiation(moduleName, conditional.whenFalse());
            } else if (expression instanceof Ast.IndexExpr indexed) {
                validateNoTraitInstantiation(moduleName, indexed.receiver());
                validateNoTraitInstantiation(moduleName, indexed.index());
            } else if (expression instanceof Ast.AwaitExpr awaited) {
                validateNoTraitInstantiation(moduleName, awaited.expression());
            } else if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) validateNoTraitInstantiation(moduleName, item);
            } else if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) validateNoTraitInstantiation(moduleName, item);
            } else if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) validateNoTraitInstantiation(moduleName, field.value());
            } else if (expression instanceof Ast.LambdaExpr lambda) {
                if (lambda.expressionBody() != null) validateNoTraitInstantiation(moduleName, lambda.expressionBody());
                if (lambda.blockBody() != null) validateNoTraitInstantiation(moduleName, lambda.blockBody());
            }
        }

        private boolean isTraitName(String moduleName, String name) {
            if (!name.contains(".")) {
                for (Map<String, TraitBinding> scope : localTraitScopes) {
                    if (scope.containsKey(name)) return true;
                }
            }
            if (qualifiedTraits.containsKey(moduleName + "." + name)) return true;
            return name.contains(".") && qualifiedTraits.containsKey(name);
        }

        private void indexTraits() {
            for (Ast.ModuleDecl module : program.modules()) {
                Set<String> occupiedTypeNames = new HashSet<>();
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ClassDecl klass) occupiedTypeNames.add(klass.name());
                    else if (declaration instanceof Ast.InterfaceDecl iface) occupiedTypeNames.add(iface.name());
                    else if (declaration instanceof Ast.TypeAliasDecl alias) occupiedTypeNames.add(alias.name());
                }

                for (Ast.Decl declaration : module.declarations()) {
                    if (!(declaration instanceof Ast.TraitDecl trait)) continue;
                    if (occupiedTypeNames.contains(trait.name())) {
                        throw new IllegalArgumentException(
                                "type name '" + module.name() + "." + trait.name()
                                        + "' collides with an existing class/interface/type alias; "
                                        + "class/struct/interface/trait/type names share one namespace");
                    }

                    String qualified = module.name() + "." + trait.name();
                    TraitBinding binding = new TraitBinding(module.name(), trait, qualified);
                    if (qualifiedTraits.putIfAbsent(qualified, binding) != null) {
                        throw new IllegalArgumentException("duplicate trait '" + qualified + "'");
                    }

                    TraitBinding previous = unqualifiedTraits.putIfAbsent(trait.name(), binding);
                    if (previous != null && previous != binding) {
                        ambiguousTraits.add(trait.name());
                        unqualifiedTraits.remove(trait.name());
                    }
                }
            }
        }

        private Ast.ClassDecl composeClass(String moduleName, Ast.ClassDecl klass) {
            if (klass.traits().isEmpty()) return composeClassBodies(moduleName, klass);

            LinkedHashMap<String, FieldEntry> traitFields = new LinkedHashMap<>();
            LinkedHashMap<String, List<MethodEntry>> traitMethods = new LinkedHashMap<>();
            LinkedHashMap<String, Ast.TypeRef> interfaces = new LinkedHashMap<>();
            for (Ast.TypeRef iface : klass.interfaces()) {
                mergeInterface(interfaces, iface, "class " + klass.name());
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            for (Ast.TypeRef traitRef : klass.traits()) {
                Material material = materialize(moduleName, traitRef, classGenerics, new ArrayDeque<>());

                for (FieldEntry field : material.fields.values()) {
                    FieldEntry previous = traitFields.putIfAbsent(field.field().name(), field);
                    if (previous != null && !previous.origin().equals(field.origin())) {
                        throw new IllegalArgumentException(
                                "trait state collision for field '" + field.field().name()
                                        + "' in class '" + klass.name() + "': "
                                        + previous.origin() + " vs " + field.origin());
                    }
                }

                for (Map.Entry<String, MethodEntry> method : material.methods.entrySet()) {
                    traitMethods.computeIfAbsent(method.getKey(), ignored -> new ArrayList<>())
                            .add(method.getValue());
                }

                for (Ast.TypeRef iface : material.interfaces.values()) {
                    mergeInterface(interfaces, iface, "trait composition of " + klass.name());
                }
            }

            for (Ast.FieldDecl field : klass.fields()) {
                if (traitFields.containsKey(field.name())) {
                    throw new IllegalArgumentException(
                            "class '" + klass.name() + "' field '" + field.name()
                                    + "' collides with composed trait state; trait storage cannot be shadowed");
                }
            }

            LinkedHashMap<String, Ast.MethodDecl> localMethods = new LinkedHashMap<>();
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic()) localMethods.put(methodKey(method), method);
            }

            List<Ast.MethodDecl> composedMethods = new ArrayList<>();
            for (Map.Entry<String, List<MethodEntry>> entry : traitMethods.entrySet()) {
                String key = entry.getKey();
                List<MethodEntry> candidates = dedupeOrigins(entry.getValue());
                Ast.MethodDecl local = localMethods.get(key);

                if (local != null) {
                    for (MethodEntry candidate : candidates) {
                        if (candidate.method().visibility() == Ast.Visibility.PRIVATE) {
                            throw new IllegalArgumentException(
                                    "class '" + klass.name() + "' method '" + key
                                            + "' collides with trait-private method from "
                                            + candidate.origin()
                                            + "; private trait helpers cannot be overridden");
                        }
                        requireCompatible(
                                local,
                                candidate.method(),
                                "class override " + klass.name() + "." + key);
                    }
                    continue;
                }

                Ast.MethodDecl selected = selectTraitMethod(klass, key, candidates);
                if (selected != null) composedMethods.add(selected);
            }

            List<Ast.FieldDecl> fields = new ArrayList<>(traitFields.size() + klass.fields().size());
            for (FieldEntry entry : traitFields.values()) fields.add(entry.field());
            fields.addAll(klass.fields());

            List<Ast.MethodDecl> methods = new ArrayList<>(composedMethods.size() + klass.methods().size());
            methods.addAll(composedMethods);
            methods.addAll(klass.methods());

            return composeClassBodies(moduleName, new Ast.ClassDecl(
                    klass.name(),
                    klass.kind(),
                    klass.isAbstract(),
                    klass.genericParameters(),
                    klass.parents(),
                    List.copyOf(interfaces.values()),
                    List.of(),
                    fields,
                    methods));
        }

        private Ast.ClassDecl composeClassBodies(String moduleName, Ast.ClassDecl klass) {
            List<Ast.FieldDecl> fields = new ArrayList<>(klass.fields().size());
            for (Ast.FieldDecl field : klass.fields()) {
                fields.add(withInitializer(field, composeExpr(moduleName, field.initializer())));
            }

            List<Ast.MethodDecl> methods = new ArrayList<>(klass.methods().size());
            for (Ast.MethodDecl method : klass.methods()) {
                methods.add(withBody(method, composeStatements(moduleName, method.body())));
            }

            return new Ast.ClassDecl(
                    klass.name(),
                    klass.kind(),
                    klass.isAbstract(),
                    klass.genericParameters(),
                    klass.parents(),
                    klass.interfaces(),
                    klass.traits(),
                    fields,
                    methods);
        }

        private Ast.FunctionDecl withBody(Ast.FunctionDecl fn, List<Ast.Stmt> body) {
            return new Ast.FunctionDecl(
                    fn.name(),
                    fn.kind(),
                    fn.visibility(),
                    fn.async(),
                    fn.genericParameters(),
                    fn.parameters(),
                    fn.returnType(),
                    fn.annotations(),
                    body);
        }

        private Ast.MethodDecl withBody(Ast.MethodDecl method, List<Ast.Stmt> body) {
            return new Ast.MethodDecl(
                    method.name(),
                    method.visibility(),
                    method.isStatic(),
                    method.isAbstract(),
                    method.async(),
                    method.explicitReceiverType(),
                    method.genericParameters(),
                    method.parameters(),
                    method.returnType(),
                    method.annotations(),
                    body,
                    method.compositionOwner());
        }

        private Ast.FieldDecl withInitializer(Ast.FieldDecl field, Ast.Expr initializer) {
            if (field.initializer() == initializer) return field;
            return new Ast.FieldDecl(
                    field.name(),
                    field.visibility(),
                    field.bindingKind(),
                    field.type(),
                    initializer,
                    field.compositionOwner());
        }

        private List<Ast.Stmt> composeStatements(String moduleName, List<Ast.Stmt> statements) {
            pushLocalTraitScope(moduleName, statements);
            try {
                List<Ast.Stmt> result = new ArrayList<>(statements.size());
                for (Ast.Stmt stmt : statements) {
                    if (stmt instanceof Ast.TypeDeclStmt local
                            && local.declaration() instanceof Ast.TraitDecl) {
                        continue; // traits are compile-time-only composition declarations
                    }
                    result.add(composeStatement(moduleName, stmt));
                }
                return List.copyOf(result);
            } finally {
                popLocalTraitScope();
            }
        }

        private Ast.Stmt composeStatement(String moduleName, Ast.Stmt stmt) {
            if (stmt instanceof Ast.TypeDeclStmt local) {
                if (local.declaration() instanceof Ast.ClassDecl klass) {
                    return new Ast.TypeDeclStmt(composeClass(moduleName, klass));
                }
                return local;
            }
            if (stmt instanceof Ast.BindingStmt binding) {
                return new Ast.BindingStmt(binding.kind(), binding.declaredType(), binding.name(),
                        composeExpr(moduleName, binding.initializer()));
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                return new Ast.DestructureStmt(destructure.bindings(), composeExpr(moduleName, destructure.initializer()));
            }
            if (stmt instanceof Ast.ReturnStmt ret) {
                return new Ast.ReturnStmt(composeExpr(moduleName, ret.value()));
            }
            if (stmt instanceof Ast.ExprStmt expression) {
                return new Ast.ExprStmt(composeExpr(moduleName, expression.expression()));
            }
            if (stmt instanceof Ast.DeferStmt defer) {
                return new Ast.DeferStmt(composeExpr(moduleName, defer.expression()));
            }
            if (stmt instanceof Ast.IfStmt conditional) {
                List<Ast.IfBranch> branches = new ArrayList<>(conditional.branches().size());
                for (Ast.IfBranch branch : conditional.branches()) {
                    branches.add(new Ast.IfBranch(
                            composeExpr(moduleName, branch.condition()),
                            composeStatements(moduleName, branch.body())));
                }
                return new Ast.IfStmt(branches, composeStatements(moduleName, conditional.elseBody()));
            }
            if (stmt instanceof Ast.TryStmt attempted) {
                return new Ast.TryStmt(
                        composeStatements(moduleName, attempted.body()),
                        attempted.errorName(),
                        composeStatements(moduleName, attempted.catchBody()),
                        composeStatements(moduleName, attempted.finallyBody()));
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                return new Ast.ForOfStmt(
                        loop.bindingKind(),
                        loop.bindingName(),
                        composeExpr(moduleName, loop.iterable()),
                        composeStatements(moduleName, loop.body()));
            }
            if (stmt instanceof Ast.ForStmt loop) {
                return new Ast.ForStmt(
                        loop.initializer() == null ? null : composeStatement(moduleName, loop.initializer()),
                        composeExpr(moduleName, loop.condition()),
                        composeExpr(moduleName, loop.update()),
                        composeStatements(moduleName, loop.body()));
            }
            return stmt;
        }

        private Ast.Expr composeExpr(String moduleName, Ast.Expr expr) {
            if (expr == null || expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return expr;
            if (expr instanceof Ast.BinaryExpr e) {
                return new Ast.BinaryExpr(e.operator(), composeExpr(moduleName, e.left()), composeExpr(moduleName, e.right()));
            }
            if (expr instanceof Ast.UnaryExpr e) {
                return new Ast.UnaryExpr(e.operator(), composeExpr(moduleName, e.operand()));
            }
            if (expr instanceof Ast.AssignExpr e) {
                return new Ast.AssignExpr(composeExpr(moduleName, e.target()), composeExpr(moduleName, e.value()));
            }
            if (expr instanceof Ast.ConditionalExpr e) {
                return new Ast.ConditionalExpr(
                        composeExpr(moduleName, e.condition()),
                        composeExpr(moduleName, e.whenTrue()),
                        composeExpr(moduleName, e.whenFalse()));
            }
            if (expr instanceof Ast.CallExpr e) {
                List<Ast.Expr> args = new ArrayList<>(e.arguments().size());
                for (Ast.Expr arg : e.arguments()) args.add(composeExpr(moduleName, arg));
                return new Ast.CallExpr(composeExpr(moduleName, e.callee()), args);
            }
            if (expr instanceof Ast.MemberExpr e) {
                return new Ast.MemberExpr(composeExpr(moduleName, e.receiver()), e.member());
            }
            if (expr instanceof Ast.IndexExpr e) {
                return new Ast.IndexExpr(composeExpr(moduleName, e.receiver()), composeExpr(moduleName, e.index()));
            }
            if (expr instanceof Ast.NewExpr e) {
                List<Ast.Expr> args = new ArrayList<>(e.arguments().size());
                for (Ast.Expr arg : e.arguments()) args.add(composeExpr(moduleName, arg));
                return new Ast.NewExpr(e.type(), args);
            }
            if (expr instanceof Ast.StructInitExpr e) {
                List<Ast.ObjectField> fields = new ArrayList<>(e.fields().size());
                for (Ast.ObjectField field : e.fields()) {
                    fields.add(new Ast.ObjectField(field.name(), composeExpr(moduleName, field.value())));
                }
                return new Ast.StructInitExpr(e.type(), fields);
            }
            if (expr instanceof Ast.AwaitExpr e) {
                return new Ast.AwaitExpr(composeExpr(moduleName, e.expression()));
            }
            if (expr instanceof Ast.ListExpr e) {
                List<Ast.Expr> elements = new ArrayList<>(e.elements().size());
                for (Ast.Expr element : e.elements()) elements.add(composeExpr(moduleName, element));
                return new Ast.ListExpr(elements);
            }
            if (expr instanceof Ast.TupleExpr e) {
                List<Ast.Expr> elements = new ArrayList<>(e.elements().size());
                for (Ast.Expr element : e.elements()) elements.add(composeExpr(moduleName, element));
                return new Ast.TupleExpr(elements);
            }
            if (expr instanceof Ast.ObjectExpr e) {
                List<Ast.ObjectField> fields = new ArrayList<>(e.fields().size());
                for (Ast.ObjectField field : e.fields()) {
                    fields.add(new Ast.ObjectField(field.name(), composeExpr(moduleName, field.value())));
                }
                return new Ast.ObjectExpr(fields);
            }
            if (expr instanceof Ast.LambdaExpr e) {
                return new Ast.LambdaExpr(
                        e.parameters(),
                        composeExpr(moduleName, e.expressionBody()),
                        e.blockBody() == null ? null : composeStatements(moduleName, e.blockBody()));
            }
            return expr;
        }

        private Ast.MethodDecl selectTraitMethod(
                Ast.ClassDecl klass,
                String key,
                List<MethodEntry> candidates) {
            if (candidates.isEmpty()) return null;

            Ast.MethodDecl baseline = candidates.getFirst().method();
            for (MethodEntry candidate : candidates) {
                requireCompatible(
                        baseline,
                        candidate.method(),
                        "trait method " + klass.name() + "." + key);
            }

            List<MethodEntry> concrete = candidates.stream()
                    .filter(entry -> !entry.method().isAbstract())
                    .toList();

            if (concrete.size() > 1) {
                throw new IllegalArgumentException(
                        "ambiguous trait method '" + key + "' in class '" + klass.name()
                                + "' from " + concrete.stream().map(MethodEntry::origin).toList()
                                + "; declare the method on the class to resolve the conflict");
            }
            if (concrete.size() == 1) return concrete.getFirst().method();

            if (!klass.isAbstract()) {
                throw new IllegalArgumentException(
                        "concrete class '" + klass.name()
                                + "' must implement trait requirement '" + key + "'");
            }
            return candidates.getFirst().method();
        }

        private Material materialize(
                String hostModule,
                Ast.TypeRef traitRef,
                Set<String> hostGenerics,
                ArrayDeque<String> stack) {
            TraitBinding binding = resolveTrait(hostModule, traitRef.name());

            // Method bodies retain their lexical module environment after
            // flattening. Restrict v0 composition to the declaring module so
            // that this transformation cannot silently change lexical lookup.
            if (!binding.module().equals(hostModule)) {
                throw new IllegalArgumentException(
                        "trait '" + traitRef.name() + "' is declared in module '"
                                + binding.module()
                                + "' but v0 trait composition is module-local");
            }

            Map<String, Ast.TypeRef> substitutions =
                    bindGenerics(binding.declaration(), traitRef, hostGenerics);
            String applicationKey = applicationKey(binding, substitutions);

            Material cached = materialCache.get(applicationKey);
            if (cached != null) return cached;
            if (stack.contains(applicationKey)) {
                throw new IllegalArgumentException(
                        "trait composition cycle: " + stack + " -> " + applicationKey);
            }
            stack.addLast(applicationKey);

            Material material = new Material();
            LinkedHashMap<String, List<MethodEntry>> inheritedMethods = new LinkedHashMap<>();

            for (Ast.TypeRef nestedRaw : binding.declaration().traits()) {
                Ast.TypeRef nested = substitute(nestedRaw, substitutions);
                Material nestedMaterial =
                        materialize(hostModule, nested, hostGenerics, stack);

                for (FieldEntry field : nestedMaterial.fields.values()) {
                    mergeField(material, field);
                }
                for (Map.Entry<String, MethodEntry> method : nestedMaterial.methods.entrySet()) {
                    inheritedMethods
                            .computeIfAbsent(method.getKey(), ignored -> new ArrayList<>())
                            .add(method.getValue());
                }
                for (Ast.TypeRef iface : nestedMaterial.interfaces.values()) {
                    mergeInterface(
                            material.interfaces,
                            iface,
                            "trait " + binding.declaration().name());
                }
            }

            for (Ast.TypeRef ifaceRaw : binding.declaration().interfaces()) {
                mergeInterface(
                        material.interfaces,
                        substitute(ifaceRaw, substitutions),
                        "trait " + binding.declaration().name());
            }

            for (Ast.FieldDecl raw : binding.declaration().fields()) {
                if (raw.initializer() == null) {
                    throw new IllegalArgumentException(
                            "trait state field '" + binding.declaration().name() + "."
                                    + raw.name()
                                    + "' requires an initializer because trait state is not "
                                    + "a host-class constructor parameter");
                }

                Ast.FieldDecl field = new Ast.FieldDecl(
                        raw.name(),
                        raw.visibility(),
                        raw.bindingKind(),
                        substitute(raw.type(), substitutions),
                        substitute(raw.initializer(), substitutions),
                        binding.identity());

                mergeField(
                        material,
                        new FieldEntry(
                                field,
                                applicationKey + "::" + raw.name()));
            }

            Set<String> lexicalFields = new LinkedHashSet<>();
            for (Ast.FieldDecl field : binding.declaration().fields()) lexicalFields.add(field.name());
            Set<String> lexicalMethods = new LinkedHashSet<>();
            for (Ast.MethodDecl method : binding.declaration().methods()) lexicalMethods.add(method.name());
            for (String inheritedKey : inheritedMethods.keySet()) {
                lexicalMethods.add(inheritedKey.substring(0, inheritedKey.lastIndexOf('/')));
            }

            LinkedHashMap<String, Ast.MethodDecl> ownMethods = new LinkedHashMap<>();
            for (Ast.MethodDecl raw : binding.declaration().methods()) {
                if (raw.isAbstract() && raw.visibility() == Ast.Visibility.PRIVATE) {
                    throw new IllegalArgumentException(
                            "trait '" + binding.declaration().name() + "' has private abstract method '"
                                    + raw.name()
                                    + "'; abstract trait requirements must be public so a host class can satisfy them");
                }
                validateTraitSelfAccess(binding.declaration().name(), raw.body(), lexicalFields, lexicalMethods);
                Ast.MethodDecl method = withCompositionOwner(
                        substitute(raw, substitutions),
                        binding.identity());
                String key = methodKey(method);
                Ast.MethodDecl previous = ownMethods.putIfAbsent(key, method);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate trait method '"
                                    + binding.declaration().name() + "." + key + "'");
                }
            }

            Set<String> methodKeys = new LinkedHashSet<>();
            methodKeys.addAll(inheritedMethods.keySet());
            methodKeys.addAll(ownMethods.keySet());

            for (String key : methodKeys) {
                Ast.MethodDecl override = ownMethods.get(key);
                List<MethodEntry> inherited =
                        dedupeOrigins(inheritedMethods.getOrDefault(key, List.of()));

                if (override != null) {
                    for (MethodEntry candidate : inherited) {
                        if (candidate.method().visibility() == Ast.Visibility.PRIVATE) {
                            throw new IllegalArgumentException(
                                    "trait '" + binding.declaration().name()
                                            + "' method '" + key
                                            + "' collides with private method inherited from "
                                            + candidate.origin()
                                            + "; private nested-trait helpers are lexical and cannot be overridden");
                        }
                        requireCompatible(
                                override,
                                candidate.method(),
                                "trait override "
                                        + binding.declaration().name() + "." + key);
                    }
                    material.methods.put(
                            key,
                            new MethodEntry(
                                    override,
                                    applicationKey + "::" + key));
                    continue;
                }

                if (inherited.isEmpty()) continue;

                Ast.MethodDecl baseline = inherited.getFirst().method();
                for (MethodEntry candidate : inherited) {
                    requireCompatible(
                            baseline,
                            candidate.method(),
                            "nested trait method "
                                    + binding.declaration().name() + "." + key);
                }

                List<MethodEntry> concrete = inherited.stream()
                        .filter(entry -> !entry.method().isAbstract())
                        .toList();
                if (concrete.size() > 1) {
                    throw new IllegalArgumentException(
                            "trait '" + binding.declaration().name()
                                    + "' inherits ambiguous concrete method '" + key
                                    + "'; override it in the trait to resolve the conflict");
                }

                MethodEntry selected =
                        concrete.isEmpty() ? inherited.getFirst() : concrete.getFirst();
                material.methods.put(key, selected);
            }

            stack.removeLast();
            materialCache.put(applicationKey, material);
            return material;
        }

        private void mergeField(Material material, FieldEntry incoming) {
            FieldEntry previous =
                    material.fields.putIfAbsent(incoming.field().name(), incoming);
            if (previous != null && !previous.origin().equals(incoming.origin())) {
                throw new IllegalArgumentException(
                        "trait state collision for field '" + incoming.field().name()
                                + "': " + previous.origin() + " vs " + incoming.origin());
            }
        }

        private void mergeInterface(
                Map<String, Ast.TypeRef> interfaces,
                Ast.TypeRef incoming,
                String owner) {
            Ast.TypeRef previous = interfaces.putIfAbsent(incoming.name(), incoming);
            if (previous != null && !previous.equals(incoming)) {
                throw new IllegalArgumentException(
                        "conflicting interface instantiations for '" + incoming.name()
                                + "' in " + owner + ": " + previous + " vs " + incoming);
            }
        }

        private TraitBinding resolveTrait(String hostModule, String name) {
            if (!name.contains(".")) {
                for (Map<String, TraitBinding> scope : localTraitScopes) {
                    TraitBinding lexical = scope.get(name);
                    if (lexical != null) return lexical;
                }
            }

            TraitBinding local = qualifiedTraits.get(hostModule + "." + name);
            if (local != null) return local;

            if (name.contains(".")) {
                TraitBinding exact = qualifiedTraits.get(name);
                if (exact != null) return exact;
            }

            if (ambiguousTraits.contains(name)) {
                throw new IllegalArgumentException(
                        "ambiguous trait '" + name + "'; qualify it with its module");
            }

            TraitBinding found = unqualifiedTraits.get(name);
            if (found == null) {
                throw new IllegalArgumentException("unknown trait '" + name + "'");
            }
            return found;
        }

        private Map<String, Ast.TypeRef> bindGenerics(
                Ast.TraitDecl trait,
                Ast.TypeRef reference,
                Set<String> hostGenerics) {
            List<String> parameters = trait.genericParameters();
            List<Ast.TypeRef> arguments = reference.arguments();

            if (parameters.isEmpty()) {
                if (!arguments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "trait '" + trait.name() + "' is not generic");
                }
                return Map.of();
            }

            List<Ast.TypeRef> bound = arguments;
            if (reference.inferArguments()) {
                ArrayList<Ast.TypeRef> inferred = new ArrayList<>();
                for (String parameter : parameters) {
                    if (!hostGenerics.contains(parameter)) {
                        throw new IllegalArgumentException(
                                "cannot infer trait generic '" + parameter
                                        + "' for " + trait.name()
                                        + "; use explicit type arguments");
                    }
                    inferred.add(Ast.TypeRef.simple(parameter));
                }
                bound = inferred;
            }

            if (bound.size() != parameters.size()) {
                throw new IllegalArgumentException(
                        "trait '" + trait.name() + "' expects "
                                + parameters.size()
                                + " type arguments but got " + bound.size());
            }

            LinkedHashMap<String, Ast.TypeRef> result = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                result.put(parameters.get(i), bound.get(i));
            }
            return result;
        }

        private String applicationKey(
                TraitBinding binding,
                Map<String, Ast.TypeRef> substitutions) {
            StringBuilder key =
                    new StringBuilder(binding.identity())
                            .append('<');
            for (String parameter : binding.declaration().genericParameters()) {
                key.append(parameter)
                        .append('=')
                        .append(substitutions.get(parameter))
                        .append(';');
            }
            return key.append('>').toString();
        }

        private Ast.TypeRef substitute(
                Ast.TypeRef type,
                Map<String, Ast.TypeRef> substitutions) {
            if (type == null) return null;

            Ast.TypeRef direct = substitutions.get(type.name());
            if (direct != null && type.arguments().isEmpty()) return direct;

            return new Ast.TypeRef(
                    type.name(),
                    type.arguments().stream()
                            .map(argument -> substitute(argument, substitutions))
                            .toList(),
                    type.inferArguments());
        }

        private List<Ast.Stmt> substituteStatements(
                List<Ast.Stmt> statements,
                Map<String, Ast.TypeRef> substitutions) {
            return statements.stream()
                    .map(statement -> substitute(statement, substitutions))
                    .toList();
        }

        private Ast.Stmt substitute(
                Ast.Stmt statement,
                Map<String, Ast.TypeRef> substitutions) {
            if (statement == null) return null;
            if (statement instanceof Ast.BindingStmt binding) {
                return new Ast.BindingStmt(
                        binding.kind(),
                        substitute(binding.declaredType(), substitutions),
                        binding.name(),
                        substitute(binding.initializer(), substitutions));
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                return new Ast.DestructureStmt(
                        destructure.bindings(),
                        substitute(destructure.initializer(), substitutions));
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                return new Ast.ReturnStmt(
                        returned.value() == null ? null : substitute(returned.value(), substitutions));
            }
            if (statement instanceof Ast.ExprStmt expression) {
                return new Ast.ExprStmt(substitute(expression.expression(), substitutions));
            }
            if (statement instanceof Ast.DeferStmt deferred) {
                return new Ast.DeferStmt(substitute(deferred.expression(), substitutions));
            }
            if (statement instanceof Ast.IfStmt conditional) {
                List<Ast.IfBranch> branches = conditional.branches().stream()
                        .map(branch -> new Ast.IfBranch(
                                substitute(branch.condition(), substitutions),
                                substituteStatements(branch.body(), substitutions)))
                        .toList();
                return new Ast.IfStmt(
                        branches,
                        substituteStatements(conditional.elseBody(), substitutions));
            }
            if (statement instanceof Ast.TryStmt attempted) {
                return new Ast.TryStmt(
                        substituteStatements(attempted.body(), substitutions),
                        attempted.errorName(),
                        substituteStatements(attempted.catchBody(), substitutions),
                        substituteStatements(attempted.finallyBody(), substitutions));
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                return new Ast.ForOfStmt(
                        loop.bindingKind(),
                        loop.bindingName(),
                        substitute(loop.iterable(), substitutions),
                        substituteStatements(loop.body(), substitutions));
            }
            if (statement instanceof Ast.ForStmt loop) {
                return new Ast.ForStmt(
                        substitute(loop.initializer(), substitutions),
                        loop.condition() == null ? null : substitute(loop.condition(), substitutions),
                        loop.update() == null ? null : substitute(loop.update(), substitutions),
                        substituteStatements(loop.body(), substitutions));
            }
            if (statement instanceof Ast.TypeDeclStmt localType) {
                return new Ast.TypeDeclStmt(
                        substituteLocalDecl(localType.declaration(), substitutions));
            }
            throw new IllegalStateException(
                    "unsupported statement node during trait substitution: "
                            + statement.getClass().getName());
        }

        private Ast.Expr substitute(
                Ast.Expr expression,
                Map<String, Ast.TypeRef> substitutions) {
            if (expression == null) return null;
            if (expression instanceof Ast.LiteralExpr || expression instanceof Ast.NameExpr) {
                return expression;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                return new Ast.BinaryExpr(
                        binary.operator(),
                        substitute(binary.left(), substitutions),
                        substitute(binary.right(), substitutions));
            }
            if (expression instanceof Ast.UnaryExpr unary) {
                return new Ast.UnaryExpr(
                        unary.operator(),
                        substitute(unary.operand(), substitutions));
            }
            if (expression instanceof Ast.AssignExpr assignment) {
                return new Ast.AssignExpr(
                        substitute(assignment.target(), substitutions),
                        substitute(assignment.value(), substitutions));
            }
            if (expression instanceof Ast.ConditionalExpr conditional) {
                return new Ast.ConditionalExpr(
                        substitute(conditional.condition(), substitutions),
                        substitute(conditional.whenTrue(), substitutions),
                        substitute(conditional.whenFalse(), substitutions));
            }
            if (expression instanceof Ast.CallExpr call) {
                return new Ast.CallExpr(
                        substitute(call.callee(), substitutions),
                        call.arguments().stream()
                                .map(argument -> substitute(argument, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.MemberExpr member) {
                return new Ast.MemberExpr(
                        substitute(member.receiver(), substitutions),
                        member.member());
            }
            if (expression instanceof Ast.IndexExpr indexed) {
                return new Ast.IndexExpr(
                        substitute(indexed.receiver(), substitutions),
                        substitute(indexed.index(), substitutions));
            }
            if (expression instanceof Ast.NewExpr created) {
                return new Ast.NewExpr(
                        substitute(created.type(), substitutions),
                        created.arguments().stream()
                                .map(argument -> substitute(argument, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.StructInitExpr initialized) {
                return new Ast.StructInitExpr(
                        initialized.type() == null
                                ? null
                                : substitute(initialized.type(), substitutions),
                        initialized.fields().stream()
                                .map(field -> new Ast.ObjectField(
                                        field.name(),
                                        substitute(field.value(), substitutions)))
                                .toList());
            }
            if (expression instanceof Ast.AwaitExpr awaited) {
                return new Ast.AwaitExpr(
                        substitute(awaited.expression(), substitutions));
            }
            if (expression instanceof Ast.ListExpr list) {
                return new Ast.ListExpr(
                        list.elements().stream()
                                .map(item -> substitute(item, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.TupleExpr tuple) {
                return new Ast.TupleExpr(
                        tuple.elements().stream()
                                .map(item -> substitute(item, substitutions))
                                .toList());
            }
            if (expression instanceof Ast.ObjectExpr object) {
                return new Ast.ObjectExpr(
                        object.fields().stream()
                                .map(field -> new Ast.ObjectField(
                                        field.name(),
                                        substitute(field.value(), substitutions)))
                                .toList());
            }
            if (expression instanceof Ast.LambdaExpr lambda) {
                List<Ast.Param> parameters = lambda.parameters().stream()
                        .map(parameter -> new Ast.Param(
                                substitute(parameter.type(), substitutions),
                                parameter.name(),
                                parameter.structural(),
                                parameter.mutable()))
                        .toList();
                return new Ast.LambdaExpr(
                        parameters,
                        lambda.expressionBody() == null
                                ? null
                                : substitute(lambda.expressionBody(), substitutions),
                        lambda.blockBody() == null
                                ? null
                                : substituteStatements(lambda.blockBody(), substitutions));
            }
            throw new IllegalStateException(
                    "unsupported expression node during trait substitution: "
                            + expression.getClass().getName());
        }

        private Ast.Decl substituteLocalDecl(
                Ast.Decl declaration,
                Map<String, Ast.TypeRef> substitutions) {
            if (declaration instanceof Ast.TypeAliasDecl alias) {
                Map<String, Ast.TypeRef> effective = withoutGenerics(
                        substitutions, alias.genericParameters());
                return new Ast.TypeAliasDecl(
                        alias.name(),
                        alias.genericParameters(),
                        substitute(alias.target(), effective));
            }

            if (declaration instanceof Ast.InterfaceDecl iface) {
                Map<String, Ast.TypeRef> effective = withoutGenerics(
                        substitutions, iface.genericParameters());
                List<Ast.InterfaceMember> members = iface.members().stream()
                        .map(member -> {
                            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                                Map<String, Ast.TypeRef> methodEffective = withoutGenerics(
                                        effective, fn.genericParameters());
                                List<Ast.Param> parameters = fn.parameters().stream()
                                        .map(parameter -> new Ast.Param(
                                                substitute(parameter.type(), methodEffective),
                                                parameter.name(),
                                                parameter.structural(),
                                                parameter.mutable()))
                                        .toList();
                                return (Ast.InterfaceMember) new Ast.InterfaceFunctionDecl(
                                        fn.name(),
                                        fn.genericParameters(),
                                        parameters,
                                        substitute(fn.returnType(), methodEffective));
                            }
                            Ast.InterfaceFieldDecl field = (Ast.InterfaceFieldDecl) member;
                            return (Ast.InterfaceMember) new Ast.InterfaceFieldDecl(
                                    field.name(),
                                    substitute(field.type(), effective));
                        })
                        .toList();
                return new Ast.InterfaceDecl(
                        iface.name(),
                        iface.visibility(),
                        iface.genericParameters(),
                        iface.parents().stream()
                                .map(parent -> substitute(parent, effective))
                                .toList(),
                        members);
            }

            if (declaration instanceof Ast.TraitDecl trait) {
                Map<String, Ast.TypeRef> effective = withoutGenerics(
                        substitutions, trait.genericParameters());
                return new Ast.TraitDecl(
                        trait.name(),
                        trait.genericParameters(),
                        trait.interfaces().stream()
                                .map(ref -> substitute(ref, effective))
                                .toList(),
                        trait.traits().stream()
                                .map(ref -> substitute(ref, effective))
                                .toList(),
                        trait.fields().stream()
                                .map(field -> substitute(field, effective))
                                .toList(),
                        trait.methods().stream()
                                .map(method -> substitute(method, effective))
                                .toList());
            }

            if (declaration instanceof Ast.ClassDecl klass && klass.isStruct()) {
                Map<String, Ast.TypeRef> effective = withoutGenerics(
                        substitutions, klass.genericParameters());
                return new Ast.ClassDecl(
                        klass.name(),
                        klass.kind(),
                        klass.isAbstract(),
                        klass.genericParameters(),
                        klass.parents().stream()
                                .map(ref -> substitute(ref, effective))
                                .toList(),
                        klass.interfaces().stream()
                                .map(ref -> substitute(ref, effective))
                                .toList(),
                        klass.traits().stream()
                                .map(ref -> substitute(ref, effective))
                                .toList(),
                        klass.fields().stream()
                                .map(field -> substitute(field, effective))
                                .toList(),
                        klass.methods().stream()
                                .map(method -> substitute(method, effective))
                                .toList());
            }

            throw new IllegalStateException(
                    "unsupported callable-local declaration during trait substitution: "
                            + declaration.getClass().getName());
        }

        private Map<String, Ast.TypeRef> withoutGenerics(
                Map<String, Ast.TypeRef> substitutions,
                List<String> genericParameters) {
            Map<String, Ast.TypeRef> effective = new LinkedHashMap<>(substitutions);
            for (String generic : genericParameters) effective.remove(generic);
            return effective;
        }

        private Ast.FieldDecl substitute(
                Ast.FieldDecl field,
                Map<String, Ast.TypeRef> substitutions) {
            return new Ast.FieldDecl(
                    field.name(),
                    field.visibility(),
                    field.bindingKind(),
                    substitute(field.type(), substitutions),
                    substitute(field.initializer(), substitutions),
                    field.compositionOwner());
        }

        private Ast.MethodDecl substitute(
                Ast.MethodDecl method,
                Map<String, Ast.TypeRef> substitutions) {
            Map<String, Ast.TypeRef> effective =
                    new LinkedHashMap<>(substitutions);
            for (String methodGeneric : method.genericParameters()) {
                effective.remove(methodGeneric);
            }

            List<Ast.Param> parameters = method.parameters().stream()
                    .map(parameter -> new Ast.Param(
                            substitute(parameter.type(), effective),
                            parameter.name(),
                            parameter.structural(),
                            parameter.mutable()))
                    .toList();

            List<Ast.Annotation> annotations = method.annotations().stream()
                    .map(annotation -> new Ast.Annotation(
                            annotation.name(),
                            annotation.arguments().stream()
                                    .map(argument -> substitute(argument, effective))
                                    .toList()))
                    .toList();

            return new Ast.MethodDecl(
                    method.name(),
                    method.visibility(),
                    method.isStatic(),
                    method.isAbstract(),
                    method.async(),
                    substitute(method.explicitReceiverType(), effective),
                    method.genericParameters(),
                    parameters,
                    substitute(method.returnType(), effective),
                    annotations,
                    substituteStatements(method.body(), effective),
                    method.compositionOwner());
        }

        private Ast.MethodDecl withCompositionOwner(Ast.MethodDecl method, String owner) {
            return new Ast.MethodDecl(
                    method.name(),
                    method.visibility(),
                    method.isStatic(),
                    method.isAbstract(),
                    method.async(),
                    method.explicitReceiverType(),
                    method.genericParameters(),
                    method.parameters(),
                    method.returnType(),
                    method.annotations(),
                    method.body(),
                    owner);
        }

        private void validateTraitSelfAccess(
                String traitName,
                List<Ast.Stmt> statements,
                Set<String> fields,
                Set<String> methods) {
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.BindingStmt binding) {
                    validateTraitSelfAccess(traitName, binding.initializer(), fields, methods);
                } else if (statement instanceof Ast.DestructureStmt destructure) {
                    validateTraitSelfAccess(traitName, destructure.initializer(), fields, methods);
                } else if (statement instanceof Ast.ReturnStmt returned && returned.value() != null) {
                    validateTraitSelfAccess(traitName, returned.value(), fields, methods);
                } else if (statement instanceof Ast.ExprStmt expression) {
                    validateTraitSelfAccess(traitName, expression.expression(), fields, methods);
                } else if (statement instanceof Ast.DeferStmt deferred) {
                    validateTraitSelfAccess(traitName, deferred.expression(), fields, methods);
                } else if (statement instanceof Ast.IfStmt conditional) {
                    for (Ast.IfBranch branch : conditional.branches()) {
                        validateTraitSelfAccess(traitName, branch.condition(), fields, methods);
                        validateTraitSelfAccess(traitName, branch.body(), fields, methods);
                    }
                    validateTraitSelfAccess(traitName, conditional.elseBody(), fields, methods);
                } else if (statement instanceof Ast.TryStmt attempted) {
                    validateTraitSelfAccess(traitName, attempted.body(), fields, methods);
                    validateTraitSelfAccess(traitName, attempted.catchBody(), fields, methods);
                    validateTraitSelfAccess(traitName, attempted.finallyBody(), fields, methods);
                } else if (statement instanceof Ast.ForOfStmt loop) {
                    validateTraitSelfAccess(traitName, loop.iterable(), fields, methods);
                    validateTraitSelfAccess(traitName, loop.body(), fields, methods);
                } else if (statement instanceof Ast.ForStmt loop) {
                    if (loop.initializer() != null) {
                        validateTraitSelfAccess(traitName, List.of(loop.initializer()), fields, methods);
                    }
                    if (loop.condition() != null) {
                        validateTraitSelfAccess(traitName, loop.condition(), fields, methods);
                    }
                    if (loop.update() != null) {
                        validateTraitSelfAccess(traitName, loop.update(), fields, methods);
                    }
                    validateTraitSelfAccess(traitName, loop.body(), fields, methods);
                }
            }
        }

        private void validateTraitSelfAccess(
                String traitName,
                Ast.Expr expression,
                Set<String> fields,
                Set<String> methods) {
            if (expression instanceof Ast.MemberExpr member) {
                if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("self")) {
                    if (!fields.contains(member.member()) && !methods.contains(member.member())) {
                        throw new IllegalArgumentException(
                                "trait '" + traitName + "' accesses undeclared self member '"
                                        + member.member()
                                        + "'; declare trait state or an abstract method requirement first");
                    }
                }
                validateTraitSelfAccess(traitName, member.receiver(), fields, methods);
            } else if (expression instanceof Ast.CallExpr call) {
                validateTraitSelfAccess(traitName, call.callee(), fields, methods);
                for (Ast.Expr argument : call.arguments()) {
                    validateTraitSelfAccess(traitName, argument, fields, methods);
                }
            } else if (expression instanceof Ast.BinaryExpr binary) {
                validateTraitSelfAccess(traitName, binary.left(), fields, methods);
                validateTraitSelfAccess(traitName, binary.right(), fields, methods);
            } else if (expression instanceof Ast.UnaryExpr unary) {
                validateTraitSelfAccess(traitName, unary.operand(), fields, methods);
            } else if (expression instanceof Ast.AssignExpr assignment) {
                validateTraitSelfAccess(traitName, assignment.target(), fields, methods);
                validateTraitSelfAccess(traitName, assignment.value(), fields, methods);
            } else if (expression instanceof Ast.ConditionalExpr conditional) {
                validateTraitSelfAccess(traitName, conditional.condition(), fields, methods);
                validateTraitSelfAccess(traitName, conditional.whenTrue(), fields, methods);
                validateTraitSelfAccess(traitName, conditional.whenFalse(), fields, methods);
            } else if (expression instanceof Ast.IndexExpr indexed) {
                validateTraitSelfAccess(traitName, indexed.receiver(), fields, methods);
                validateTraitSelfAccess(traitName, indexed.index(), fields, methods);
            } else if (expression instanceof Ast.NewExpr created) {
                for (Ast.Expr argument : created.arguments()) {
                    validateTraitSelfAccess(traitName, argument, fields, methods);
                }
            } else if (expression instanceof Ast.AwaitExpr awaited) {
                validateTraitSelfAccess(traitName, awaited.expression(), fields, methods);
            } else if (expression instanceof Ast.ListExpr list) {
                for (Ast.Expr item : list.elements()) {
                    validateTraitSelfAccess(traitName, item, fields, methods);
                }
            } else if (expression instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr item : tuple.elements()) {
                    validateTraitSelfAccess(traitName, item, fields, methods);
                }
            } else if (expression instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) {
                    validateTraitSelfAccess(traitName, field.value(), fields, methods);
                }
            } else if (expression instanceof Ast.LambdaExpr lambda) {
                if (lambda.expressionBody() != null) {
                    validateTraitSelfAccess(traitName, lambda.expressionBody(), fields, methods);
                }
                if (lambda.blockBody() != null) {
                    validateTraitSelfAccess(traitName, lambda.blockBody(), fields, methods);
                }
            }
        }

        private List<MethodEntry> dedupeOrigins(List<MethodEntry> entries) {
            LinkedHashMap<String, MethodEntry> result = new LinkedHashMap<>();
            for (MethodEntry entry : entries) {
                result.putIfAbsent(entry.origin(), entry);
            }
            return List.copyOf(result.values());
        }

        private String methodKey(Ast.MethodDecl method) {
            return method.name() + "/" + method.arity();
        }

        private void requireCompatible(
                Ast.MethodDecl left,
                Ast.MethodDecl right,
                String where) {
            if (!sameContract(left, right)) {
                throw new IllegalArgumentException(
                        "incompatible trait method contracts in " + where
                                + ": " + describe(left) + " vs " + describe(right));
            }
        }

        private boolean sameContract(
                Ast.MethodDecl left,
                Ast.MethodDecl right) {
            if (left.async() != right.async()) return false;
            if (!left.genericParameters().equals(right.genericParameters())) return false;
            if (!java.util.Objects.equals(
                    left.explicitReceiverType(),
                    right.explicitReceiverType())) return false;
            if (!left.returnType().equals(right.returnType())) return false;
            if (left.parameters().size() != right.parameters().size()) return false;

            for (int i = 0; i < left.parameters().size(); i++) {
                Ast.Param a = left.parameters().get(i);
                Ast.Param b = right.parameters().get(i);
                if (!a.type().equals(b.type())
                        || a.structural() != b.structural()
                        || a.mutable() != b.mutable()) return false;
            }
            return true;
        }

        private String describe(Ast.MethodDecl method) {
            return method.name()
                    + method.parameters().stream()
                            .map(parameter -> parameter.type().toString())
                            .toList()
                    + " => " + method.returnType();
        }
    }
}
