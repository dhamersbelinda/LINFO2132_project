package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class LogicNode extends ExpressionNode
{
    public final AtomLiteralNode obj;

    public LogicNode (Span span, Object obj) {
        super(span);
        this.obj = Util.cast(obj, AtomLiteralNode.class);
    }

    @Override public String contents ()
    {
        return obj.contents();
    }
}
