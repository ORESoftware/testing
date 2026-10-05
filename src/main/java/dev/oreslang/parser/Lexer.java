package dev.oreslang.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.oreslang.parser.Token.Type.*;

public final class Lexer {
    private static final Map<String, Token.Type> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("define", DEFINE); KEYWORDS.put("class", CLASS); KEYWORDS.put("module", MODULE); KEYWORDS.put("namespace", NAMESPACE);
        KEYWORDS.put("import", IMPORT); KEYWORDS.put("from", FROM); KEYWORDS.put("as", AS); KEYWORDS.put("with", WITH);
        KEYWORDS.put("extends", EXTENDS); KEYWORDS.put("implements", IMPLEMENTS);
        KEYWORDS.put("try", TRY); KEYWORDS.put("catch", CATCH); KEYWORDS.put("finally", FINALLY);
        KEYWORDS.put("end", END); KEYWORDS.put("fi", FI); KEYWORDS.put("if", IF); KEYWORDS.put("do", DO);
        KEYWORDS.put("else", ELSE); KEYWORDS.put("then", THEN); KEYWORDS.put("new", NEW); KEYWORDS.put("stop", STOP); KEYWORDS.put("done", DONE);
        KEYWORDS.put("await", AWAIT); KEYWORDS.put("async", ASYNC); KEYWORDS.put("generator", GENERATOR); KEYWORDS.put("nlex", NLEX); KEYWORDS.put("actor", ACTOR); KEYWORDS.put("isoactor", ISOACTOR); KEYWORDS.put("def", DEF); KEYWORDS.put("fnc", FNC); KEYWORDS.put("routine", ROUTINE);
        KEYWORDS.put("for", FOR); KEYWORDS.put("of", OF); KEYWORDS.put("loop", LOOP); KEYWORDS.put("block", BLOCK); KEYWORDS.put("break", BREAK); KEYWORDS.put("continue", CONTINUE); KEYWORDS.put("yield", YIELD); KEYWORDS.put("super", SUPER); KEYWORDS.put("elseif", ELSEIF); KEYWORDS.put("switch", SWITCH); KEYWORDS.put("match", MATCH); KEYWORDS.put("matches", MATCHES); KEYWORDS.put("is", IS); KEYWORDS.put("when", WHEN); KEYWORDS.put("case", CASE); KEYWORDS.put("default", DEFAULT);
        KEYWORDS.put("type", TYPE); KEYWORDS.put("types", TYPES); KEYWORDS.put("typeof", TYPEOF); KEYWORDS.put("interface", INTERFACE); KEYWORDS.put("trait", TRAIT); KEYWORDS.put("struct", STRUCT); KEYWORDS.put("impl", IMPL); KEYWORDS.put("abstract", ABSTRACT);
        KEYWORDS.put("void", VOID); KEYWORDS.put("static", STATIC); KEYWORDS.put("pub", PUB); KEYWORDS.put("private", PRIVATE);
        KEYWORDS.put("return", RETURN); KEYWORDS.put("defer", DEFER); KEYWORDS.put("val", VAL); KEYWORDS.put("const", CONST);
        KEYWORDS.put("let", LET); KEYWORDS.put("mut", MUT); KEYWORDS.put("self", SELF); KEYWORDS.put("true", TRUE); KEYWORDS.put("false", FALSE);
        KEYWORDS.put("null", NULL); KEYWORDS.put("obj", OBJ); KEYWORDS.put("arr", ARR);
    }

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int start;
    private int current;
    private int line = 1;
    private int column = 1;
    private int startColumn = 1;

    public Lexer(String source) { this.source = source == null ? "" : source; }

    public List<Token> scan() {
        while (!isAtEnd()) {
            start = current;
            startColumn = column;
            scanToken();
        }
        tokens.add(new Token(EOF, "", line, column));
        return List.copyOf(tokens);
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '(' -> add(LPAREN); case ')' -> add(RPAREN); case '{' -> add(LBRACE); case '}' -> add(RBRACE);
            case '[' -> add(LBRACKET); case ']' -> add(RBRACKET); case ',' -> add(COMMA); case '.' -> add(DOT);
            case ';' -> add(SEMICOLON); case ':' -> add(COLON); case '?' -> add(QUESTION); case '@' -> add(AT); case '`' -> add(BACKTICK); case '+' -> add(PLUS);
            case '*' -> add(STAR); case '%' -> add(PERCENT); case '|' -> add(PIPE); case '&' -> add(AMP);
            case '^' -> add(CARET); case '~' -> add(TILDE);
            case '-' -> add(match('>') ? ARROW : MINUS);
            case '!' -> add(match('=') ? BANG_EQUAL : BANG);
            case '=' -> add(match('>') ? FAT_ARROW : match('=') ? EQUAL_EQUAL : EQUAL);
            case '<' -> add(match('=') ? LTE : LT); case '>' -> add(match('=') ? GTE : GT);
            case '/' -> {
                if (match('/')) while (peek() != '\n' && !isAtEnd()) advance();
                else if (match('*')) blockComment();
                else add(SLASH);
            }
            case ' ', '\r', '\t' -> { }
            case '\n' -> newline();
            case '"', '\'' -> string(c);
            default -> {
                if (isDigit(c)) number();
                else if (isIdentStart(c)) identifier();
                else throw error("unexpected character '" + c + "'");
            }
        }
    }

    private void blockComment() {
        int depth = 1;
        while (depth > 0) {
            if (isAtEnd()) throw error("unterminated block comment");
            char c = advance();
            if (c == '\n') newline();
            else if (c == '/' && match('*')) depth++;
            else if (c == '*' && match('/')) depth--;
        }
    }

    private void string(char quote) {
        StringBuilder value = new StringBuilder();
        while (!isAtEnd() && peek() != quote) {
            char c = advance();
            if (c == '\n') { newline(); value.append('\n'); continue; }
            if (c == '\\') {
                if (isAtEnd()) throw error("unterminated string escape");
                char escaped = advance();
                value.append(switch (escaped) {
                    case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '"' -> '"'; case '\'' -> '\''; case '\\' -> '\\'; default -> escaped;
                });
            } else value.append(c);
        }
        if (isAtEnd()) throw error("unterminated string");
        advance();
        tokens.add(new Token(STRING, value.toString(), line, startColumn));
    }

    private void number() {
        while (isDigit(peek()) || peek() == '_') advance();
        boolean floating = false;
        if (peek() == '.' && isDigit(peekNext())) {
            floating = true; advance();
            while (isDigit(peek()) || peek() == '_') advance();
        }
        if (peek() == 'e' || peek() == 'E') {
            floating = true; advance();
            if (peek() == '+' || peek() == '-') advance();
            if (!isDigit(peek())) throw error("malformed exponent");
            while (isDigit(peek()) || peek() == '_') advance();
        }
        if (peek() == 'i') { advance(); add(IMAG); }
        else add(floating ? FLOAT : INT);
    }

    private void identifier() {
        while (isIdentPart(peek())) advance();
        String text = source.substring(start, current);
        add(KEYWORDS.getOrDefault(text, IDENT));
    }

    private boolean isAtEnd() { return current >= source.length(); }
    private char advance() { char c = source.charAt(current++); column++; return c; }
    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) return false;
        current++; column++; return true;
    }
    private char peek() { return isAtEnd() ? '\0' : source.charAt(current); }
    private char peekNext() { return current + 1 >= source.length() ? '\0' : source.charAt(current + 1); }
    private void newline() { line++; column = 1; }
    private void add(Token.Type type) { tokens.add(new Token(type, source.substring(start, current), line, startColumn)); }
    private boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private boolean isIdentStart(char c) { return Character.isLetter(c) || c == '_'; }
    private boolean isIdentPart(char c) { return isIdentStart(c) || isDigit(c); }
    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Oreslang lexer error at " + line + ":" + startColumn + ": " + message);
    }
}
