package norswap.sigh.types;

public final class FunctorType extends Type
{
    public static final FunctorType INSTANCE = new FunctorType();
    private FunctorType () {}

    @Override public boolean isPrimitive () {
        return false;
    } //TODO: correct?

    @Override public String name() {
        return "Functor";
    }
}
