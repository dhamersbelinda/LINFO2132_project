package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class UnificationNode extends SighNode {
    public final PredicateNode left, right;

    public UnificationNode
        (Span span, Object left, Object right) {
        super(span);
        this.left = Util.cast(left, PredicateNode.class);
        this.right = Util.cast(right, PredicateNode.class);
    }

    @Override public String contents () {
        return "unification : " + left.contents() + right.contents();
    }
}