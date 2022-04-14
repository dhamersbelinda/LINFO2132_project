package norswap.sigh.ast;

import norswap.autumn.positions.Span;
import norswap.utils.Util;
import java.util.List;

public class PredicateRuleNode extends DeclarationNode {
    public final String name;
    public final List<ParameterNode> parameters;
    public final BlockNode block;

    @SuppressWarnings("unchecked")
    public PredicateRuleNode
        (Span span, Object name, Object parameters, Object block) {
        super(span);
        this.name = Util.cast(name, String.class);
        this.parameters = Util.cast(parameters, List.class);
        this.block = Util.cast(block, BlockNode.class);
    }

    @Override public String contents () {
        return "rule : " + parameters.toString();
    }

    @Override
    public String name () {
        return this.name + "(" + parameters.toString() + ")";
    }

    @Override
    public String declaredThing () {
        return "predicate_rule";
    }
}
