package dev.oreslang.parser;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;

import static dev.oreslang.parser.Token.Type.*;

public final class Parser {
    public static final String ROOT_MODULE = "__root__";

    private final List<Token> tokens;
    private int current;
    private boolean suppressRefinementOperators;

    public Parser(List<Token> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    public static Ast.Program parse(String source) {
        return new Parser(new Lexer(source).scan()).parseProgram();
    }

    public Ast.Program parseProgram() {
        String namespace = null;
        if (match(NAMESPACE)) {
            namespace = consume(IDENT, "expected flat namespace name").lexeme();
            if (check(DOT)) throw error(peek(), "namespaces cannot be nested or dotted");
            consume(SEMICOLON, "namespace declaration must end with ';'");
        }

        List<Ast.ImportDecl> imports = new ArrayList<>();
        while (match(IMPORT)) imports.add(parseImport());

        List<Ast.ModuleDecl> modules = new ArrayList<>();
        List<Ast.Decl> rootDeclarations = new ArrayList<>();

        while (!check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers modifiers = parseModifiers();

            if (match(DEFINE)) {
                if (modifiers.shared) {
                    throw error(previous(), "'shared' must modify an actor declaration; use 'shared actor <Name>'");
                }
                boolean afterDefineAbstract = match(ABSTRACT);
                if (match(MODULE)) {
                    if (modifiers.visibility != Ast.Visibility.PRIVATE || modifiers.async || modifiers.generator || modifiers.nonLexical || modifiers.isStatic || modifiers.isAbstract || modifiers.shared) {
                        throw error(previous(), "modules do not accept function/class modifiers");
                    }
                    modules.add(parseModule(annotations));
                    continue;
                }
                if (match(CLASS)) {
                    if (modifiers.generator) throw error(previous(), "'generator' applies only to fnc or routine declarations, not classes or class methods");
                    if (modifiers.nonLexical) throw error(previous(), "'nlex' applies only to fnc, routine, or lambda");
                    rootDeclarations.add(parseClass(modifiers.isAbstract || afterDefineAbstract));
                    continue;
                }
                if (match(INTERFACE)) {
                    if (modifiers.generator) throw error(previous(), "'generator' applies only to fnc or routine declarations");
                    if (modifiers.nonLexical) throw error(previous(), "'nlex' applies only to fnc, routine, or lambda");
                    rootDeclarations.add(parseInterface(modifiers.visibility));
                    continue;
                }
                throw error(previous(), "expected module, class, or interface after 'define'");
            }

            Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
            if (declaration == null) throw error(peek(), "expected module or top-level declaration");
            rootDeclarations.add(declaration);
        }

        if (!rootDeclarations.isEmpty()) {
            modules.add(new Ast.ModuleDecl(ROOT_MODULE, List.of(), rootDeclarations));
        }
        if (modules.isEmpty()) throw error(peek(), "a source file must define at least one module or top-level declaration");
        return new Ast.Program(namespace, imports, modules);
    }

    private Ast.ImportDecl parseImport() {
        Ast.ImportKind kind;
        List<String> names = new ArrayList<>();
        boolean wildcard = false;
        String namespace = null;

        if (match(STAR)) {
            kind = Ast.ImportKind.ALL;
            wildcard = true;
            consume(AS, "'import *' requires 'as <namespace>'");
            namespace = consume(IDENT, "expected import namespace").lexeme();
        } else {
            if (isLegacyFnSpelling()) {
                throw error(peek(), "function imports use 'import fnc', not 'import fn'");
            }
            if (match(MODULE)) kind = Ast.ImportKind.MODULE;
            else if (match(ACTOR)) kind = Ast.ImportKind.ACTOR;
            else if (match(CLASS)) kind = Ast.ImportKind.CLASS;
            else if (match(FNC)) kind = Ast.ImportKind.FUNCTION;
            else if (match(INTERFACE)) kind = Ast.ImportKind.INTERFACE;
            else if (match(TRAIT)) kind = Ast.ImportKind.TRAIT;
            else if (match(STRUCT)) kind = Ast.ImportKind.STRUCT;
            else if (match(TYPE)) kind = Ast.ImportKind.TYPE;
            else if (match(TYPES)) kind = Ast.ImportKind.TYPES;
            else throw error(peek(), "expected module, actor, class, fnc, interface, trait, struct, type, types, or * after import");

            if (match(STAR)) {
                wildcard = true;
                consume(AS, "wildcard import requires 'as <namespace>'");
                namespace = consume(IDENT, "expected import namespace").lexeme();
            } else {
                parseImportSelection(kind, names);
            }

            if (!wildcard && match(AS)) {
                if (names.size() != 1) {
                    throw error(previous(), "named import aliases require exactly one selected name");
                }
                namespace = consume(IDENT, "expected import alias").lexeme();
            }
        }

        consume(FROM, "expected 'from' in import");
        String path = consume(STRING, "expected quoted import path").lexeme();
        if (path.isBlank()) throw error(previous(), "import path cannot be empty");
        consume(SEMICOLON, "expected ';' after import");
        return new Ast.ImportDecl(kind, names, wildcard, namespace, path);
    }

    private void parseImportSelection(Ast.ImportKind kind, List<String> names) {
        Token.Type closing = null;
        if (match(LBRACE)) closing = RBRACE;
        else if (match(LPAREN)) closing = RPAREN;

        if (closing != null) {
            if (check(closing)) throw error(peek(), "import selection cannot be empty");
            do names.add(consumeImportName(kind)); while (match(COMMA));
            consume(closing, closing == RBRACE
                    ? "expected '}' after imported names"
                    : "expected ')' after imported names");
            return;
        }

        names.add(consumeImportName(kind));
        while (match(COMMA)) names.add(consumeImportName(kind));
    }

    private String consumeImportName(Ast.ImportKind kind) {
        if (kind == Ast.ImportKind.FUNCTION) {
            return consumeCallableName("expected imported function name");
        }
        return consume(IDENT, "expected imported name").lexeme();
    }

    private Ast.ModuleDecl parseModule(List<Ast.Annotation> annotations) {
        String name = consume(IDENT, "expected flat module name").lexeme();
        if (check(DOT)) throw error(peek(), "modules cannot be nested or dotted");
        List<Ast.Decl> declarations = new ArrayList<>();
        while (!check(END) && !check(EOF)) declarations.add(parseModuleMember());
        consume(END, "expected 'end' to close module " + name);
        return new Ast.ModuleDecl(name, annotations, declarations);
    }

    private Ast.Decl parseModuleMember() {
        List<Ast.Annotation> annotations = parseAnnotations();
        Modifiers modifiers = parseModifiers();

        if (match(DEFINE)) {
            if (modifiers.shared) {
                throw error(previous(), "'shared' must modify an actor declaration; use 'shared actor <Name>'");
            }
            boolean afterDefineAbstract = match(ABSTRACT);
            if (match(CLASS)) {
                if (modifiers.generator) throw error(previous(), "'generator' applies only to fnc or routine declarations, not classes or class methods");
                if (modifiers.nonLexical) throw error(previous(), "'nlex' applies only to fnc, routine, or lambda");
                return parseClass(modifiers.isAbstract || afterDefineAbstract);
            }
            if (match(INTERFACE)) {
                if (modifiers.generator) throw error(previous(), "'generator' applies only to fnc or routine declarations");
                if (modifiers.nonLexical) throw error(previous(), "'nlex' applies only to fnc, routine, or lambda");
                return parseInterface(modifiers.visibility);
            }
            throw error(previous(), "expected class or interface after 'define'");
        }

        Ast.Decl declaration = parseDeclarationAfterModifiers(annotations, modifiers);
        if (declaration != null) return declaration;
        throw error(peek(), "expected function, routine, class, interface, type, or binding declaration");
    }

    private Ast.Decl parseDeclarationAfterModifiers(List<Ast.Annotation> annotations, Modifiers modifiers) {
        if (isLegacyFnSpelling()) {
            throw error(peek(), "functions are declared with 'fnc', not 'fn'");
        }
        if (match(ACTOR, ISOACTOR)) {
            Token actorToken = previous();
            boolean isolated = actorToken.type() == ISOACTOR;
            if (isolated && modifiers.shared) {
                throw error(actorToken, "'shared isoactor' is contradictory; use either actor/shared actor or isoactor");
            }
            Ast.ActorKind actorKind = isolated ? Ast.ActorKind.PRIVATE : Ast.ActorKind.SHARED;
            if (isLegacyFnSpelling()) {
                throw error(peek(), "actor functions are declared with 'actor fnc', not 'actor fn'");
            }
            if (match(FNC)) return parseFunction(annotations, modifiers, Ast.CallableKind.FNC, actorKind);
            if (match(ROUTINE)) return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE, actorKind);
            if (modifiers.async || modifiers.nonLexical || modifiers.isStatic || modifiers.isAbstract) {
                throw error(actorToken, "actor declarations do not accept async, nlex, static, or abstract modifiers");
            }
            return parseActorClass(actorKind);
        }
        if (modifiers.shared) throw error(previous(), "'shared' must modify an actor declaration");
        if (match(FNC)) return parseFunction(annotations, modifiers, Ast.CallableKind.FNC);
        if (match(ROUTINE)) return parseFunction(annotations, modifiers, Ast.CallableKind.ROUTINE);
        if (modifiers.nonLexical) throw error(peek(), "'nlex' applies only to fnc, routine, or lambda");
        if (modifiers.generator) throw error(peek(), "'generator' applies only to fnc or routine declarations");
        if (match(INTERFACE)) return parseInterface(modifiers.visibility);
        if (match(TYPE)) return parseTypeAlias();
        if (isBindingKind(peek().type())) return parseModuleBinding(annotations, modifiers.visibility);
        return null;
    }

    private Ast.FunctionDecl parseFunction(List<Ast.Annotation> annotations, Modifiers modifiers, Ast.CallableKind kind) {
        return parseFunction(annotations, modifiers, kind, Ast.ActorKind.NONE);
    }

    private Ast.FunctionDecl parseFunction(
            List<Ast.Annotation> annotations,
            Modifiers modifiers,
            Ast.CallableKind kind,
            Ast.ActorKind actorKind) {
        if (modifiers.isStatic) throw error(previous(), "'static fnc' is only valid inside a class");
        if (modifiers.isAbstract) throw error(previous(), "top-level/module callables cannot be abstract");
        String name = consumeCallableName("expected callable name");
        List<String> generics = parseGenericParameters();

        if (match(EQUAL)) {
            consume(PIPE, "lambda-style callable declarations use '= |Type name, ...| -> [ReturnType] { ... }'");
            List<Ast.Param> params = parseDeclaredPipeParameters();
            consume(PIPE, "expected closing '|' in lambda-style callable declaration");
            consume(ARROW, "lambda-style callable declarations use the slim arrow '->'");
            Ast.TypeRef returnType = check(LBRACE)
                    ? Ast.TypeRef.simple("void")
                    : parseTypeRef();
            List<Ast.Stmt> body = parseBlock();
            return new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, modifiers.generator, modifiers.nonLexical, actorKind,
                    generics, params, returnType, annotations, body);
        }

        consume(LPAREN, "expected '(' after callable name or '=' for lambda-style declaration");
        java.util.Set<String> structuralNames = structuralAnnotationNames(annotations);
        List<Ast.Param> params = applyStructuralAnnotations(parseParametersUntil(RPAREN, structuralNames), annotations);
        consume(RPAREN, "expected ')' after parameters");
        Ast.TypeRef returnType = parseReturnType(annotations);
        List<Ast.Stmt> body = parseBlock();
        return new Ast.FunctionDecl(name, kind, modifiers.visibility, modifiers.async, modifiers.generator, modifiers.nonLexical, actorKind, generics, params,
                returnType, annotations, body);
    }

    private Ast.ClassDecl parseClass(boolean isAbstract) {
        String name = consume(IDENT, "expected class name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> interfaces = match(IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();
        consume(AS, "expected 'as' after class header");
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();

        while (!check(END) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (isLegacyFnSpelling()) {
                if (mods.isStatic) throw error(peek(), "static class functions use 'static fnc', not 'static fn'");
                throw error(peek(), "instance methods omit 'fn'/'fnc'; declare the method name directly");
            }
            if (mods.generator) throw error(peek(), "'generator' is not permitted on class methods or static class fnc; declare a module/top-level generator fnc or routine");
            if (mods.nonLexical) throw error(peek(), "'nlex' is unnecessary on class members; methods/static fnc never capture enclosing local scopes");
            if (isBindingKind(peek().type())) {
                if (mods.isStatic) throw error(peek(), "static data members are not implemented yet; static class functions use 'static fnc'");
                fields.add(parseField(annotations, mods.visibility));
                continue;
            }
            if (check(IDENT) && checkNext(COLON)) {
                if (mods.isStatic) throw error(peek(), "static data members are not implemented yet; static class functions use 'static fnc'");
                fields.add(parseColonField(annotations, mods.visibility));
                continue;
            }
            if (mods.isStatic) {
                consume(FNC, "static class functions must be declared with 'static fnc'");
                if (mods.isAbstract) throw error(previous(), "static class functions cannot be abstract");
            } else if (check(FNC)) {
                throw error(peek(), "instance methods omit 'fnc'; use 'static fnc' only for class functions");
            }
            methods.add(parseMethod(annotations, mods));
        }
        consume(END, "expected 'end' to close class " + name);
        return new Ast.ClassDecl(name, isAbstract, Ast.ActorKind.NONE, generics, parents, interfaces, fields, methods);
    }

    private Ast.ClassDecl parseActorClass(Ast.ActorKind actorKind) {
        String name = consume(IDENT, "expected actor name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        List<Ast.TypeRef> interfaces = match(IMPLEMENTS, IMPL) ? parseTypeRefList() : List.of();

        boolean braceStyle = match(LBRACE);
        Token.Type terminator = braceStyle ? RBRACE : END;
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.MethodDecl> methods = new ArrayList<>();

        while (!check(terminator) && !check(EOF)) {
            List<Ast.Annotation> annotations = parseAnnotations();
            Modifiers mods = parseModifiers();
            if (isLegacyFnSpelling()) {
                if (mods.isStatic) throw error(peek(), "static actor functions use 'static fnc', not 'static fn'");
                throw error(peek(), "actor methods omit 'fn'/'fnc'; declare the method name directly");
            }
            if (mods.shared) throw error(previous(), "'shared' is only valid on an actor declaration, not its members");
            if (mods.nonLexical) throw error(previous(), "'nlex' is unnecessary on actor members; actor methods already execute in the actor turn scope");

            if (isBindingKind(peek().type())) {
                if (mods.isStatic) throw error(peek(), "actor state cannot be static");
                if (mods.visibility == Ast.Visibility.PUBLIC) {
                    throw error(peek(), "actor state fields are private; expose state through actor methods");
                }
                fields.add(parseField(annotations, mods.visibility));
                continue;
            }

            if (mods.isAbstract) throw error(peek(), "actor methods cannot be abstract");
            if (mods.isStatic) {
                consume(FNC, "static actor functions must be declared with 'static fnc'");
            } else {
                match(FNC);
            }
            methods.add(parseMethod(annotations, mods));
        }

        consume(terminator, braceStyle
                ? "expected '}' to close actor " + name
                : "expected 'end' to close actor " + name);
        return new Ast.ClassDecl(name, false, actorKind, generics, parents, interfaces, fields, methods);
    }

    private Ast.InterfaceDecl parseInterface(Ast.Visibility visibility) {
        String name = consume(IDENT, "expected interface name").lexeme();
        List<String> generics = parseGenericParameters();
        List<Ast.TypeRef> parents = match(EXTENDS) ? parseTypeRefList() : List.of();
        boolean braceStyle = match(LBRACE);
        Token.Type terminator = braceStyle ? RBRACE : END;

        List<Ast.InterfaceMember> members = new ArrayList<>();
        while (!check(terminator) && !check(EOF)) {
            parseAnnotations();
            parseModifiers();

            if (isLegacyFnSpelling()) {
                throw error(peek(), "interface functions are declared with 'fnc', not 'fn'");
            }
            if (match(FNC)) {
                String memberName = consumeCallableName("expected interface function name");
                List<String> memberGenerics = parseGenericParameters();
                consume(LPAREN, "expected '(' after interface function name");
                List<Ast.Param> params = parseParametersUntil(RPAREN);
                consume(RPAREN, "expected ')' after interface parameters");
                Ast.TypeRef returns = match(FAT_ARROW) ? parseTypeRef() : Ast.TypeRef.simple("void");
                consumeMemberTerminator(terminator, "interface function signature should end with ';'");
                members.add(new Ast.InterfaceFunctionDecl(memberName, memberGenerics, params, returns));
                continue;
            }

            if (check(IDENT) && checkNext(COLON)) {
                String fieldName = advance().lexeme();
                consume(COLON, "expected ':' after interface field name");
                Ast.TypeRef type = parseTypeRef();
                consumeMemberTerminator(terminator, "interface field signature should end with ';'");
                members.add(new Ast.InterfaceFieldDecl(fieldName, type));
                continue;
            }

            if (isBindingKind(peek().type())) advance();
            Ast.TypeRef type = parseTypeRef();
            String fieldName = consume(IDENT, "expected interface field name").lexeme();
            consumeMemberTerminator(terminator, "interface field signature should end with ';'");
            members.add(new Ast.InterfaceFieldDecl(fieldName, type));
        }

        consume(terminator, braceStyle ? "expected '}' to close interface " + name : "expected 'end' to close interface " + name);
        return new Ast.InterfaceDecl(name, visibility, generics, parents, members);
    }

    private List<Ast.TypeRef> parseTypeRefList() {
        List<Ast.TypeRef> refs = new ArrayList<>();
        do refs.add(parseTypeRef()); while (match(COMMA));
        return refs;
    }

    private Ast.FieldDecl parseField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) {
            name = advance().lexeme();
        } else {
            type = parseTypeRef();
            name = consume(IDENT, "expected field name").lexeme();
        }
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        if (type == null && initializer == null) {
            throw error(previous(), "inferred field '" + name + "' requires an initializer");
        }
        consumeStatementTerminator("field declaration should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer);
    }

    private Ast.FieldDecl parseColonField(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        String name = consume(IDENT, "expected field name").lexeme();
        consume(COLON, "expected ':' after field name");
        Ast.TypeRef type = parseTypeRef();
        Ast.Expr initializer = match(EQUAL) ? parseExpression() : null;
        Ast.BindingKind kind = hasAnnotation(annotations, "FromJson") ? Ast.BindingKind.LET : Ast.BindingKind.VAL;
        consumeClassFieldTerminator("field declaration should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer);
    }

    private Ast.FieldDecl parseModuleBinding(List<Ast.Annotation> annotations, Ast.Visibility visibility) {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        consume(EQUAL, "module bindings require an initializer");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("module binding should end with ';'");
        return new Ast.FieldDecl(name, visibility, kind, type, annotations, initializer);
    }

    private Ast.MethodDecl parseMethod(List<Ast.Annotation> annotations, Modifiers mods) {
        String name = parseMethodName();
        List<String> generics = parseGenericParameters();
        consume(LPAREN, "expected '(' after method name");

        Ast.TypeRef receiverType = null;
        List<Ast.Param> params;
        if (mods.isStatic && check(SELF)) throw error(peek(), "static class functions do not have a self receiver");
        if (match(SELF)) {
            receiverType = parseTypeRef();
            consume(RPAREN, "expected ')' after explicit self receiver");
            consume(LPAREN, "explicit receiver form is method(self Type)(params)");
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        } else {
            params = parseParametersUntil(RPAREN);
            consume(RPAREN, "expected ')' after method parameters");
        }

        Ast.TypeRef returnType = parseReturnType(annotations);
        List<Ast.Stmt> body;
        if (mods.isAbstract) {
            consumeStatementTerminator("abstract method should end with ';'");
            body = List.of();
        } else body = parseBlock();
        return new Ast.MethodDecl(name, mods.visibility, mods.isStatic, mods.isAbstract, mods.async,
                receiverType, generics, params, returnType, annotations, body);
    }

    private String parseMethodName() {
        if (match(LBRACKET)) {
            String namespace = consume(IDENT, "expected symbol namespace").lexeme();
            consume(DOT, "expected '.' in symbol method");
            String symbol = consume(IDENT, "expected symbol name").lexeme();
            consume(RBRACKET, "expected ']' after symbol method");
            if (!namespace.equals("Symbol")) throw error(previous(), "symbol methods must use Symbol.<name>");
            return namespace + "." + symbol;
        }
        return consumeCallableName("expected method name (methods omit 'fnc')");
    }

    private Ast.TypeAliasDecl parseTypeAlias() {
        String name = consume(IDENT, "expected type alias name").lexeme();
        List<String> generics = parseGenericParameters();
        consume(EQUAL, "expected '=' in type alias");
        Ast.TypeRef target = parseTypeRef();
        consumeStatementTerminator("type alias should end with ';'");
        return new Ast.TypeAliasDecl(name, generics, target);
    }

    private List<Ast.Annotation> parseAnnotations() {
        List<Ast.Annotation> result = new ArrayList<>();
        while (match(AT)) {
            String name = consume(IDENT, "expected annotation name").lexeme();
            List<Ast.TypeRef> args = new ArrayList<>();
            Token.Type close = null;
            if (match(LT)) close = GT;
            else if (match(LPAREN)) close = RPAREN;
            if (close != null) {
                if (!check(close)) do args.add(parseTypeRef()); while (match(COMMA));
                consume(close, "expected annotation terminator");
            }
            result.add(new Ast.Annotation(name, args));
        }
        return result;
    }

    private Modifiers parseModifiers() {
        Ast.Visibility visibility = Ast.Visibility.PRIVATE;
        boolean async = false;
        boolean generator = false;
        boolean nonLexical = false;
        boolean isStatic = false;
        boolean isAbstract = false;
        boolean shared = false;
        boolean visibilitySeen = false;
        boolean asyncSeen = false;
        boolean generatorSeen = false;
        boolean nonLexicalSeen = false;
        boolean staticSeen = false;
        boolean abstractSeen = false;
        boolean sharedSeen = false;

        while (true) {
            if (match(PUB)) {
                if (visibilitySeen) throw error(previous(), "duplicate/conflicting visibility modifier");
                visibilitySeen = true;
                visibility = Ast.Visibility.PUBLIC;
            } else if (match(PRIVATE)) {
                if (visibilitySeen) throw error(previous(), "duplicate/conflicting visibility modifier");
                visibilitySeen = true;
                visibility = Ast.Visibility.PRIVATE;
            } else if (match(ASYNC)) {
                if (asyncSeen) throw error(previous(), "duplicate 'async' modifier");
                asyncSeen = true;
                async = true;
            } else if (match(GENERATOR)) {
                if (generatorSeen) throw error(previous(), "duplicate 'generator' modifier");
                generatorSeen = true;
                generator = true;
            } else if (match(NLEX)) {
                if (nonLexicalSeen) throw error(previous(), "duplicate 'nlex' modifier");
                nonLexicalSeen = true;
                nonLexical = true;
            } else if (match(STATIC)) {
                if (staticSeen) throw error(previous(), "duplicate 'static' modifier");
                staticSeen = true;
                isStatic = true;
            } else if (match(ABSTRACT)) {
                if (abstractSeen) throw error(previous(), "duplicate 'abstract' modifier");
                abstractSeen = true;
                isAbstract = true;
            } else if (matchContextualShared()) {
                if (sharedSeen) throw error(previous(), "duplicate 'shared' modifier");
                sharedSeen = true;
                shared = true;
            } else {
                break;
            }
        }
        return new Modifiers(visibility, async, generator, nonLexical, isStatic, isAbstract, shared);
    }

    private Ast.TypeRef parseReturnType(List<Ast.Annotation> annotations) {
        Ast.TypeRef annotated = null;
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals("Ret")) {
                if (annotation.arguments().size() != 1) throw error(previous(), "@Ret requires exactly one type");
                annotated = annotation.arguments().getFirst();
            }
        }

        if (check(FAT_ARROW)) {
            throw error(peek(),
                    "fat arrow '=>' is reserved for function types/interface callable signatures; "
                            + "named executable callables use ': ReturnType' or '-> ReturnType'");
        }

        Ast.TypeRef declared = null;
        if (match(COLON) || match(ARROW)) {
            declared = parseTypeRef();
        }
        if (annotated != null && declared != null && !sameType(annotated, declared)) {
            throw error(previous(), "@Ret type and declared return type disagree");
        }
        return declared != null ? declared : annotated != null ? annotated : Ast.TypeRef.simple("void");
    }

    private List<Ast.Param> parseDeclaredPipeParameters() {
        if (check(PIPE)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            Ast.TypeRef type = parseTypeRef();
            boolean mutable = match(MUT);
            String name = consume(IDENT, "lambda-style callable declaration parameters require 'Type name'").lexeme();
            params.add(new Ast.Param(type, name, false, mutable));
        } while (match(COMMA));
        return List.copyOf(params);
    }

    private boolean sameType(Ast.TypeRef a, Ast.TypeRef b) {
        return a.name().equals(b.name()) && a.arguments().equals(b.arguments()) && a.inferArguments() == b.inferArguments();
    }

    private List<String> parseGenericParameters() {
        if (!match(LT)) return List.of();
        List<String> names = new ArrayList<>();
        do names.add(consume(IDENT, "expected generic parameter name").lexeme()); while (match(COMMA));
        consume(GT, "expected '>' after generic parameters");
        return names;
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator) {
        return parseParametersUntil(terminator, java.util.Set.of());
    }

    private List<Ast.Param> parseParametersUntil(Token.Type terminator, java.util.Set<String> annotationStructuralNames) {
        if (check(terminator)) return List.of();
        List<Ast.Param> params = new ArrayList<>();
        do {
            boolean structural = false;

            // Name-first structural spelling: y structural Foo
            if (check(IDENT) && checkNextLexeme("structural")) {
                String name = advance().lexeme();
                Token marker = consume(IDENT, "expected structural");
                if (!marker.lexeme().equals("structural")) throw error(marker, "expected structural");
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                params.add(new Ast.Param(type, name, true, mutable));
                continue;
            }

            // Function annotation spelling: @AllowStructural(y) fnc x(y Foo)
            if (check(IDENT) && annotationStructuralNames.contains(peek().lexeme()) && checkNext(IDENT)) {
                String name = advance().lexeme();
                Ast.TypeRef type = parseTypeRef();
                boolean mutable = match(MUT);
                params.add(new Ast.Param(type, name, true, mutable));
                continue;
            }

            // Existing type-first spelling: @Structural Foo y
            if (match(AT)) {
                String annotation = consume(IDENT, "expected parameter annotation").lexeme();
                if (!annotation.equals("Structural")) throw error(previous(), "only @Structural is currently supported on parameters");
                structural = true;
            }
            Ast.TypeRef type = parseTypeRef();
            boolean mutable = match(MUT);
            String name = consume(IDENT, "expected parameter name").lexeme();
            params.add(new Ast.Param(type, name, structural, mutable));
        } while (match(COMMA));
        return params;
    }

    private java.util.Set<String> structuralAnnotationNames(List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("AllowStructural")) continue;
            for (Ast.TypeRef argument : annotation.arguments()) {
                if (!argument.arguments().isEmpty() || argument.inferArguments() || argument.isStringLiteral()) {
                    throw error(previous(), "@AllowStructural arguments must be parameter names");
                }
                allowed.add(argument.name());
            }
        }
        return java.util.Set.copyOf(allowed);
    }

    private List<Ast.Param> applyStructuralAnnotations(List<Ast.Param> params, List<Ast.Annotation> annotations) {
        java.util.Set<String> allowed = new java.util.HashSet<>(structuralAnnotationNames(annotations));
        if (allowed.isEmpty()) return params;
        java.util.Set<String> found = new java.util.HashSet<>();
        List<Ast.Param> result = new ArrayList<>(params.size());
        for (Ast.Param param : params) {
            boolean structural = param.structural() || allowed.contains(param.name());
            if (allowed.contains(param.name())) found.add(param.name());
            result.add(new Ast.Param(param.type(), param.name(), structural, param.mutable()));
        }
        if (!found.equals(allowed)) {
            java.util.Set<String> missing = new java.util.HashSet<>(allowed);
            missing.removeAll(found);
            throw error(previous(), "@AllowStructural names unknown parameter(s): " + missing);
        }
        return List.copyOf(result);
    }

    private Ast.TypeRef parseTypeRef() {
        Ast.TypeRef first = parseTypeAtom();
        if (!match(PIPE)) return first;

        List<Ast.TypeRef> options = new ArrayList<>();
        options.add(first);
        do options.add(parseTypeAtom()); while (match(PIPE));
        return Ast.TypeRef.union(options);
    }

    private Ast.TypeRef parseTypeAtom() {
        if (match(TYPE)) {
            Ast.TypeRef marked = parseTypeAtom();
            if (marked.name().startsWith("$")) {
                throw error(previous(), "'type' alias marker must prefix a named type");
            }
            return marked;
        }

        if (match(AMP)) {
            boolean mutable = match(MUT);
            return Ast.TypeRef.borrowed(parseTypeAtom(), mutable);
        }
        if (match(STRING)) return Ast.TypeRef.stringLiteral(previous().lexeme());

        if (match(LBRACKET)) {
            List<Ast.TypeRef> elements = new ArrayList<>();
            if (!check(RBRACKET)) {
                do elements.add(parseTypeRef()); while (match(COMMA));
            }
            consume(RBRACKET, "expected ']' after finite tuple type");
            return Ast.TypeRef.tupleType(elements);
        }

        if (match(LBRACE)) {
            java.util.LinkedHashMap<String, Ast.TypeRef> members = new java.util.LinkedHashMap<>();
            if (!check(RBRACE)) {
                do {
                    String field = consumeStaticObjectKeyName("expected record type field name");
                    consume(COLON, "expected ':' after record type field name");
                    Ast.TypeRef fieldType = parseTypeRef();
                    if (members.putIfAbsent(field, fieldType) != null) {
                        throw error(previous(), "duplicate record type field '" + field + "'");
                    }
                } while (match(COMMA));
            }
            consume(RBRACE, "expected '}' after record type");
            return Ast.TypeRef.recordType(members);
        }

        if (match(TYPEOF)) {
            if (isLegacyFnSpelling()) {
                throw error(peek(), "function types use 'typeof fnc(...) => ReturnType', not 'typeof fn(...)'");
            }
            consume(FNC, "typeof function types use 'typeof fnc(...) => ReturnType'");
            return parseFunctionTypeSignature();
        }

        if (check(LPAREN) && looksLikeFunctionType()) return parseFunctionTypeSignature();
        if (match(LPAREN)) {
            Ast.TypeRef grouped = parseTypeRef();
            consume(RPAREN, "expected ')' after grouped type");
            return grouped;
        }

        String name;
        if (match(VOID)) name = "void";
        else if (match(SELF)) name = "self";
        else if (match(NULL)) name = "null";
        else name = parseQualifiedName();

        List<Ast.TypeRef> args = new ArrayList<>();
        boolean infer = false;
        if (match(LT)) {
            if (match(GT)) infer = true;
            else {
                do args.add(parseTypeRef()); while (match(COMMA));
                consume(GT, "expected '>' after type arguments");
            }
        }
        return new Ast.TypeRef(name, args, infer);
    }

    private Ast.TypeRef parseFunctionTypeSignature() {
        consume(LPAREN, "expected '(' in function type");
        List<Ast.TypeRef> params = new ArrayList<>();
        if (!check(RPAREN)) {
            do {
                Ast.TypeRef paramType = parseTypeRef();
                if (check(IDENT)) advance(); // optional documentation-only parameter name
                params.add(paramType);
            } while (match(COMMA));
        }
        consume(RPAREN, "expected ')' after function type parameters");
        consume(FAT_ARROW, "function types use the fat arrow '=>'");
        Ast.TypeRef result = parseTypeRef();
        return Ast.TypeRef.functionType(params, result);
    }

    private boolean looksLikeFunctionType() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == FAT_ARROW;
            }
        }
        return false;
    }

    private String parseQualifiedName() {
        StringBuilder name = new StringBuilder(consume(IDENT, "expected name").lexeme());
        while (match(DOT)) name.append('.').append(consume(IDENT, "expected name after '.'").lexeme());
        return name.toString();
    }

    private List<Ast.Stmt> parseBlock() {
        consume(LBRACE, "expected '{'");
        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(RBRACE) && !check(EOF)) body.add(parseStatement());
        consume(RBRACE, "expected '}'");
        return body;
    }

    private Ast.Stmt parseStatement() {
        // Runtime blocks may create values, never declaration identities. This
        // keeps source semantics compatible with both closed-world AOT and JIT:
        // JIT may specialize known declarations, but it cannot invent modules,
        // namespaces, classes, or trait/impl identities while executing code.
        if (check(NAMESPACE)) {
            throw error(peek(), "namespace declarations are file-scope compile-time declarations and cannot execute inside a block");
        }
        if (check(DEFINE)) {
            Token define = peek();
            Token.Type next = current + 1 < tokens.size() ? tokens.get(current + 1).type() : EOF;
            if (next == MODULE) {
                throw error(define, "module declarations are compile-time declarations and cannot execute inside a block");
            }
            if (next == CLASS) {
                throw error(define, "class declarations are compile-time declarations and cannot execute inside a block");
            }
            if (next == TRAIT) {
                throw error(define, "trait declarations are compile-time declarations and cannot execute inside a block");
            }
        }
        if (check(TRAIT)) {
            throw error(peek(), "trait declarations are static/module-scope declarations; runtime trait creation is forbidden");
        }

        if (isBindingKind(peek().type()) && looksLikePrefixedDestructure()) {
            Ast.BindingKind inherited = parseBindingKind();
            return parseDestructure(check(LBRACKET) ? Ast.DestructureKind.SEQUENCE : Ast.DestructureKind.OBJECT, inherited);
        }
        if (isBindingKind(peek().type())) return parseBindingStatement();
        if (check(LBRACKET) && looksLikeDestructure()) return parseDestructure(Ast.DestructureKind.SEQUENCE, null);
        if (check(LBRACE) && looksLikeDestructure()) return parseDestructure(Ast.DestructureKind.OBJECT, null);
        if (match(RETURN)) {
            Ast.Expr value = check(SEMICOLON) || isSafeStatementBoundary() ? null : parseExpression();
            consumeStatementTerminator("return statement should end with ';'");
            return new Ast.ReturnStmt(value);
        }
        if (match(YIELD)) {
            if (check(SEMICOLON) || isSafeStatementBoundary()) throw error(previous(), "yield requires a value");
            Ast.Expr value = parseExpression();
            consumeStatementTerminator("yield statement should end with ';'");
            return new Ast.YieldStmt(value);
        }
        if (match(DEFER)) {
            Ast.Expr expression = parseExpression();
            consumeStatementTerminator("defer statement should end with ';'");
            return new Ast.DeferStmt(expression);
        }
        if (check(BLOCK) && checkNext(LBRACE)) {
            advance();
            return new Ast.BlockStmt(parseBlock());
        }
        if (match(BREAK)) {
            consumeStatementTerminator("break statement should end with ';'");
            return new Ast.BreakStmt();
        }
        if (match(CONTINUE)) {
            consumeStatementTerminator("continue statement should end with ';'");
            return new Ast.ContinueStmt();
        }
        if (match(IF)) return parseIf();
        if (match(MATCH)) return parseMatch();
        if (match(SWITCH)) return parseSwitch();
        if (match(TRY)) return parseTry();
        if (check(LOOP) && (checkNext(LBRACE) || checkNext(DO))) {
            advance();
            return new Ast.LoopStmt(parseLoopBody());
        }
        if (match(FOR)) {
            boolean asyncIteration = match(AWAIT);
            return parseFor(asyncIteration);
        }

        Ast.Expr expression = parseExpression();
        consumeStatementTerminator("expression statement should end with ';'");
        return new Ast.ExprStmt(expression);
    }

    private Ast.Stmt parseFor(boolean asyncIteration) {
        if (!match(LPAREN)) {
            if (looksLikeUnparenthesizedForOf()) {
                Ast.BindingKind kind = isBindingKind(peek().type())
                        ? parseBindingKind()
                        : Ast.BindingKind.VAL;
                if (looksLikeForOfDestructurePattern()) {
                    List<Ast.DestructureBinding> bindings = parseForSequenceBindings(kind);
                    consume(OF, "iterator destructuring uses 'for [a, b] of iterable'");
                    Ast.Expr iterable = parseExpression();
                    return new Ast.ForOfDestructureStmt(bindings, iterable, asyncIteration, parseLoopBody());
                }
                String name = consume(IDENT, "iterator for-loop requires a binding name").lexeme();
                consume(OF, "iterator for-loop shorthand uses 'for x of iterable'");
                Ast.Expr iterable = parseExpression();
                return new Ast.ForOfStmt(kind, name, iterable, asyncIteration, parseLoopBody());
            }

            if (asyncIteration) {
                throw error(peek(), "'for await' requires iterator syntax: for await x of asyncIterable");
            }
            Ast.Stmt initializer = parseUnparenthesizedForInitializer();
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(LBRACE) || check(DO) ? null : parseForUpdate();
            return new Ast.ForStmt(initializer, condition, update, parseLoopBody());
        }

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            if (check(LBRACKET)) {
                List<Ast.DestructureBinding> bindings = parseForSequenceBindings(kind);
                consume(OF, "expected 'of' after for-of destructure pattern");
                Ast.Expr iterable = parseExpression();
                consume(RPAREN, "expected ')' after for-of header");
                return new Ast.ForOfDestructureStmt(bindings, iterable, asyncIteration, parseLoopBody());
            }
            if (check(IDENT) && checkNext(OF)) {
                String name = advance().lexeme();
                consume(OF, "expected 'of' in for-of loop");
                Ast.Expr iterable = parseExpression();
                consume(RPAREN, "expected ')' after for-of header");
                return new Ast.ForOfStmt(kind, name, iterable, asyncIteration, parseLoopBody());
            }

            if (asyncIteration) {
                throw error(peek(), "'for await' requires iterator syntax: for await (x of asyncIterable)");
            }
            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
            else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            Ast.Expr initializer = parseExpression();
            Ast.BindingStmt init = new Ast.BindingStmt(kind, type, name, initializer);
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseLoopBody());
        }

        if (looksLikeForOfDestructurePattern()) {
            List<Ast.DestructureBinding> bindings = parseForSequenceBindings(Ast.BindingKind.VAL);
            consume(OF, "expected 'of' after for-of destructure pattern");
            Ast.Expr iterable = parseExpression();
            consume(RPAREN, "expected ')' after for-of header");
            return new Ast.ForOfDestructureStmt(bindings, iterable, asyncIteration, parseLoopBody());
        }

        if (check(IDENT) && checkNext(OF)) {
            String name = advance().lexeme();
            consume(OF, "expected 'of' in for-of loop");
            Ast.Expr iterable = parseExpression();
            consume(RPAREN, "expected ')' after for-of header");
            return new Ast.ForOfStmt(Ast.BindingKind.VAL, name, iterable, asyncIteration, parseLoopBody());
        }

        if (asyncIteration) {
            throw error(peek(), "'for await' requires iterator syntax: for await (x of asyncIterable)");
        }

        if (looksLikeTypedForInitializer()) {
            Ast.TypeRef type = parseTypeRef();
            String name = consume(IDENT, "expected typed loop binding name").lexeme();
            consume(EQUAL, "typed for initializer requires '='");
            Ast.BindingStmt init = new Ast.BindingStmt(
                    Ast.BindingKind.LET,
                    type,
                    name,
                    parseExpression());
            consume(SEMICOLON, "expected ';' after for initializer");
            Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
            consume(SEMICOLON, "expected ';' after for condition");
            Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
            consume(RPAREN, "expected ')' after for header");
            return new Ast.ForStmt(init, condition, update, parseLoopBody());
        }

        Ast.Stmt initializer = null;
        if (!check(SEMICOLON)) initializer = new Ast.ExprStmt(parseExpression());
        consume(SEMICOLON, "expected ';' after for initializer");
        Ast.Expr condition = check(SEMICOLON) ? null : parseExpression();
        consume(SEMICOLON, "expected ';' after for condition");
        Ast.Expr update = check(RPAREN) ? null : parseForUpdate();
        consume(RPAREN, "expected ')' after for header");
        return new Ast.ForStmt(initializer, condition, update, parseLoopBody());
    }

    private boolean looksLikeUnparenthesizedForOf() {
        int mark = current;
        try {
            if (isBindingKind(peek().type())) parseBindingKind();
            if (looksLikeForOfDestructurePattern()) return true;
            return check(IDENT) && checkNext(OF);
        } finally {
            current = mark;
        }
    }

    private boolean looksLikeForOfDestructurePattern() {
        if (!check(LBRACKET)) return false;
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LBRACKET) depth++;
            else if (type == RBRACKET) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).type() == OF;
                }
            }
        }
        return false;
    }

    private Ast.Stmt parseUnparenthesizedForInitializer() {
        if (check(SEMICOLON)) return null;

        if (isBindingKind(peek().type())) {
            Ast.BindingKind kind = parseBindingKind();
            Ast.TypeRef type = null;
            String name;
            if (check(IDENT) && checkNext(EQUAL)) {
                name = advance().lexeme();
            } else {
                type = parseTypeRef();
                name = consume(IDENT, "expected loop initializer binding name").lexeme();
            }
            consume(EQUAL, "for initializer binding requires '='");
            return new Ast.BindingStmt(kind, type, name, parseExpression());
        }

        if (looksLikeTypedForInitializer()) {
            Ast.TypeRef type = parseTypeRef();
            String name = consume(IDENT, "expected typed loop binding name").lexeme();
            consume(EQUAL, "typed for initializer requires '='");
            return new Ast.BindingStmt(Ast.BindingKind.LET, type, name, parseExpression());
        }

        return new Ast.ExprStmt(parseExpression());
    }

    private boolean looksLikeTypedForInitializer() {
        int mark = current;
        try {
            parseTypeRef();
            return check(IDENT) && checkNext(EQUAL);
        } catch (IllegalArgumentException ignored) {
            return false;
        } finally {
            current = mark;
        }
    }

    private Ast.Expr parseForUpdate() {
        int mark = current;
        if (check(IDENT)) {
            String name = advance().lexeme();
            if (matchAdjacentPair(PLUS)) {
                Ast.NameExpr target = new Ast.NameExpr(name);
                return new Ast.AssignExpr(
                        target,
                        new Ast.BinaryExpr("+", new Ast.NameExpr(name), new Ast.LiteralExpr(1L)));
            }
            if (matchAdjacentPair(MINUS)) {
                Ast.NameExpr target = new Ast.NameExpr(name);
                return new Ast.AssignExpr(
                        target,
                        new Ast.BinaryExpr("-", new Ast.NameExpr(name), new Ast.LiteralExpr(1L)));
            }
            current = mark;
        }
        return parseExpression();
    }

    private List<Ast.DestructureBinding> parseForSequenceBindings(Ast.BindingKind inheritedKind) {
        consume(LBRACKET, "expected '[' to start for-of destructure pattern");
        if (check(RBRACKET)) throw error(peek(), "for-of destructure pattern cannot be empty");

        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        Ast.BindingKind currentKind = inheritedKind == null ? Ast.BindingKind.VAL : inheritedKind;
        do {
            if (isBindingKind(peek().type())) currentKind = parseBindingKind();
            if (isDiscardToken(peek())) {
                advance();
                bindings.add(Ast.DestructureBinding.discard());
            } else {
                String name = consume(IDENT, "expected binding name in for-of destructure pattern").lexeme();
                bindings.add(new Ast.DestructureBinding(currentKind, name));
            }
        } while (match(COMMA));

        consume(RBRACKET, "expected ']' after for-of destructure pattern");
        return List.copyOf(bindings);
    }

    private List<Ast.Stmt> parseLoopBody() {
        if (check(LBRACE)) return parseBlock();
        if (!match(DO)) {
            throw error(peek(), "loop body must use '{ ... }' or 'do ... done'");
        }

        List<Ast.Stmt> body = new ArrayList<>();
        while (!check(EOF) && !isBareDoneDelimiter()) {
            body.add(parseStatement());
        }
        consume(DONE, "expected 'done' to close loop body");
        return body;
    }

    private boolean isBareDoneDelimiter() {
        return check(DONE) && !reservedCallableNameFollowedByInvocation(current);
    }

    private Ast.BindingStmt parseBindingStatement() {
        Ast.BindingKind kind = parseBindingKind();
        Ast.TypeRef type = null;
        String name;
        if (check(IDENT) && checkNext(EQUAL)) name = advance().lexeme();
        else {
            type = parseTypeRef();
            name = consume(IDENT, "expected binding name").lexeme();
        }
        consume(EQUAL, "binding requires initializer");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("binding should end with ';'");
        return new Ast.BindingStmt(kind, type, name, initializer);
    }

    private Ast.DestructureStmt parseDestructure(Ast.DestructureKind kind, Ast.BindingKind inheritedKind) {
        Token.Type close;
        if (kind == Ast.DestructureKind.SEQUENCE) {
            consume(LBRACKET, "expected '['");
            close = RBRACKET;
        } else {
            consume(LBRACE, "expected '{'");
            close = RBRACE;
        }

        if (check(close)) throw error(peek(), "destructure pattern cannot be empty");

        List<Ast.DestructureBinding> bindings = new ArrayList<>();
        Ast.BindingKind currentKind = inheritedKind;
        do {
            if (isBindingKind(peek().type())) currentKind = parseBindingKind();

            if (isDiscardToken(peek())) {
                if (kind == Ast.DestructureKind.OBJECT) {
                    throw error(peek(), "bare '_' discard is only valid in sequence destructuring");
                }
                advance();
                bindings.add(Ast.DestructureBinding.discard());
                continue;
            }

            if (currentKind == null) {
                throw error(peek(), "destructure binding kind must be declared before the first binding");
            }
            String name = consume(IDENT, "expected binding name in destructure").lexeme();
            bindings.add(new Ast.DestructureBinding(currentKind, name));
        } while (match(COMMA));

        consume(close, kind == Ast.DestructureKind.SEQUENCE ? "expected ']'" : "expected '}'");
        consume(EQUAL, "expected '=' after destructure pattern");
        Ast.Expr initializer = parseExpression();
        consumeStatementTerminator("destructure should end with ';'");
        return new Ast.DestructureStmt(kind, bindings, initializer);
    }

    private boolean looksLikeDestructure() {
        if (current + 1 >= tokens.size()) return false;
        Token first = tokens.get(current + 1);
        return isBindingKind(first.type()) || isDiscardToken(first);
    }

    private boolean looksLikePrefixedDestructure() {
        if (current + 2 >= tokens.size()) return false;
        Token.Type open = tokens.get(current + 1).type();
        Token.Type close;
        if (open == LBRACKET) close = RBRACKET;
        else if (open == LBRACE) close = RBRACE;
        else return false;

        int depth = 0;
        for (int i = current + 1; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == open) depth++;
            else if (type == close) {
                depth--;
                if (depth == 0) {
                    return i + 1 < tokens.size() && tokens.get(i + 1).type() == EQUAL;
                }
            }
        }
        return false;
    }

    private boolean isDiscardToken(Token token) {
        return token.type() == IDENT && token.lexeme().equals("_");
    }

    private Ast.IfStmt parseIf() {
        List<Ast.IfBranch> branches = new ArrayList<>();
        Ast.Expr condition = parseCondition();

        // Brace form still obeys the universal Oreslang invariant: every if closes with fi.
        // Braces delimit branch bodies; they never replace the structural terminator.
        if (check(LBRACE)) {
            branches.add(new Ast.IfBranch(condition, parseBlock()));
            while (match(ELSEIF)) {
                condition = parseCondition();
                branches.add(new Ast.IfBranch(condition, parseBlock()));
            }

            List<Ast.Stmt> elseBody = List.of();
            if (match(ELSE)) elseBody = parseBlock();
            consume(FI, "expected 'fi' to close if");
            return new Ast.IfStmt(branches, elseBody);
        }

        // Keyword-delimited form: if condition then ... elseif condition then ... else ... fi.
        // 'do' remains accepted as a compatibility spelling for existing source.
        match(SEMICOLON);
        if (!match(THEN, DO)) throw error(peek(), "expected 'then' after if condition");
        List<Ast.Stmt> body = parseUntil(ELSEIF, ELSE, FI);
        branches.add(new Ast.IfBranch(condition, body));

        while (match(ELSEIF)) {
            condition = parseCondition();
            match(SEMICOLON);
            if (!match(THEN, DO)) throw error(peek(), "expected 'then' after elseif condition");
            body = parseUntil(ELSEIF, ELSE, FI);
            branches.add(new Ast.IfBranch(condition, body));
        }

        List<Ast.Stmt> elseBody = List.of();
        if (match(ELSE)) elseBody = parseUntil(FI);
        consume(FI, "expected 'fi' to close if");
        return new Ast.IfStmt(branches, elseBody);
    }

    private Ast.Expr parseCondition() {
        Ast.Expr expression = normalizeLegacyConditionPipe(parseLogicalOr());
        // Legacy condition-only comma means logical AND. Prefer && in new code.
        while (match(COMMA)) {
            expression = new Ast.BinaryExpr(
                    "&&",
                    expression,
                    normalizeLegacyConditionPipe(parseLogicalOr()));
        }
        return expression;
    }

    private Ast.Expr normalizeLegacyConditionPipe(Ast.Expr expr) {
        if (expr instanceof Ast.BinaryExpr binary) {
            String operator = binary.operator().equals("|") ? "||" : binary.operator();
            return new Ast.BinaryExpr(
                    operator,
                    normalizeLegacyConditionPipe(binary.left()),
                    normalizeLegacyConditionPipe(binary.right()));
        }
        return expr;
    }

    private Ast.MatchStmt parseMatch() {
        boolean ordered = check(IDENT) && peek().lexeme().equals("first");
        if (ordered) advance();

        boolean previousSuppression = suppressRefinementOperators;
        suppressRefinementOperators = true;
        Ast.Expr subject;
        try {
            subject = parseExpression();
        } finally {
            suppressRefinementOperators = previousSuppression;
        }
        match(SEMICOLON);

        List<Ast.MatchArm> arms = new ArrayList<>();
        while (!check(END) && !check(EOF)) {
            Ast.Pattern pattern = match(ELSE) ? new Ast.WildcardPattern() : parsePattern();
            Ast.Expr guard = match(WHEN) ? parseExpression() : null;
            if (check(FAT_ARROW)) {
                throw error(peek(), "match implementations use the slim arrow '->'; '=>' is reserved for type definitions");
            }
            consume(ARROW, "match arms use the slim arrow '->'");
            List<Ast.Stmt> body = parseBlock();
            arms.add(new Ast.MatchArm(pattern, guard, body));
        }
        consume(END, "expected 'end' to close match");
        if (arms.isEmpty()) throw error(previous(), "match requires at least one arm");
        return new Ast.MatchStmt(subject, ordered, arms);
    }

    private Ast.Pattern parsePattern() {
        if (match(IS)) {
            Ast.TypeRef target = parseTypeRef();
            String binding = check(IDENT) && !peek().lexeme().equals("_") ? advance().lexeme() : null;
            return new Ast.TypePattern(target, binding);
        }
        if (match(INT)) return new Ast.LiteralPattern(Long.parseLong(previous().lexeme().replace("_", "")));
        if (match(FLOAT)) return new Ast.LiteralPattern(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(STRING)) return new Ast.LiteralPattern(previous().lexeme());
        if (match(TRUE)) return new Ast.LiteralPattern(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralPattern(Boolean.FALSE);
        if (check(IDENT) && peek().lexeme().equals("_")) {
            advance();
            return new Ast.WildcardPattern();
        }
        if (check(IDENT)) {
            String name = advance().lexeme();
            if (match(LPAREN)) {
                List<Ast.Pattern> args = new ArrayList<>();
                if (!check(RPAREN)) {
                    do args.add(parsePattern()); while (match(COMMA));
                }
                consume(RPAREN, "expected ')' after constructor pattern");
                return new Ast.ConstructorPattern(name, args);
            }
            // Upper-case bare names are zero-arity constructors; lower-case names bind.
            if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                return new Ast.ConstructorPattern(name, List.of());
            }
            return new Ast.BindingPattern(name);
        }
        throw error(peek(), "expected match pattern");
    }

    private Ast.SwitchStmt parseSwitch() {
        Ast.Expr subject = parseExpression();
        match(SEMICOLON);
        List<Ast.SwitchCase> cases = new ArrayList<>();
        List<Ast.Stmt> defaultBody = List.of();
        boolean sawDefault = false;

        while (!check(END) && !check(EOF)) {
            if (match(CASE)) {
                if (sawDefault) throw error(previous(), "switch case cannot appear after default");
                List<Ast.Expr> constants = new ArrayList<>();
                do constants.add(parseExpression()); while (match(COMMA));
                if (check(FAT_ARROW)) {
                    throw error(peek(), "switch implementations use the slim arrow '->'; '=>' is reserved for type definitions");
                }
                consume(ARROW, "switch cases use the slim arrow '->'");
                cases.add(new Ast.SwitchCase(constants, parseBlock()));
                continue;
            }
            if (match(DEFAULT)) {
                if (sawDefault) throw error(previous(), "switch can contain only one default arm");
                sawDefault = true;
                if (check(FAT_ARROW)) {
                    throw error(peek(), "switch implementations use the slim arrow '->'; '=>' is reserved for type definitions");
                }
                consume(ARROW, "switch default uses the slim arrow '->'");
                defaultBody = parseBlock();
                continue;
            }
            throw error(peek(), "expected 'case', 'default', or 'end' in switch");
        }

        consume(END, "expected 'end' to close switch");
        return new Ast.SwitchStmt(subject, cases, defaultBody);
    }

    private Ast.TryStmt parseTry() {
        List<Ast.Stmt> body = parseBlock();
        consume(CATCH, "expected catch after try block");
        consume(LPAREN, "expected '(' after catch");
        String error = consume(IDENT, "expected catch binding").lexeme();
        consume(RPAREN, "expected ')' after catch binding");
        List<Ast.Stmt> catchBody = parseBlock();
        List<Ast.Stmt> finallyBody = match(FINALLY) ? parseBlock() : List.of();
        return new Ast.TryStmt(body, error, catchBody, finallyBody);
    }

    private List<Ast.Stmt> parseUntil(Token.Type... terminators) {
        List<Ast.Stmt> body = new ArrayList<>();
        outer: while (!check(EOF)) {
            for (Token.Type terminator : terminators) if (check(terminator)) break outer;
            body.add(parseStatement());
        }
        return body;
    }

    public Ast.Expr parseExpression() { return parseAssignment(); }

    private Ast.Expr parseAssignment() {
        Ast.Expr expr = parseConditional();
        if (match(EQUAL)) {
            if (!(expr instanceof Ast.NameExpr) && !(expr instanceof Ast.MemberExpr) && !(expr instanceof Ast.IndexExpr)) {
                throw error(previous(), "assignment target must be a local, field, or index");
            }
            return new Ast.AssignExpr(expr, parseAssignment());
        }
        return expr;
    }

    private Ast.Expr parseConditional() {
        Ast.Expr condition = parseLogicalOr();
        if (!match(QUESTION)) return condition;
        Ast.Expr whenTrue = parseAssignment();
        consume(COLON, "expected ':' in ternary expression");
        Ast.Expr whenFalse = parseConditional();
        return new Ast.ConditionalExpr(condition, whenTrue, whenFalse);
    }

    private Ast.Expr parseLogicalOr() {
        Ast.Expr expr = parseLogicalXor();
        while (matchAdjacentPair(PIPE)) expr = new Ast.BinaryExpr("||", expr, parseLogicalXor());
        return expr;
    }

    private Ast.Expr parseLogicalXor() {
        Ast.Expr expr = parseLogicalAnd();
        while (matchAdjacentPair(CARET)) expr = new Ast.BinaryExpr("^^", expr, parseLogicalAnd());
        return expr;
    }

    private Ast.Expr parseLogicalAnd() {
        Ast.Expr expr = parseBitwiseOr();
        while (matchAdjacentPair(AMP)) expr = new Ast.BinaryExpr("&&", expr, parseBitwiseOr());
        return expr;
    }

    private Ast.Expr parseBitwiseOr() {
        Ast.Expr expr = parseBitwiseXor();
        while (matchSingleOperator(PIPE)) expr = new Ast.BinaryExpr("|", expr, parseBitwiseXor());
        return expr;
    }

    private Ast.Expr parseBitwiseXor() {
        Ast.Expr expr = parseBitwiseAnd();
        while (matchSingleOperator(CARET)) expr = new Ast.BinaryExpr("^", expr, parseBitwiseAnd());
        return expr;
    }

    private Ast.Expr parseBitwiseAnd() {
        Ast.Expr expr = parseEquality();
        while (matchSingleOperator(AMP)) expr = new Ast.BinaryExpr("&", expr, parseEquality());
        return expr;
    }

    private Ast.Expr parseEquality() {
        Ast.Expr expr = parseComparison();
        while (true) {
            String op;
            if (match(EQ, EQUAL_EQUAL)) op = "eq";
            else if (match(NEQ, BANG_EQUAL)) op = "neq";
            else if (matchAdjacentBangEqKeyword()) op = "neq";
            else if (!suppressRefinementOperators && match(IS)) op = "is";
            else break;
            expr = new Ast.BinaryExpr(op, expr, parseComparison());
        }
        return expr;
    }

    private Ast.Expr parseComparison() {
        Ast.Expr expr = parseShift();
        while (true) {
            if (match(LT, LTE, GT, GTE)) {
                String op = previous().lexeme();
                expr = new Ast.BinaryExpr(op, expr, parseShift());
                continue;
            }
            if (!suppressRefinementOperators && check(IS) && checkNext(TYPE)) {
                advance();
                consume(TYPE, "expected 'type' after 'is' in a nominal type test");
                Ast.TypeRef target = parseTypeRef();
                String binding = check(IDENT) && !peek().lexeme().equals("_") ? advance().lexeme() : null;
                expr = new Ast.TypeTestExpr(expr, target, binding);
                continue;
            }
            if (!suppressRefinementOperators && match(MATCHES)) {
                expr = new Ast.PatternTestExpr(expr, parsePattern());
                continue;
            }
            if (!suppressRefinementOperators && match(AS)) {
                boolean optional = match(QUESTION);
                expr = new Ast.CastExpr(expr, parseTypeRef(),
                        optional ? Ast.CastMode.OPTIONAL : Ast.CastMode.CHECKED);
                continue;
            }
            break;
        }
        return expr;
    }

    private Ast.Expr parseShift() {
        Ast.Expr expr = parseAdditive();
        while (true) {
            String op;
            if (matchAdjacentTriple(GT)) op = ">>>";
            else if (matchAdjacentPair(LT)) op = "<<";
            else if (matchAdjacentPair(GT)) op = ">>";
            else break;
            expr = new Ast.BinaryExpr(op, expr, parseAdditive());
        }
        return expr;
    }

    private Ast.Expr parseAdditive() {
        Ast.Expr expr = parseMultiplicative();
        while (match(PLUS, MINUS)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseMultiplicative());
        }
        return expr;
    }

    private Ast.Expr parseMultiplicative() {
        Ast.Expr expr = parseUnary();
        while (match(STAR, SLASH, PERCENT)) {
            String op = previous().lexeme();
            expr = new Ast.BinaryExpr(op, expr, parseUnary());
        }
        return expr;
    }

    private Ast.Expr parseUnary() {
        if (match(BANG, TILDE, MINUS, PLUS)) return new Ast.UnaryExpr(previous().lexeme(), parseUnary());
        if (match(AMP)) {
            boolean mutable = match(MUT);
            return new Ast.UnaryExpr(mutable ? "&mut" : "&", parseUnary());
        }
        if (match(AWAIT)) return new Ast.AwaitExpr(parseUnary());
        return parsePostfix();
    }

    private Ast.Expr parsePostfix() {
        Ast.Expr expr = parsePrimary();
        while (true) {
            if (check(LT) && adjacent(previous(), peek()) && looksLikeTypeArgumentCall()) {
                List<Ast.TypeRef> typeArguments = parseCallTypeArguments();
                consume(LPAREN, "expected '(' after call type arguments");
                List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, typeArguments, args);
            } else if (match(LPAREN)) {
                List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
                consume(RPAREN, "expected ')' after arguments");
                expr = new Ast.CallExpr(expr, args);
            } else if (match(DOT)) {
                String member = consumeMemberName();
                expr = new Ast.MemberExpr(expr, member);
            } else if (match(LBRACKET)) {
                Ast.Expr index = parseExpression();
                consume(RBRACKET, "expected ']' after index");
                expr = new Ast.IndexExpr(expr, index);
            } else break;
        }
        return expr;
    }

    private List<Ast.TypeRef> parseCallTypeArguments() {
        consume(LT, "expected '<' before call type arguments");
        List<Ast.TypeRef> arguments = new ArrayList<>();
        if (!check(GT)) {
            do arguments.add(parseTypeRef()); while (match(COMMA));
        }
        consume(GT, "expected '>' after call type arguments");
        return List.copyOf(arguments);
    }

    private boolean looksLikeTypeArgumentCall() {
        return looksLikeTypeArgumentCallAt(current);
    }

    private boolean looksLikeTypeArgumentCallAt(int startIndex) {
        int depth = 0;
        for (int i = startIndex; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LT) depth++;
            else if (type == GT) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == LPAREN;
                if (depth < 0) return false;
            } else if (type == SEMICOLON || type == EQUAL || type == QUESTION || type == COLON || type == EOF) {
                return false;
            }
        }
        return false;
    }

    private String consumeCallableName(String message) {
        Token token = peek();
        if (token.type() == IDENT
                || isReservedCallableName(token.type())
                || isContextualStatementCallableName(token.type())) {
            advance();
            return token.lexeme();
        }
        throw error(token, message);
    }

    private boolean reservedCallableNameFollowedByInvocation(int nameIndex) {
        int nextIndex = nameIndex + 1;
        if (nextIndex >= tokens.size()) return false;
        Token next = tokens.get(nextIndex);
        if (next.type() == LPAREN) return true;
        return next.type() == LT
                && adjacent(tokens.get(nameIndex), next)
                && looksLikeTypeArgumentCallAt(nextIndex);
    }

    private static boolean isReservedCallableName(Token.Type type) {
        return type == STOP || type == DO || type == DONE;
    }

    private static boolean isContextualStatementCallableName(Token.Type type) {
        return type == LOOP || type == BLOCK;
    }

    private String consumeStaticObjectKeyName(String message) {
        if (match(STRING)) return previous().lexeme();
        Token token = peek();
        if (isMemberNameToken(token.type())) {
            advance();
            return token.lexeme();
        }
        throw error(token, message);
    }

    private String consumeMemberName() {
        Token token = peek();
        if (isMemberNameToken(token.type())) {
            if (isReservedCallableName(token.type())
                    && token.type() != DONE
                    && !reservedCallableNameFollowedByInvocation(current)) {
                throw error(token, "'" + token.lexeme()
                        + "' is reserved and may only be used as a function name in a call or as an object/map key");
            }
            advance();
            return token.lexeme();
        }
        throw error(token, "expected member name after '.'");
    }

    /**
     * Reserved words remain illegal lexical identifiers, but member names live
     * in a separate namespace. This permits APIs such as SharedMutex.new(...)
     * without allowing declarations such as `val new = ...`.
     */
    private static boolean isMemberNameToken(Token.Type type) {
        return switch (type) {
            case IDENT,
                    DEFINE, CLASS, MODULE, NAMESPACE, IMPORT, FROM, AS, EXTENDS, IMPLEMENTS,
                    TRY, CATCH, FINALLY, END, FI, IF, DO, ELSE, THEN,
                    NEW, STOP, DONE, AWAIT, ASYNC, NLEX, ACTOR, ISOACTOR, SHARED, DEF, FNC, ROUTINE, FOR, OF, LOOP, BLOCK, BREAK, CONTINUE, YIELD, SUPER, ELSEIF, SWITCH, MATCH, MATCHES, EQ, NEQ, IS, WHEN, CASE, DEFAULT, FIRST, TYPE, TYPES, TYPEOF,
                    INTERFACE, TRAIT, STRUCT, IMPL, ABSTRACT, VOID, STATIC, PUB, PRIVATE, STRUCTURAL, RETURN, DEFER,
                    VAL, CONST, LET, MUT, SELF, TRUE, FALSE, NULL, OBJ, ARR -> true;
            default -> false;
        };
    }

    private Ast.Expr parsePrimary() {
        if (match(INT)) return new Ast.LiteralExpr(Long.parseLong(previous().lexeme().replace("_", "")));
        if (match(FLOAT)) return new Ast.LiteralExpr(Double.parseDouble(previous().lexeme().replace("_", "")));
        if (match(IMAG)) {
            String raw = previous().lexeme().substring(0, previous().lexeme().length() - 1).replace("_", "");
            return new Ast.LiteralExpr(new Ast.Imaginary(Double.parseDouble(raw)));
        }
        if (match(STRING)) return new Ast.LiteralExpr(previous().lexeme());
        if (match(TRUE)) return new Ast.LiteralExpr(Boolean.TRUE);
        if (match(FALSE)) return new Ast.LiteralExpr(Boolean.FALSE);
        if (match(NULL)) throw error(previous(), "standalone null values are forbidden; use Option<T>");
        if (match(SELF)) return new Ast.NameExpr("self");
        // 'actor' remains reserved, but in expression position it names the
        // actor-local runtime namespace (actor.gc and future local primitives).
        if (match(ACTOR)) return new Ast.NameExpr("actor");
        // loop/block are contextual statement keywords: parseStatement only
        // consumes them when followed by '{'. In every expression position they
        // remain valid references to same-named callables, including first-class
        // function values (not only direct calls).
        if (isContextualStatementCallableName(peek().type())) {
            return new Ast.NameExpr(advance().lexeme());
        }
        if (isReservedCallableName(peek().type())
                && reservedCallableNameFollowedByInvocation(current)) {
            return new Ast.NameExpr(advance().lexeme());
        }
        if (match(IDENT)) return new Ast.NameExpr(previous().lexeme());
        if (match(NEW)) {
            Ast.TypeRef type = parseTypeRef();
            consume(LPAREN, "expected '(' after new type");
            List<Ast.Expr> args = parseArgumentsUntil(RPAREN);
            consume(RPAREN, "expected ')' after constructor arguments");
            return new Ast.NewExpr(type, args);
        }
        if (match(OBJ)) return parseObjectLiteral();
        if (match(ARR)) {
            consume(LBRACKET, "expected '[' after arr");
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']' after arr literal");
            return new Ast.ListExpr(items);
        }
        if (match(NLEX)) {
            if (check(PIPE)) return parsePipeLambda(true);
            if (check(LPAREN) && looksLikeLambda()) return parseLambda(true);
            throw error(previous(), "'nlex' in expression position must prefix a lambda");
        }
        if (check(PIPE)) return parsePipeLambda(false);
        if (check(LPAREN) && looksLikeLambda()) return parseLambda(false);
        if (match(LPAREN)) {
            Ast.Expr first = parseExpression();
            if (match(COMMA)) {
                List<Ast.Expr> items = new ArrayList<>();
                items.add(first);
                do items.add(parseExpression()); while (match(COMMA));
                consume(RPAREN, "expected ')' after tuple");
                return new Ast.TupleExpr(items);
            }
            consume(RPAREN, "expected ')' after expression");
            return first;
        }
        if (match(LBRACKET)) {
            List<Ast.Expr> items = parseArgumentsUntil(RBRACKET);
            consume(RBRACKET, "expected ']'");
            return new Ast.ListExpr(items);
        }
        throw error(peek(), "expected expression");
    }

    private Ast.ObjectExpr parseObjectLiteral() {
        consume(LBRACE, "expected '{' after obj");
        List<Ast.ObjectField> fields = new ArrayList<>();
        if (!check(RBRACE)) {
            do {
                Ast.ObjectField field;
                if (match(BACKTICK)) {
                    Ast.Expr key = parseExpression();
                    consume(BACKTICK, "expected closing backtick after dynamic object key");
                    consume(COLON, "expected ':' after dynamic object key");
                    field = Ast.ObjectField.dynamic(key, parseExpression());
                } else {
                    String name = consumeStaticObjectKeyName("expected object field name");
                    consume(COLON, "expected ':' after object field name");
                    field = Ast.ObjectField.named(name, parseExpression());
                }
                fields.add(field);
            } while (match(COMMA));
        }
        consume(RBRACE, "expected '}' after obj literal");
        return new Ast.ObjectExpr(fields);
    }

    private Ast.LambdaExpr parseLambda(boolean nonLexical) {
        consume(LPAREN, "expected '('");
        List<Ast.Param> params = parseParametersUntil(RPAREN);
        consume(RPAREN, "expected ')' after lambda parameters");
        consume(ARROW, "expected '->' after lambda parameters");
        if (!check(LBRACE)) throw error(peek(), "lambdas always require a block body; use '-> { ... }'");
        return new Ast.LambdaExpr(params, null, parseBlock(), nonLexical);
    }

    private Ast.LambdaExpr parsePipeLambda(boolean nonLexical) {
        consume(PIPE, "expected '|'");
        List<Ast.Param> params = new ArrayList<>();
        if (!check(PIPE)) {
            do {
                if (check(IDENT) && (checkNext(COMMA) || checkNext(PIPE))) {
                    String name = advance().lexeme();
                    params.add(new Ast.Param(Ast.TypeRef.inferred(), name, false, false));
                } else {
                    Ast.TypeRef type = parseTypeRef();
                    boolean mutable = match(MUT);
                    String name = consume(IDENT, "expected lambda parameter name").lexeme();
                    params.add(new Ast.Param(type, name, false, mutable));
                }
            } while (match(COMMA));
        }
        consume(PIPE, "expected closing '|' after lambda parameters");
        consume(ARROW, "lambdas use the slim arrow '->'");
        if (!check(LBRACE)) throw error(peek(), "lambdas always require a block body; use '|args| -> { ... }'");
        return new Ast.LambdaExpr(params, null, parseBlock(), nonLexical);
    }

    private boolean looksLikeLambda() {
        int depth = 0;
        for (int i = current; i < tokens.size(); i++) {
            Token.Type type = tokens.get(i).type();
            if (type == LPAREN) depth++;
            else if (type == RPAREN) {
                depth--;
                if (depth == 0) return i + 1 < tokens.size() && tokens.get(i + 1).type() == ARROW;
            }
        }
        return false;
    }

    private List<Ast.Expr> parseArgumentsUntil(Token.Type terminator) {
        if (check(terminator)) return List.of();
        List<Ast.Expr> args = new ArrayList<>();
        do args.add(parseExpression()); while (match(COMMA));
        return args;
    }

    private void consumeMemberTerminator(Token.Type structuralTerminator, String message) {
        if (match(SEMICOLON) || check(structuralTerminator)) return;
        throw error(peek(), message);
    }

    private void consumeClassFieldTerminator(String message) {
        if (match(SEMICOLON) || check(AT) || check(END) || check(PUB) || check(PRIVATE)
                || check(STATIC) || check(ABSTRACT) || check(ASYNC) || check(LBRACKET)
                || isBindingKind(peek().type())
                || (check(IDENT) && (checkNext(COLON) || checkNext(LPAREN)))) return;
        throw error(peek(), message);
    }

    private boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        for (Ast.Annotation annotation : annotations) if (annotation.name().equals(name)) return true;
        return false;
    }

    private void consumeStatementTerminator(String message) {
        if (match(SEMICOLON) || isSafeStatementBoundary() || isImplicitNewlineTerminator()) return;
        throw error(peek(), message);
    }

    private boolean isImplicitNewlineTerminator() {
        if (current == 0 || check(EOF)) return false;
        return previous().line() < peek().line();
    }

    private boolean isSafeStatementBoundary() {
        return check(RBRACE) || check(FI) || check(END) || check(ELSE) || check(ELSEIF)
                || check(CATCH) || check(FINALLY) || isBareDoneDelimiter() || check(EOF);
    }

    private Ast.BindingKind parseBindingKind() {
        if (match(CONST)) return Ast.BindingKind.CONST;
        if (match(VAL)) return Ast.BindingKind.VAL;
        if (match(LET)) return Ast.BindingKind.LET;
        throw error(peek(), "expected const, val, or let");
    }

    private boolean isBindingKind(Token.Type type) { return type == CONST || type == VAL || type == LET; }

    private boolean matchContextualShared() {
        if (match(SHARED)) return true;
        if (check(IDENT) && peek().lexeme().equals("shared")) {
            advance();
            return true;
        }
        return false;
    }

    private boolean match(Token.Type... types) {
        for (Token.Type type : types) {
            if (check(type)) { advance(); return true; }
        }
        return false;
    }

    private boolean matchAdjacentBangEqKeyword() {
        if (current + 1 >= tokens.size()) return false;
        Token bang = tokens.get(current);
        Token eq = tokens.get(current + 1);
        if (bang.type() != BANG || eq.type() != EQ || !adjacent(bang, eq)) return false;
        advance();
        advance();
        return true;
    }

    private Token consume(Token.Type type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(Token.Type type) { return peek().type() == type; }
    private boolean checkNext(Token.Type type) { return current + 1 < tokens.size() && tokens.get(current + 1).type() == type; }

    private boolean adjacent(Token left, Token right) {
        return left.line() == right.line() && right.column() == left.column() + left.lexeme().length();
    }

    private boolean checkAdjacentPair(Token.Type type) {
        return current + 1 < tokens.size()
                && tokens.get(current).type() == type
                && tokens.get(current + 1).type() == type
                && adjacent(tokens.get(current), tokens.get(current + 1));
    }

    private boolean matchAdjacentPair(Token.Type type) {
        if (!checkAdjacentPair(type)) return false;
        advance();
        advance();
        return true;
    }

    private boolean matchAdjacentTriple(Token.Type type) {
        if (current + 2 >= tokens.size()) return false;
        Token first = tokens.get(current);
        Token second = tokens.get(current + 1);
        Token third = tokens.get(current + 2);
        if (first.type() != type || second.type() != type || third.type() != type
                || !adjacent(first, second) || !adjacent(second, third)) return false;
        advance();
        advance();
        advance();
        return true;
    }

    private boolean matchSingleOperator(Token.Type type) {
        if (!check(type) || checkAdjacentPair(type)) return false;
        advance();
        return true;
    }
    private boolean isLegacyFnSpelling() {
        return check(IDENT) && peek().lexeme().equals("fn");
    }

    private boolean checkNextLexeme(String lexeme) {
        return current + 1 < tokens.size() && tokens.get(current + 1).type() == IDENT
                && tokens.get(current + 1).lexeme().equals(lexeme);
    }
    private Token advance() { if (!check(EOF)) current++; return previous(); }
    private Token peek() { return tokens.get(current); }
    private Token previous() { return tokens.get(current - 1); }

    private IllegalArgumentException error(Token token, String message) {
        return new IllegalArgumentException("Oreslang parse error at " + token.line() + ":" + token.column() + ": " + message);
    }

    private record Modifiers(Ast.Visibility visibility, boolean async, boolean generator, boolean nonLexical, boolean isStatic, boolean isAbstract, boolean shared) { }
}
