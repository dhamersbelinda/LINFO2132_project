package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public final class ArgumentNode extends DeclarationNode
{
    public final SighNode arg;

    public ArgumentNode (Span span, Object arg) {
        super(span);
        this.arg = Util.cast(arg, SighNode.class);
    }

    @Override public String name () {
        return arg.contents();
    }

    @Override public String contents () {
        return arg.contents();
    }

    @Override public String declaredThing () {
        return "Argument";
    }
}