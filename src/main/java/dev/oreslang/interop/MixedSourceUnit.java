package dev.oreslang.interop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an extended source file into its Oreslang and Java views without
 * inventing a source-level module/package identity for Oreslang. The canonical
 * filesystem path remains the code-unit identity.
 *
 * In .ores files the two Java forms have deliberately different semantics:
 *
 *   java { ... }     declaration island; compiled but never executed merely
 *                    because the declaration exists.
 *
 *   do java { ... }  executable island; lowered at that exact statement
 *                    position to a generated java.lang.Runnable whose run()
 *                    method contains the Java statements.
 *
 * Declaration islands are source-level declarations only. Executable islands
 * are statement-level only. This separation prevents an inert declaration from
 * accidentally becoming an import-time/init-time side effect.
 */
public final class MixedSourceUnit {
    public enum PrimaryLanguage { ORES, JAVA }
    public enum IslandKind { DECLARATION, EXECUTION, EMBEDDED_ORES }

    public record Island(
            String language,
            IslandKind kind,
            int keywordStart,
            int bodyStart,
            int bodyEnd,
            int blockEnd,
            int sourceBraceDepth,
            String body) { }

    public record JavaBinding(String simpleName, String binaryName) { }
    public record JavaSource(String className, String sourceText) { }

    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;");
    private static final Pattern PACKAGE_ANYWHERE = Pattern.compile("(?m)^\\s*package\\s+");
    private static final String GENERATED_DO_PREFIX = "__OresJavaDo";

    private final String unitId;
    private final PrimaryLanguage primaryLanguage;
    private final String rawSource;
    private final String oresSource;
    private final String javaSource;
    private final String javaPackage;
    private final List<Island> foreignIslands;
    private final List<JavaBinding> javaBindings;
    private final List<JavaSource> javaSources;

    private MixedSourceUnit(
            String unitId,
            PrimaryLanguage primaryLanguage,
            String rawSource,
            String oresSource,
            String javaSource,
            String javaPackage,
            List<Island> foreignIslands,
            List<JavaBinding> javaBindings,
            List<JavaSource> javaSources) {
        this.unitId = unitId;
        this.primaryLanguage = primaryLanguage;
        this.rawSource = rawSource;
        this.oresSource = oresSource;
        this.javaSource = javaSource;
        this.javaPackage = javaPackage;
        this.foreignIslands = List.copyOf(foreignIslands);
        this.javaBindings = List.copyOf(javaBindings);
        this.javaSources = List.copyOf(javaSources);
    }

    public static MixedSourceUnit parse(Path path, String source) {
        Path normalized = path.toAbsolutePath().normalize();
        return parse(normalized.toString().replace('\\', '/'), normalized.getFileName().toString(), source);
    }

    public static MixedSourceUnit parse(String unitId, String fileName, String source) {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("mixed source unit id cannot be blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("mixed source filename cannot be blank");
        }

        String raw = source == null ? "" : source;
        String lower = fileName.toLowerCase(Locale.ROOT);
        PrimaryLanguage primary;
        if (lower.endsWith(".ores")) primary = PrimaryLanguage.ORES;
        else if (lower.endsWith(".java")) primary = PrimaryLanguage.JAVA;
        else throw new IllegalArgumentException("mixed source must use .ores or .java: " + fileName);

        if (primary == PrimaryLanguage.ORES) {
            return parseOresPrimary(unitId, raw);
        }
        return parseJavaPrimary(unitId, fileName, raw);
    }

    private static MixedSourceUnit parseOresPrimary(String unitId, String raw) {
        String generatedPackage = generatedPackage(unitId);
        List<Island> islands = findOresJavaIslands(raw);
        List<JavaSource> javaSources = new ArrayList<>();
        List<JavaBinding> bindings = new ArrayList<>();
        Map<String, Boolean> names = new LinkedHashMap<>();
        Map<Island, String> replacements = new LinkedHashMap<>();

        int executableIndex = 0;
        for (Island island : islands) {
            if (island.kind() == IslandKind.DECLARATION) {
                if (island.sourceBraceDepth() != 0) {
                    throw new IllegalArgumentException(
                            "java { ... } is a declaration island and must appear at Oreslang source declaration scope; "
                                    + "use do java { ... } inside executable code");
                }
                JavaIslandType type = parseJavaDeclarationIsland(island.body(), generatedPackage);
                reserveJavaName(names, type.simpleName(), "Java declaration island");
                javaSources.add(new JavaSource(type.binaryName(), type.sourceText()));
                bindings.add(new JavaBinding(type.simpleName(), type.binaryName()));
                replacements.put(island, "");
                continue;
            }

            if (island.kind() == IslandKind.EXECUTION) {
                if (island.sourceBraceDepth() == 0) {
                    throw new IllegalArgumentException(
                            "do java { ... } is executable and must appear inside an Oreslang callable/method body");
                }
                String simpleName = GENERATED_DO_PREFIX + executableIndex++;
                reserveJavaName(names, simpleName, "generated do java runnable");
                String binaryName = generatedPackage + "." + simpleName;
                javaSources.add(new JavaSource(
                        binaryName,
                        runnableSource(generatedPackage, simpleName, island.body())));
                bindings.add(new JavaBinding(simpleName, binaryName));
                replacements.put(island, "new " + simpleName + "().run();");
                continue;
            }

            throw new IllegalStateException("unexpected island kind in .ores source: " + island.kind());
        }

        String ores = replaceOresJavaIslands(raw, islands, replacements);
        ores = injectJavaImports(ores, bindings, raw, !islands.isEmpty());
        return new MixedSourceUnit(
                unitId,
                PrimaryLanguage.ORES,
                raw,
                ores,
                "",
                generatedPackage,
                islands,
                bindings,
                javaSources);
    }

    private static MixedSourceUnit parseJavaPrimary(String unitId, String fileName, String raw) {
        List<Island> islands = findSimpleIslands(raw, "ores", IslandKind.EMBEDDED_ORES);
        String javaPrimary = maskBlocks(raw, islands);
        String pkg = detectJavaPackage(javaPrimary);
        List<TopLevelType> types = topLevelTypes(javaPrimary);
        List<JavaBinding> bindings = new ArrayList<>();
        for (TopLevelType type : types) {
            if (!type.isPublic()) continue;
            String binary = pkg.isBlank() ? type.name() : pkg + "." + type.name();
            bindings.add(new JavaBinding(type.name(), binary));
        }

        String ores = extractIslandBodies(raw, islands);
        ores = injectJavaImports(ores, bindings, raw, !islands.isEmpty());

        String primaryClass = Path.of(fileName).getFileName().toString();
        primaryClass = primaryClass.substring(0, primaryClass.length() - ".java".length());
        List<JavaSource> sources = List.of(new JavaSource(
                pkg.isBlank() ? primaryClass : pkg + "." + primaryClass,
                javaPrimary));

        return new MixedSourceUnit(
                unitId,
                PrimaryLanguage.JAVA,
                raw,
                ores,
                javaPrimary,
                pkg,
                islands,
                bindings,
                sources);
    }

    public String unitId() { return unitId; }
    public PrimaryLanguage primaryLanguage() { return primaryLanguage; }
    public String rawSource() { return rawSource; }
    public String oresSource() { return oresSource; }
    public String javaSource() { return javaSource; }
    public String javaPackage() { return javaPackage; }
    public List<Island> foreignIslands() { return foreignIslands; }
    public List<JavaBinding> javaBindings() { return javaBindings; }
    public List<JavaSource> javaSources() { return javaSources; }
    public boolean hasOresSource() { return primaryLanguage == PrimaryLanguage.ORES || !foreignIslands.isEmpty(); }
    public boolean hasJavaSource() { return primaryLanguage == PrimaryLanguage.JAVA || !foreignIslands.isEmpty(); }
    public boolean mixed() { return !foreignIslands.isEmpty(); }

    public String primaryJavaClassName() {
        if (primaryLanguage != PrimaryLanguage.JAVA) return null;
        return javaSources.getFirst().className();
    }

    private static void reserveJavaName(Map<String, Boolean> names, String simpleName, String owner) {
        if (simpleName.startsWith(GENERATED_DO_PREFIX) && !owner.startsWith("generated")) {
            throw new IllegalArgumentException(
                    "Java declaration type prefix '" + GENERATED_DO_PREFIX + "' is reserved for do java lowering");
        }
        if (names.putIfAbsent(simpleName, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("duplicate generated/mixed Java type '" + simpleName + "'");
        }
    }

    private static String injectJavaImports(
            String ores,
            List<JavaBinding> bindings,
            String raw,
            boolean mixed) {
        if (ores.isBlank() && bindings.isEmpty()) return ores;

        StringBuilder prefix = new StringBuilder();
        for (JavaBinding binding : bindings) {
            prefix.append("import class ").append(binding.simpleName())
                    .append(" from \"java:").append(binding.binaryName()).append("\";\n");
        }
        if (!prefix.isEmpty()) prefix.append('\n');

        String result = prefix + ores;
        if (mixed) result += "\n// __mixed_source_sha256=" + sha256(raw) + "\n";
        return result;
    }

    private static JavaIslandType parseJavaDeclarationIsland(String body, String generatedPackage) {
        if (PACKAGE_ANYWHERE.matcher(body).find()) {
            throw new IllegalArgumentException(
                    "java { ... } declaration islands in .ores files cannot declare a package; "
                            + "Oreslang source identity is filesystem-path based");
        }

        List<TopLevelType> types = topLevelTypes(body);
        if (types.size() != 1) {
            throw new IllegalArgumentException(
                    "each java { ... } declaration island in a .ores file must declare exactly one "
                            + "top-level Java class/interface/record/enum");
        }

        TopLevelType type = types.getFirst();
        if (type.name().equals("Ores")) {
            throw new IllegalArgumentException(
                    "Java declaration type name 'Ores' is reserved for the generated Oreslang bridge facade");
        }
        if (type.name().startsWith(GENERATED_DO_PREFIX)) {
            throw new IllegalArgumentException(
                    "Java declaration type prefix '" + GENERATED_DO_PREFIX + "' is reserved for do java lowering");
        }

        String promoted = body;
        if (!type.isPublic()) {
            promoted = body.substring(0, type.keywordStart())
                    + "public "
                    + body.substring(type.keywordStart());
        }

        String source = "package " + generatedPackage + ";\n" + promoted;
        return new JavaIslandType(type.name(), generatedPackage + "." + type.name(), source);
    }

    private static String runnableSource(String generatedPackage, String simpleName, String body) {
        if (PACKAGE_ANYWHERE.matcher(body).find()) {
            throw new IllegalArgumentException("do java { ... } contains Java statements, not a package declaration");
        }
        return "package " + generatedPackage + ";\n"
                + "public final class " + simpleName + " implements java.lang.Runnable {\n"
                + "  @Override public void run() {\n"
                + body
                + "\n  }\n"
                + "}\n";
    }

    private static String detectJavaPackage(String javaSource) {
        Matcher matcher = JAVA_PACKAGE.matcher(javaSource);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<TopLevelType> topLevelTypes(String source) {
        List<TopLevelType> result = new ArrayList<>();
        int depth = 0;
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i + 2);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i + 2);
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(source, i, c);
                continue;
            }
            if (c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth = Math.max(0, depth - 1);
                i++;
                continue;
            }
            if (depth == 0 && Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
                String word = source.substring(start, i);
                if (word.equals("class")
                        || word.equals("interface")
                        || word.equals("record")
                        || word.equals("enum")) {
                    int j = skipWhitespaceAndComments(source, i);
                    if (j < source.length() && Character.isJavaIdentifierStart(source.charAt(j))) {
                        int nameStart = j++;
                        while (j < source.length() && Character.isJavaIdentifierPart(source.charAt(j))) j++;
                        String name = source.substring(nameStart, j);
                        boolean isPublic = modifierRegionContainsPublic(source, start);
                        result.add(new TopLevelType(name, start, isPublic));
                    }
                }
                continue;
            }
            i++;
        }
        return result;
    }

    private static boolean modifierRegionContainsPublic(String source, int keywordStart) {
        int start = keywordStart - 1;
        while (start >= 0) {
            char c = source.charAt(start);
            if (c == ';' || c == '}' || c == '{') break;
            start--;
        }
        String prefix = source.substring(start + 1, keywordStart);
        return Pattern.compile("\\bpublic\\b").matcher(prefix).find();
    }

    /**
     * Ores-primary scanner. Tracks Ores brace depth so declaration islands and
     * execution islands cannot silently swap roles.
     */
    private static List<Island> findOresJavaIslands(String source) {
        List<Island> islands = new ArrayList<>();
        int braceDepth = 0;
        int i = 0;

        while (i < source.length()) {
            char c = source.charAt(i);

            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i + 2);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i + 2);
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(source, i, c);
                continue;
            }
            if (c == '{') {
                braceDepth++;
                i++;
                continue;
            }
            if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
                i++;
                continue;
            }

            if (!Character.isJavaIdentifierStart(c)) {
                i++;
                continue;
            }

            int wordStart = i++;
            while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            String word = source.substring(wordStart, i);

            if (word.equals("do")) {
                int javaStart = skipWhitespaceAndComments(source, i);
                int javaEnd = identifierEnd(source, javaStart);
                if (javaEnd > javaStart && source.substring(javaStart, javaEnd).equals("java")) {
                    int open = skipWhitespaceAndComments(source, javaEnd);
                    if (open < source.length() && source.charAt(open) == '{') {
                        int close = matchingBrace(source, open);
                        int blockEnd = consumeOptionalStatementSemicolon(source, close + 1);
                        islands.add(new Island(
                                "java",
                                IslandKind.EXECUTION,
                                wordStart,
                                open + 1,
                                close,
                                blockEnd,
                                braceDepth,
                                source.substring(open + 1, close)));
                        i = blockEnd;
                        continue;
                    }
                }
            }

            if (word.equals("java")) {
                int open = skipWhitespaceAndComments(source, i);
                if (open < source.length() && source.charAt(open) == '{') {
                    int close = matchingBrace(source, open);
                    islands.add(new Island(
                            "java",
                            IslandKind.DECLARATION,
                            wordStart,
                            open + 1,
                            close,
                            close + 1,
                            braceDepth,
                            source.substring(open + 1, close)));
                    i = close + 1;
                }
            }
        }

        return islands;
    }

    private static List<Island> findSimpleIslands(
            String source,
            String keyword,
            IslandKind kind) {
        List<Island> islands = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i + 2);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i + 2);
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(source, i, c);
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int start = i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
                if (!source.substring(start, i).equals(keyword)) continue;
                int open = skipWhitespaceAndComments(source, i);
                if (open >= source.length() || source.charAt(open) != '{') continue;
                int close = matchingBrace(source, open);
                islands.add(new Island(
                        keyword,
                        kind,
                        start,
                        open + 1,
                        close,
                        close + 1,
                        0,
                        source.substring(open + 1, close)));
                i = close + 1;
                continue;
            }
            i++;
        }
        return islands;
    }

    private static int identifierEnd(String source, int start) {
        if (start >= source.length() || !Character.isJavaIdentifierStart(source.charAt(start))) return start;
        int i = start + 1;
        while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
        return i;
    }

    private static int consumeOptionalStatementSemicolon(String source, int index) {
        int i = index;
        while (i < source.length() && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) i++;
        return i < source.length() && source.charAt(i) == ';' ? i + 1 : index;
    }

    private static int matchingBrace(String source, int open) {
        int depth = 1;
        int i = open + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i + 2);
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i + 2);
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(source, i, c);
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
            i++;
        }
        throw new IllegalArgumentException("unterminated mixed-language block");
    }

    private static int skipWhitespaceAndComments(String source, int index) {
        int i = index;
        while (i < source.length()) {
            if (Character.isWhitespace(source.charAt(i))) {
                i++;
                continue;
            }
            if (source.charAt(i) == '/'
                    && i + 1 < source.length()
                    && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i + 2);
                continue;
            }
            if (source.charAt(i) == '/'
                    && i + 1 < source.length()
                    && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i + 2);
                continue;
            }
            break;
        }
        return i;
    }

    private static int skipLineComment(String source, int i) {
        while (i < source.length() && source.charAt(i) != '\n') i++;
        return i;
    }

    private static int skipBlockComment(String source, int i) {
        int depth = 1;
        while (i < source.length()) {
            if (i + 1 < source.length()
                    && source.charAt(i) == '/'
                    && source.charAt(i + 1) == '*') {
                depth++;
                i += 2;
                continue;
            }
            if (i + 1 < source.length()
                    && source.charAt(i) == '*'
                    && source.charAt(i + 1) == '/') {
                depth--;
                i += 2;
                if (depth == 0) return i;
                continue;
            }
            i++;
        }
        throw new IllegalArgumentException("unterminated block comment in mixed source");
    }

    private static int skipQuoted(String source, int start, char quote) {
        if (quote == '"' && start + 2 < source.length() && source.startsWith("\"\"\"", start)) {
            int end = source.indexOf("\"\"\"", start + 3);
            if (end < 0) {
                throw new IllegalArgumentException("unterminated Java text block in mixed source");
            }
            return end + 3;
        }

        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i++);
            if (c == '\\' && i < source.length()) {
                i++;
                continue;
            }
            if (c == quote) return i;
        }
        throw new IllegalArgumentException("unterminated quoted literal in mixed source");
    }

    private static String maskBlocks(String source, List<Island> islands) {
        char[] chars = source.toCharArray();
        for (Island island : islands) {
            for (int i = island.keywordStart(); i < island.blockEnd(); i++) {
                if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
            }
        }
        return new String(chars);
    }

    private static String replaceOresJavaIslands(
            String source,
            List<Island> islands,
            Map<Island, String> replacements) {
        if (islands.isEmpty()) return source;

        StringBuilder out = new StringBuilder(source.length() + islands.size() * 32);
        int cursor = 0;
        for (Island island : islands) {
            out.append(source, cursor, island.keywordStart());
            String replacement = replacements.getOrDefault(island, "");
            out.append(replacement);

            // Preserve the original number of physical lines after the lowered
            // statement so diagnostics for later Ores source remain stable.
            for (int i = island.keywordStart(); i < island.blockEnd(); i++) {
                char c = source.charAt(i);
                if (c == '\n' || c == '\r') out.append(c);
            }
            cursor = island.blockEnd();
        }
        out.append(source, cursor, source.length());
        return out.toString();
    }

    private static String extractIslandBodies(String source, List<Island> islands) {
        char[] chars = source.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
        }
        for (Island island : islands) {
            source.getChars(island.bodyStart(), island.bodyEnd(), chars, island.bodyStart());
        }
        return new String(chars);
    }

    private static String generatedPackage(String unitId) {
        return "dev.oreslang.mixed.u" + sha256(unitId).substring(0, 16);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private record TopLevelType(String name, int keywordStart, boolean isPublic) { }
    private record JavaIslandType(String simpleName, String binaryName, String sourceText) { }
}
