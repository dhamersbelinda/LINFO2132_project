package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class FunctorNode extends ExpressionNode{
    public final String name;

    public FunctorNode (Span span, Object aNode) {
        super(span);
        this.name = Util.cast(aNode, String.class);
    }

    @Override public String contents ()
    {
        return name;
    }
}
