package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class LogicNode extends ExpressionNode
{
    public final ExpressionNode aNode;

    public LogicNode (Span span, Object aNode) {
        super(span);
        this.aNode = Util.cast(aNode, ExpressionNode.class);
    }

    @Override public String contents ()
    {
        return aNode.contents();
    }
}
