package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class LogicNode extends ExpressionNode
{
    public final AtomLiteralNode aNode;

    public LogicNode (Span span, Object aNode) {
        super(span);
        this.aNode = Util.cast(aNode, AtomLiteralNode.class);
    }

    @Override public String contents ()
    {
        return aNode.contents();
    }
}
