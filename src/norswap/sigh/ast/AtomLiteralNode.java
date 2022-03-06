package norswap.sigh.ast;

import norswap.autumn.positions.Span;

public final class AtomLiteralNode extends ExpressionNode {
    public final String value;
    public AtomLiteralNode (Span span, String value) {
        super(span);
        this.value = value;
    }

    @Override public String contents() {
        return String.valueOf(value);
    }
}
