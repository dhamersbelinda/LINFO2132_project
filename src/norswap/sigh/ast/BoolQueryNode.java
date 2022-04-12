package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;

//AssignmentNode
public class BoolQueryNode extends ExpressionNode{
    public final ExpressionNode left;
    public final ExpressionNode right;

    public BoolQueryNode (Span span, Object left, Object right) {
        super(span);
        this.left = Util.cast(left, ExpressionNode.class);
        this.right = Util.cast(right, ExpressionNode.class);
    }

    @Override
    public String contents () {
        String leftEqual = left.contents() + " ?= ";

        String candidate = leftEqual + right.contents();
        System.out.println(right.getClass());
        if (candidate.length() <= contentsBudget())
            return candidate;

        candidate = leftEqual + "(?)";
        System.out.println(candidate.length() <= contentsBudget()
            ? candidate
            : "(?) ?= (?)");
        return candidate.length() <= contentsBudget()
            ? candidate
            : "(?) ?= (?)";
    }
}
