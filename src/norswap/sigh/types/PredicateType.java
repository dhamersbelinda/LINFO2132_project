package norswap.sigh.types;

public class PredicateType extends Type {
    public static final PredicateType INSTANCE = new PredicateType();
    private PredicateType () {}

    @Override public boolean isPrimitive () {
        return false;
    } //TODO: correct?

    @Override public String name() {
        return "Predicate";
    }
}
