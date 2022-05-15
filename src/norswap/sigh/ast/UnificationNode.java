package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

public class UnificationNode extends StatementNode {
    public final PredicateUNode left, right;

    public UnificationNode
        (Span span, Object left, Object right) {
        super(span);
        this.left = Util.cast(left, PredicateUNode.class);
        this.right = Util.cast(right, PredicateUNode.class);
    }

    @Override public String contents () {
        return "unification : " + left.contents() + right.contents();
    }
}